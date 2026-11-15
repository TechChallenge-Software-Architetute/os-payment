# os-payment

Worker Spring Boot que consome pedidos de pagamento do SQS, cria preferências no Mercado Pago, registra o pedido no DynamoDB, consulta pagamentos pendentes periodicamente e publica ações na fila de saída. Não disponibiliza endpoints REST.

## Fluxo e contrato

- Entrada: `order`, `amount` e `correlationId` (opcional; o serviço gera um UUID quando ausente).
- Moeda fixa: `BRL`. O serviço não recebe email nem detalhes dos itens.
- Saída: `order`, `correlationId`, `action`, `amount`, `currency`, `paymentId`, `checkoutUrl`, `errorCode`, `errorMessage` e `occurredAt`.
- Ações: `ORDER_GENERATED`, `EXECUTAR_SERVICO` e `NEGAR_SERVICO`.
- O polling consulta o Mercado Pago e publica a transição final na fila de saída.

## Infraestrutura AWS

`infra/terraform` provisiona os recursos usados pelo worker:

| Recurso | Nome padrão |
| --- | --- |
| Fila de entrada | `os-payment-<environment>-payment-requests` |
| DLQ de entrada | `os-payment-<environment>-payment-requests-dlq` |
| Fila de saída | `os-payment-<environment>-payment-status` |
| Tabela de ordens | `os-payment-<environment>-payment-orders` |
| Repositório ECR | `os-payment-<environment>` |
| Cluster e serviço ECS | `os-payment-<environment>` |

A tabela DynamoDB usa `order` como chave de partição e o índice `action-updatedAt-index` para localizar pagamentos pendentes. O task role tem acesso às filas e à tabela. O token do Mercado Pago é injetado no task definition pelo GitHub Actions e não é armazenado no state do Terraform.

O Terraform também remove a infraestrutura Lambda antiga. Ao aplicar a mudança sobre um state que já criou esses recursos, revise o plano: ele deve remover a Lambda e a tabela `os-payment-<environment>-payments` legada. A tabela nova `payment-orders` permanece e não é substituída. A fila de entrada e sua DLQ são migradas com blocos `moved`, preservando-as.

## Execução local

Copie `.env.example` para `.env`, preencha `MERCADO_PAGO_ACCESS_TOKEN` com um token de teste e inicie o ambiente:

```bash
docker compose up --build
```

O Compose inicia LocalStack com as filas e a tabela locais. Para executar pelo IntelliJ, inicie a infraestrutura com `docker compose up -d queue-init` e defina `SPRING_PROFILES_ACTIVE=local` na Run/Debug Configuration. O perfil local aponta para `http://localhost:4566`, usa região `eu-west-3`, credenciais `test/test` e tabela `payment_orders`. O `.env` não é carregado automaticamente pelo IntelliJ; configure `MERCADO_PAGO_ACCESS_TOKEN` na Run Configuration se for testar checkout.

## Deploy

O workflow `.github/workflows/pipeline.yml` executa os testes, valida o Terraform, constrói e publica a imagem no ECR e aplica os recursos ECS. Depois injeta `MERCADO_PAGO_ACCESS_TOKEN` a partir do GitHub Secret e reinicia o serviço. O `task_definition` gerido manualmente pelo workflow fica preservado nas próximas aplicações do Terraform.

Secrets usados no GitHub Actions:

- `AWS_ACCOUNT_ID_DEVELOP`, `AWS_ACCESS_KEY_ID_DEVELOP`, `AWS_SECRET_ACCESS_KEY_DEVELOP` e `TF_STATE_BUCKET_DEVELOP`.
- Os equivalentes `_MAIN` para o ambiente `main`.
- `AWS_REGION` (opcional; padrão `us-east-1`).
- `MERCADO_PAGO_ACCESS_TOKEN`.
- `DD_API_KEY` (opcional; liga o Datadog — ver [Observabilidade](#observabilidade-datadog)).

Variável de repositório opcional: `DD_SITE` (padrão `datadoghq.com`).

O bucket S3 do state deve existir antes do deploy. O backend usa o lockfile nativo do S3 (`use_lockfile=true`); não requer tabela DynamoDB para locking.

## Observabilidade (Datadog)

Segue o modelo recomendado pelo Datadog para ECS Fargate. Com o secret `DD_API_KEY`
configurado, a task passa a ter três containers:

| Container | Papel |
| --- | --- |
| `os-payment` | O worker, com o `dd-java-agent` carregado por `JAVA_TOOL_OPTIONS`. Os logs saem por FireLens direto para o Datadog. |
| `datadog-agent` | Recebe os traces em `localhost:8126` e coleta as métricas dos containers (`ECS_FARGATE=true`). Não é essencial: se parar, o worker continua. |
| `log-router` | Fluent Bit (FireLens) que envia o stdout do worker para o Datadog. Os logs dele mesmo ficam no CloudWatch. |

- **Liga/desliga:** sem `DD_API_KEY`, a task sobe exatamente como antes: só o
  worker, com logs no CloudWatch e sem tracer. É o mesmo comportamento do Agent
  no `os-management`.
- **Tracer:** o `Dockerfile` baixa o `dd-java-agent.jar` para `/app`, mas ele só é
  carregado quando a task define `JAVA_TOOL_OPTIONS`. Rodando localmente ou no
  Compose, o jar fica inerte.
- **API key:** fica no Secrets Manager (`os-payment-<environment>-datadog-api-key`).
  O ECS injeta a chave no Agent e no FireLens pela execution role, que tem leitura
  só nesse secret.
- **Tags:** `DD_SERVICE=os-payment`; `DD_ENV` com o mesmo mapeamento do
  `os-management` (`main` → `production`, `develop` → `homolog`, outras →
  `developer`); `DD_VERSION` = SHA do commit (a tag da imagem).
- **Tamanho da task:** com Datadog, sobe para no mínimo 0,5 vCPU / 1 GB. O worker
  fica limitado a 640 MB, para a JVM não reservar a memória dos sidecars.

### Como validar

1. Faça o deploy na `develop` com o secret `DD_API_KEY` configurado. No fim do
   `terraform apply`, o output `datadog_enabled` tem que ser `true`.
2. No console do ECS, a task do serviço `os-payment-develop` tem que mostrar os
   três containers em execução.
3. Envie um pedido de pagamento para a fila de entrada:

   ```bash
   QUEUE_URL=$(aws sqs get-queue-url --queue-name os-payment-develop-payment-requests --query QueueUrl --output text)
   aws sqs send-message --queue-url "$QUEUE_URL" \
     --message-body '{"order":"teste-datadog","amount":10.0,"correlationId":"teste-datadog"}'
   ```

4. No Datadog, filtre por `service:os-payment env:homolog`:
   - **APM:** o serviço `os-payment`, com os spans do SQS, do DynamoDB, da
     chamada HTTP ao Mercado Pago e da publicação na fila de saída. Como o
     listener do Spring Cloud AWS é assíncrono, confira se o processamento de uma
     mensagem aparece num trace único ou dividido;
   - **Logs:** as linhas do worker, com `correlationId` e `order`;
   - **Infrastructure → Containers:** os três containers da task.

### Teste local (sem AWS)

O script `datadog/test-local.sh` sobe o `docker-compose.yml` (LocalStack + worker)
junto com o `datadog/docker-compose.datadog.yml`, que acrescenta um Datadog Agent e
liga o tracer no worker pelo mesmo `JAVA_TOOL_OPTIONS` da task do ECS. Os dados
chegam ao Datadog com `service:os-payment env:developer`.

Requer Docker rodando e uma API key do Datadog (pode ser de uma conta de trial).
A chave pode ser exportada no terminal ou colocada no `.env`, que não vai para o
git. No Git Bash, na raiz do repositório:

```bash
export DD_API_KEY=...          # ou DD_API_KEY=... no .env
export DD_SITE=datadoghq.com   # padrão; troque se a conta for de outro site
bash datadog/test-local.sh up          # build, LocalStack, Agent e worker + verificações
bash datadog/test-local.sh traffic 5   # 5 pedidos de pagamento na fila (padrão 3) + links do Datadog
bash datadog/test-local.sh down        # derruba tudo
```

No fim do `up` tem que aparecer `OK dd-java-agent carregado` e a API key como
válida. Sem `MERCADO_PAGO_ACCESS_TOKEN` no `.env`, a chamada ao Mercado Pago
falha, mas o trace e os logs do erro aparecem no Datadog do mesmo jeito.

Aqui o Agent coleta os logs pelo Docker. O FireLens e o tamanho da task só existem
no ECS e são validados no deploy.

**Propagação pelo SQS:** quem publica na fila com o tracer do Datadog grava o
contexto no atributo `_datadog`. O consumidor aqui também roda com o tracer, então
o trace pode aparecer ligado ao do produtor. Isso depende do produtor
(`os-orchestrator`) também estar instrumentado.

## Testes

Os cenários BDD em `src/test/resources/features` rodam com:

```bash
./mvnw -B test
```
