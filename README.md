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

O bucket S3 do state deve existir antes do deploy. O backend usa o lockfile nativo do S3 (`use_lockfile=true`); não requer tabela DynamoDB para locking.

## Testes

Os cenários BDD em `src/test/resources/features` rodam com:

```bash
./mvnw -B test
```
