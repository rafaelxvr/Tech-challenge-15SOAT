#!/usr/bin/env bash
# Verificacao OFFLINE do modulo infra/aws-academy: nenhuma credencial, nenhuma chamada AWS, sem plan/apply.
# (terraform init baixa so providers do registry; nao toca a conta AWS.)
set -euo pipefail
cd "$(dirname "$0")"
fail() { echo "FALHOU: $*" >&2; exit 1; }

# 1. Sem credenciais AWS no ambiente desta verificacao.
for v in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_PROFILE AWS_CONTAINER_CREDENTIALS_RELATIVE_URI AWS_CONTAINER_CREDENTIALS_FULL_URI AWS_WEB_IDENTITY_TOKEN_FILE; do
  [ -z "${!v:-}" ] || fail "variavel $v definida: rode sem credenciais AWS"
done

# 2. Proibido qualquer recurso IAM (so data sources de roles/profiles existentes do Learner Lab).
if grep -nE '^\s*resource\s+"aws_iam_' ./*.tf; then fail "recurso aws_iam_* proibido (UNR-608 C3)"; fi

# 3. Nenhuma regra de ENTRADA aberta ao mundo (inclui 30000-32767) e nenhum Principal "*".
#    Cobre aws_vpc_security_group_ingress_rule, aws_security_group_rule (type=ingress) e blocos ingress inline.
#    Toda origem CIDR de uma regra de entrada deve ser exatamente var.operator_cidr (nunca literal, nunca outra variavel).
ingress_abertas="$(awk '
  function avalia(ctx, rotulo,   n, i, l, linhas) {
    n = split(ctx, linhas, "\n")
    for (i = 1; i <= n; i++) {
      l = linhas[i]
      if (l ~ /^[ 	]*#/) continue
      if (l ~ /0\.0\.0\.0\/0|::\/0/) { print rotulo ": " l; continue }
      if (l ~ /(cidr_ipv4|cidr_ipv6|cidr_blocks|ipv6_cidr_blocks|prefix_list_ids?)[ 	]*=/ && l !~ /=[ 	]*\[?[ 	]*var\.operator_cidr[ 	]*\]?[ 	]*(#.*)?$/) print rotulo ": " l
    }
  }
  FNR == 1 { emres = 0 }
  /^resource[ 	]+"/ { t = $2; gsub(/"/, "", t); nome = FILENAME ":" $3; corpo = ""; emres = 1; next }
  emres && /^}/ {
    if (t == "aws_vpc_security_group_ingress_rule") avalia(corpo, nome)
    else if (t == "aws_security_group_rule" && corpo ~ /type[ 	]*=[ 	]*"ingress"/) avalia(corpo, nome)
    else if (t == "aws_security_group") {
      n = split(corpo, ls, "\n"); dentro = 0; blk = ""
      for (i = 1; i <= n; i++) {
        if (ls[i] ~ /^[ 	]*ingress[ 	]*(=[ 	]*\[[ 	]*)?\{/) { dentro = 1; blk = "" }
        if (dentro) blk = blk ls[i] "\n"
        if (dentro && ls[i] ~ /^[ 	]*\}/) { avalia(blk, nome); dentro = 0 }
      }
    }
    emres = 0; next
  }
  emres { corpo = corpo $0 "\n" }
' ./*.tf)"
[ -z "$ingress_abertas" ] || { echo "$ingress_abertas" >&2; fail "regra de entrada fora de var.operator_cidr/SG de referencia (0.0.0.0/0 ou CIDR nao controlado)"; }
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
# Sem NAT/LB/EIP (inclui aws_lb_listener, aws_lb_target_group, aws_alb*, aws_elb*): a entrada e port-forward.
if grep -nE 'resource +"aws_(a?lb|elb|nat_gateway|eip|globalaccelerator_[a-z_]+|api_gateway[a-z_0-9]*|apigatewayv2_[a-z_]+|cloudfront_distribution|wafv2_[a-z_]+)(_[a-z_0-9]+)?"' ./*.tf; then
  fail "NAT/LB/EIP/gateway publico nao fazem parte do desenho"
fi
# Nada de Service/Ingress Kubernetes nem helm via Terraform (LoadBalancer/NodePort).
if grep -nE 'resource +"(kubernetes_(service|ingress)[a-z_0-9]*|helm_release)"|LoadBalancer|NodePort' ./*.tf | grep -vE ':[0-9]+:\s*#'; then
  fail "Service LoadBalancer/NodePort/Ingress/helm via Terraform proibido"
fi
# EKS: endpoint privado ligado (nos alcancam a API) e publico restrito exatamente ao /32 do operador.
grep -qE 'endpoint_private_access *= *true' eks.tf || fail "EKS: endpoint_private_access deve ser true"
grep -qE 'public_access_cidrs *= *\[var\.operator_cidr\]' eks.tf || fail "EKS: public_access_cidrs deve ser exatamente [var.operator_cidr]"

# 5. Sem segredos/identificadores reais versionados (AKIA/ASIA, ARN com conta, 12 digitos fora do exemplo).
if grep -rnIE 'AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|arn:aws:[a-z0-9-]*:[a-z0-9-]*:[0-9]{12}:' . --exclude-dir=.terraform --exclude=verify-offline.sh; then
  fail "possivel credencial/ARN com conta"
fi
# Aceita zero arquivo (glob sem casamento nao pode mascarar o resultado): qualquer saida de git ls-files e falha.
# Exclui apenas o modelo terraform.tfvars.example (extensao .example nao casa '*.tfvars').
if git ls-files -- '*.tfvars' '*.tfvars.json' '*.tfstate' '*.tfstate.backup' '*tfplan*' | grep -q .; then fail "tfvars/tfstate/tfplan versionado"; fi

# 6. Formatacao e validacao (sem backend, sem credenciais).
terraform fmt -check -recursive
terraform init -backend=false -input=false -no-color >/dev/null
terraform validate -no-color
echo "verify-offline: OK"
