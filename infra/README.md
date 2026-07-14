# Documentação dos recursos Terraform — ver também README na raiz.
#
# Pré-requisitos no host:
# - Docker Desktop (engine ativo)
# - kind (https://kind.sigs.k8s.io/)
# - kubectl
# - terraform >= 1.5
#
# Fluxo:
#   1. docker build -t oficina-mecanica:local .
#   2. cd infra && terraform init && terraform apply
#   3. Acesse http://localhost:30080/api/swagger-ui.html
#
# Destruir:
#   terraform destroy
