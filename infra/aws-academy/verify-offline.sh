#!/usr/bin/env bash
# Verificacao OFFLINE do modulo infra/aws-academy: nenhuma credencial, nenhuma chamada AWS, sem plan/apply.
# (terraform init baixa so providers do registry; nao toca a conta AWS.)
set -euo pipefail
cd "$(dirname "$0")"
fail() { echo "FALHOU: $*" >&2; exit 1; }

# 1. Sem credenciais AWS no ambiente desta verificacao.
for v in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_PROFILE; do
  [ -z "${!v:-}" ] || fail "variavel $v definida: rode sem credenciais AWS"
done

# 2. Proibido qualquer recurso IAM (so data sources de roles/profiles existentes do Learner Lab).
if grep -nE '^\s*resource\s+"aws_iam_' ./*.tf; then fail "recurso aws_iam_* proibido (UNR-608 C3)"; fi

# 3. Nenhuma regra de ENTRADA aberta ao mundo (inclui 30000-32767) e nenhum Principal "*".
if grep -nE -A10 'aws_vpc_security_group_ingress_rule|ingress\s*\{' ./*.tf | grep -E '0\.0\.0\.0/0|::/0'; then
  fail "regra de entrada com 0.0.0.0/0"
fi
if grep -nE 'Principal|aws_sqs_queue_policy|aws_iam_policy_document' ./*.tf | grep -v '^\S*:[0-9]*:\s*#'; then
  fail "politica/Principal inesperado"
fi

# 4. Endurecimento obrigatorio presente.
grep -q 'publicly_accessible *= *false' rds.tf || fail "RDS deve ter publicly_accessible=false"
grep -q 'storage_encrypted *= *true' rds.tf || fail "RDS deve ter storage_encrypted=true"
grep -q 'http_tokens *= *"required"' eks.tf || fail "IMDSv2 obrigatorio no launch template"
grep -q 'http_tokens *= *"required"' k3s.tf || fail "IMDSv2 obrigatorio na EC2 k3s"
grep -q 'allowed_account_ids' provider.tf || fail "trava de conta ausente"
grep -q 'profile *= *"fiap-lab"' provider.tf || fail "perfil fiap-lab ausente"
! grep -nE 'aws_nat_gateway|aws_lb\b|aws_alb|aws_eip' ./*.tf || fail "NAT/LB/EIP nao fazem parte do desenho"

# 5. Sem segredos/identificadores reais versionados (AKIA/ASIA, ARN com conta, 12 digitos fora do exemplo).
if grep -rnIE 'AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|arn:aws:[a-z0-9-]*:[a-z0-9-]*:[0-9]{12}:' . --exclude-dir=.terraform --exclude=verify-offline.sh; then
  fail "possivel credencial/ARN com conta"
fi
if git ls-files --error-unmatch ./*.tfvars ./*.tfstate >/dev/null 2>&1; then fail "tfvars/tfstate versionado"; fi

# 6. Formatacao e validacao (sem backend, sem credenciais).
terraform fmt -check -recursive
terraform init -backend=false -input=false -no-color >/dev/null
terraform validate -no-color
echo "verify-offline: OK"
