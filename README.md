# os-payment

Pagamentos da saga de ordens de serviço. Consome a fila de requisições de
pagamento e registra cada requisição no DynamoDB.

> **Estado atual: infraestrutura + handler placeholder.** As regras de negócio de
> pagamento e o contrato de mensagem com o `os-orchestrator` ainda não foram
> definidos. O handler persiste a requisição e para aí — de propósito, ele **não**
> publica resultado de volta para o orquestrador, porque inventar esse formato
> agora amarraria a saga a um contrato que ninguém acordou.

---

## Infraestrutura (`infra/terraform`)

Este repositório cria **apenas o que é seu**:

| Recurso | Nome |
|---|---|
| Fila de entrada | `os-payment-<env>-payment-requests` |
| DLQ (redrive, maxReceiveCount 5) | `os-payment-<env>-payment-requests-dlq` |
| Tabela de pagamentos | `os-payment-<env>-payments` |
| Lambda consumidora | `os-payment-<env>-payment-handler` |

`<env>` é o nome da branch (`develop`/`main`).

Tabela: PK `paymentId` (S), GSI `orderId-index` em `orderId` (a saga busca por
ordem, não por pagamento), `PAY_PER_REQUEST`.

### Deploy independente

Nenhum `terraform_remote_state` e nenhum recurso criado dentro do stack de outro
repositório. O `aws_lambda_event_source_mapping` aponta para a fila **deste**
repositório, então não é dependência cruzada — um mapping na fila de outro
serviço seria, e por isso não existe aqui.

Filas de terceiros são endereçadas por nome determinístico
(`os-<serviço>-<env>-<propósito>`), então este deploy roda em qualquer ordem,
inclusive primeiro, em conta vazia.

O `os-orchestrator` recebe permissão de `sqs:SendMessage` pela própria policy IAM
dele — por isso `producer_role_arns` vem vazio por padrão e nenhuma resource
policy é criada. Preencha somente para produtores em outra conta.

---

## Build

```bash
./mvnw -B clean package
```

Gera o fat jar `target/os-payment.jar`, que é o artefato que o Terraform envia
para a Lambda (`filename` + `source_code_hash`). O jar é construído no mesmo job
do deploy, como em `os-management-lambda`.

## Variáveis de ambiente da Lambda

| Variável | Origem |
|---|---|
| `PAYMENTS_TABLE` | injetada pelo Terraform |

Credenciais e região vêm do default provider chain (a execution role). Não há
endpoint override nem credencial fixa no código.

## Segurança

- Policy da Lambda restrita: só esta fila, só esta tabela, só as ações que o
  handler executa (`ReceiveMessage`/`DeleteMessage`/`GetQueueAttributes`,
  `PutItem`). Sem VPC — a função só fala com SQS e DynamoDB pela API da AWS.
- SSE-SQS, encryption-at-rest e PITR habilitados.
- Log group criado explicitamente com retenção de 14 dias; sem isso a Lambda cria
  o grupo com retenção "never expire".
- `visibility_timeout` da fila = 6x o timeout da função, para o SQS não
  reentregar mensagem ainda em processamento.
