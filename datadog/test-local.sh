#!/usr/bin/env bash
# Sobe o os-payment localmente (Docker Compose + LocalStack) com o Datadog Agent e o
# tracer ligados, para validar a integração sem AWS:
#   - o worker carrega o dd-java-agent embutido na imagem (JAVA_TOOL_OPTIONS),
#     como a task do ECS faz;
#   - traces e logs do worker chegam ao Datadog com service:os-payment.
# O que NÃO dá para ver aqui: o FireLens e o tamanho da task, que só existem no
# ECS, e a fila real do orquestrador.
#
# Uso (Git Bash, na raiz do repositório):
#   export DD_API_KEY=<api key>        # ou DD_API_KEY=... no arquivo .env (ignorado pelo git)
#   export DD_SITE=datadoghq.com        # opcional; padrão datadoghq.com (US1)
#   bash datadog/test-local.sh up          # build, LocalStack, Agent e worker + verificações
#   bash datadog/test-local.sh traffic 5   # envia 5 pedidos de pagamento para a fila (padrão 3)
#   bash datadog/test-local.sh check       # repete as verificações
#   bash datadog/test-local.sh diag        # diagnóstico do Agent
#   bash datadog/test-local.sh down        # derruba tudo
#
# Requer Docker rodando e curl. MERCADO_PAGO_ACCESS_TOKEN (no .env) é opcional: sem
# ele a chamada ao Mercado Pago falha, mas o trace e os logs do erro aparecem igual.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

COMPOSE=(docker compose -f docker-compose.yml -f datadog/docker-compose.datadog.yml)

# Lê uma variável do .env quando ela não foi exportada no terminal.
from_env_file() {
  local name="$1"
  if [ -z "${!name:-}" ] && [ -f .env ]; then
    local value
    value="$(sed -n "s/^${name}=//p" .env | tail -1 | tr -d '\r' || true)"
    value="${value%\"}"; value="${value#\"}"
    [ -n "$value" ] && printf -v "$name" '%s' "$value"
  fi
  return 0
}

from_env_file DD_API_KEY
from_env_file DD_SITE
export DD_API_KEY="${DD_API_KEY:-}"
export DD_SITE="${DD_SITE:-datadoghq.com}"
export DD_ENV="${DD_ENV:-developer}"   # um dos valores do filtro env dos dashboards
REGION="${AWS_REGION:-eu-west-3}"       # região do LocalStack no docker-compose.yml

log() { printf '\n==> %s\n' "$*"; }
die() { echo "ERRO: $*" >&2; exit 1; }

require() {
  local c
  for c in "$@"; do
    command -v "$c" >/dev/null 2>&1 || die "comando '$c' não encontrado no PATH."
  done
}

# URL da interface web: app.<site> para US1/EU/gov, <site> para us3/us5/ap1/ap2.
app_url() {
  case "$DD_SITE" in
    datadoghq.com|datadoghq.eu|ddog-gov.com) echo "https://app.$DD_SITE" ;;
    *) echo "https://$DD_SITE" ;;
  esac
}

container_id() { "${COMPOSE[@]}" ps -q "$1" 2>/dev/null || true; }

validate_key() {
  [ -n "$DD_API_KEY" ] || die "defina DD_API_KEY (export DD_API_KEY=... ou no arquivo .env)."
  local answer
  answer="$(curl -s "https://api.$DD_SITE/api/v1/validate" -H "DD-API-KEY: $DD_API_KEY" || true)"
  if ! grep -q '"valid":true' <<<"$answer"; then
    echo "A API key não foi aceita em $DD_SITE (resposta: ${answer:-sem resposta})." >&2
    echo "Confira a chave e o site da conta pela URL depois do login: app.datadoghq.com" >&2
    echo "é o padrão (US1); app.datadoghq.eu -> export DD_SITE=datadoghq.eu." >&2
    exit 1
  fi
  echo "API key válida em $DD_SITE."
}

up() {
  require docker curl
  docker info >/dev/null 2>&1 || die "o Docker não está rodando. Abra o Docker Desktop e tente de novo."
  validate_key

  log "Build da imagem e subida do LocalStack, Datadog Agent e worker"
  "${COMPOSE[@]}" up -d --build

  log "Aguardando o Datadog Agent ficar saudável"
  local i status cid
  for i in $(seq 1 60); do
    cid="$(container_id datadog-agent)"
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || true)"
    [ "$status" = "healthy" ] && { echo "Agent saudável."; break; }
    sleep 2
  done
  if [ "$status" != "healthy" ]; then
    echo "AVISO: o Agent não ficou saudável em 2 minutos (status: ${status:-desconhecido})." >&2
    diag
  fi

  log "Aguardando o worker subir com o tracer"
  local logs
  for i in $(seq 1 90); do
    logs="$("${COMPOSE[@]}" logs --no-color payment 2>/dev/null || true)"
    grep -q "DATADOG TRACER CONFIGURATION" <<<"$logs" && break
    sleep 2
  done

  check
}

check() {
  require docker
  log "Verificações"
  local logs
  logs="$("${COMPOSE[@]}" logs --no-color payment 2>/dev/null || true)"
  if grep -q "Picked up JAVA_TOOL_OPTIONS" <<<"$logs"; then
    echo "OK     JAVA_TOOL_OPTIONS aplicado no worker"
  else
    echo "ERRO   o worker não recebeu JAVA_TOOL_OPTIONS (confira o datadog/docker-compose.datadog.yml)" >&2
  fi
  if grep -q "DATADOG TRACER CONFIGURATION" <<<"$logs"; then
    echo "OK     dd-java-agent carregado"
  else
    echo "ERRO   a linha 'DATADOG TRACER CONFIGURATION' não apareceu no log do worker." >&2
    echo "       Últimas linhas do worker:" >&2
    "${COMPOSE[@]}" logs --no-color --tail 20 payment >&2 || true
  fi
  if grep -qi "Started PaymentApplication" <<<"$logs"; then
    echo "OK     worker iniciado"
  else
    echo "AVISO  o worker ainda não registrou 'Started PaymentApplication'"
  fi
  diag
  echo
  echo "Próximo passo: bash datadog/test-local.sh traffic"
}

# Mostra a saúde do Agent e a validação da API key, que é a causa mais comum de
# problema (chave errada ou de outro site).
diag() {
  echo
  echo "agent health:"
  { "${COMPOSE[@]}" exec -T datadog-agent agent health 2>&1 || true; } | sed 's/^/  /'
  echo "API key (agent status):"
  { "${COMPOSE[@]}" exec -T datadog-agent agent status 2>/dev/null || true; } \
    | grep -i -A1 "api key" | sed 's/^/  /' || true
}

# Roda o AWS CLI do serviço queue-init contra o LocalStack.
aws_local() {
  "${COMPOSE[@]}" run --rm --no-deps -T --entrypoint aws queue-init \
    --endpoint-url http://localstack:4566 --region "$REGION" "$@"
}

traffic() {
  require docker
  local total="${1:-3}" i stamp queue_url body
  log "Enviando $total pedido(s) de pagamento para a fila sqs-os-management"
  queue_url="$(aws_local sqs get-queue-url --queue-name sqs-os-management --query QueueUrl --output text | tr -d '\r')"
  [ -n "$queue_url" ] || die "fila sqs-os-management não encontrada no LocalStack. Rode 'up' primeiro."
  stamp="$(date +%s)"
  for i in $(seq 1 "$total"); do
    body="{\"order\":\"dd-teste-$stamp-$i\",\"amount\":10.0,\"correlationId\":\"dd-teste-$stamp-$i\"}"
    aws_local sqs send-message --queue-url "$queue_url" --message-body "$body" >/dev/null
    echo "enviado: $body"
  done

  log "Últimas linhas do worker (aguardando 15 s pelo processamento)"
  sleep 15
  "${COMPOSE[@]}" logs --no-color --tail 25 payment || true

  log "Onde ver no Datadog (os dados levam de 1 a 2 minutos para aparecer)"
  links
}

links() {
  local app
  app="$(app_url)"
  echo "APM:        $app/apm/services?env=$DD_ENV"
  echo "Logs:       $app/logs?query=service%3Aos-payment%20env%3A$DD_ENV"
  echo "Containers: $app/containers"
}

down() {
  require docker
  "${COMPOSE[@]}" down
}

case "${1:-up}" in
  up) up ;;
  check) check ;;
  diag) diag ;;
  traffic) traffic "${2:-3}" ;;
  links) links ;;
  down) down ;;
  *) echo "Uso: $0 [up|traffic [n]|check|diag|links|down]" >&2; exit 2 ;;
esac
