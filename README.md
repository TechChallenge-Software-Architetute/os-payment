# os-payment

Worker de pagamentos do ecossistema `os-management`. Consome pedidos da fila `sqs-os-management`, cria uma preferência de checkout no Mercado Pago, grava o pedido no DynamoDB, consulta pagamentos pendentes periodicamente e publica os estados em `sqs-payment`. A aplicação não disponibiliza API REST e usa somente uma fila de entrada e uma de saída.

## Estrutura

Os pacotes seguem a separação usada em `os-management`:

- `domain`: modelo e estados de pagamento, sem dependências de framework.
- `application/payment`: casos de uso e portas de saída para persistência, provedor e publicação.
- `adapter/in/sqs`: consumidores SQS e modelos das mensagens de entrada.
- `adapter/in/schedule`: agendamento do polling de pagamentos.
- `adapter/out`: adaptadores DynamoDB, Mercado Pago e publicação SQS.
- `infrastructure/config`: configurações específicas de infraestrutura, quando necessárias.

Os mapeamentos entre o domínio e os documentos DynamoDB usam MapStruct. A implementação é gerada pelo processador de anotações configurado no Maven.

## Testes BDD

Os cenários Gherkin em português ficam em `src/test/resources/features` e são executados pelo Cucumber/JUnit Platform com `mvn test`. Os steps usam implementações em memória das portas da aplicação, sem acessar AWS, DynamoDB ou Mercado Pago.

## Mensagens

Entrada JSON: `orderId`, `customerEmail`, `amount` e `currency`. O serviço não recebe nem persiste descrição, quantidade ou detalhes dos produtos. O adaptador envia ao Mercado Pago uma linha técnica única (`Pedido <orderId>`, quantidade 1) com o valor total da ordem, pois a API de preferências exige `items`. Use `orderId` como identificador estável para deduplicação. Saída contém `orderId`, `status`, `amount`, `currency`, `paymentId`, `checkoutUrl`, `errorCode`, `errorMessage` e `occurredAt`.

Estados: `ORDER_GENERATED` (checkout criado ou pagamento ainda pendente), `ORDER_PAID` (pagamento aprovado e validado) e `ORDER_PAYMENT_ERROR` (falha ao gerar checkout, pagamento recusado ou inconsistência de valor/moeda). O estado gerado não significa que o pagamento foi confirmado. O polling interno pesquisa pagamentos no Mercado Pago pela `external_reference` igual ao `orderId`; quando encontra um resultado final, persiste e publica a mudança de estado. O token do provedor não deve ser exposto ao `os-management`.

Tabela `os-payment-orders` usa `orderId` como chave de partição e mantém os dados do pedido, estado do pagamento, identificador externo e URL do checkout. A tabela é criada pelo Terraform; o Compose cria uma tabela local equivalente no LocalStack.

## Execução local

Copie `.env.example` para `.env` e preencha `MERCADO_PAGO_ACCESS_TOKEN` com uma credencial de teste da aplicação Mercado Pago. Execute `docker compose up --build`. O `.env` é ignorado pelo Git. Para integração local, o Compose inicia LocalStack com SQS e DynamoDB e cria as filas e a tabela de pedidos. Credenciais AWS locais de teste, endpoints, região, nomes das filas e tabela já são definidos pelo Compose.

Variáveis locais:

| Variável | Obrigatória | Valor/configuração |
| --- | --- | --- |
| `MERCADO_PAGO_ACCESS_TOKEN` | Sim | Access Token de teste no `.env`. |
| `MERCADO_PAGO_API_URL` | Não | `https://api.mercadopago.com` por padrão. |
| `PAYMENT_POLLING_FIXED_DELAY_MS` | Não | Intervalo entre consultas, em ms; padrão `30000`. |
| `PAYMENT_POLLING_BATCH_SIZE` | Não | Pedidos verificados por ciclo; padrão `50`. |

Ao executar a aplicação fora do Compose, configure `AWS_REGION`, `SQS_ENDPOINT`, `DYNAMODB_ENDPOINT`, `DYNAMODB_PAYMENT_ORDERS_TABLE`, `SQS_INPUT_QUEUE` e `SQS_OUTPUT_QUEUE`. O polling usa `PAYMENT_POLLING_FIXED_DELAY_MS` (30.000 ms por padrão) e `PAYMENT_POLLING_BATCH_SIZE` (50 por padrão). Para LocalStack, use `http://localhost:4566` nos endpoints e credenciais de desenvolvimento `AWS_ACCESS_KEY_ID=test` e `AWS_SECRET_ACCESS_KEY=test`.

Pedidos em `ORDER_GENERATED` são consultados pelo índice DynamoDB `status-updatedAt-index`. O polling pesquisa pagamentos atualizados nos últimos 364 dias. Pagamentos sem resultado ou ainda pendentes permanecem em `ORDER_GENERATED`; pagamentos recusados/cancelados viram `ORDER_PAYMENT_ERROR`. A gravação condicional evita que múltiplas tasks publiquem uma transição final duplicada.

## AWS e GitHub Actions

`terraform/` provisiona duas filas SQS (entrada com DLQ e saída), a tabela DynamoDB com índice para ordens pendentes, ECR, ECS Fargate e logs CloudWatch. Configure GitHub Variables `AWS_REGION`, `TF_STATE_BUCKET` e `TF_LOCK_TABLE`, e Secrets `AWS_DEPLOY_ROLE_ARN` (role federada por OIDC, sem access keys de longa duração) e `MERCADO_PAGO_ACCESS_TOKEN`. O bucket S3 e a tabela DynamoDB de locking precisam existir antes do primeiro deploy. O workflow constrói a imagem, publica no ECR, aplica Terraform e registra uma nova revisão da task definition injetando o token a partir do GitHub Secret. O token não é escrito no estado do Terraform. No ambiente local, o Compose lê o token de `MERCADO_PAGO_ACCESS_TOKEN` no `.env`.

| GitHub Actions | Tipo | Como configurar |
| --- | --- | --- |
| `AWS_REGION` | Variable | Região AWS usada pelo backend e pelos recursos. |
| `TF_STATE_BUCKET` | Variable | Nome de bucket S3 já existente para o state do Terraform. |
| `TF_LOCK_TABLE` | Variable | Nome de tabela DynamoDB de locking já existente, com chave de partição `LockID` do tipo String. |
| `AWS_DEPLOY_ROLE_ARN` | Secret | ARN da role de deploy com trust OIDC para este repositório e permissões de deploy. |
| `MERCADO_PAGO_ACCESS_TOKEN` | Secret | Access Token da aplicação Mercado Pago. |

Configure em **Settings → Secrets and variables → Actions**. O workflow usa o GitHub Secret para registrar a task definition do ECS, sem passar o token ao Terraform. No ECS, o valor fica na definição da task e pode ser lido por identidades AWS autorizadas a chamar `ecs:DescribeTaskDefinition`; limite essas permissões à equipe e role necessárias. `AWS_REGION` também alimenta `TF_VAR_aws_region`. As demais variáveis Terraform têm defaults: `app_name=os-payment`, filas `sqs-os-management` e `sqs-payment`, `desired_count=1`; `image_tag` é definido pelo workflow com o SHA do commit.

Antes do primeiro deploy, configure backend remoto do Terraform com locking e ajuste a trust policy IAM do role de deploy para o repositório/branch. Para consumir fila compartilhada já criada pelo `os-management`, alinhe região, nome e permissões; por padrão este projeto cria suas filas.

## Polling de pagamentos

Não há fila de confirmação nem endpoint REST. Um scheduler interno pesquisa pagamentos no Mercado Pago e atualiza as ordens geradas. Para maior escala ou confirmação em tempo real, a arquitetura pode evoluir para Webhooks recebidos por um adaptador HTTP externo.
