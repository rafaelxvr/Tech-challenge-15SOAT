# Infraestrutura local com Terraform

Este diretório contém a Infraestrutura como Código da Fase 2. O Terraform cria um cluster Kubernetes local com Kind, carrega a imagem Docker da API e aplica os manifestos de `k8s/`, incluindo banco de dados, aplicação, configurações, armazenamento e escalabilidade.

## Pré-requisitos

- Docker Desktop com o engine em execução;
- Terraform 1.5 ou superior;
- Kind disponível no `PATH`;
- `kubectl` disponível no `PATH`;
- imagem Docker da aplicação construída localmente.

Confirme as instalações no PowerShell:

```powershell
docker version
terraform version
kind version
kubectl version --client
```

## Recursos gerenciados

### Recursos Terraform

| Endereço | Tipo | Responsabilidade |
|---|---|---|
| `kind_cluster.oficina` | `kind_cluster` | Cria o cluster Kind com um nó `control-plane` e mapeia o NodePort da API para o host. |
| `null_resource.load_image` | `null_resource` | Executa `kind load docker-image` para disponibilizar a imagem local dentro do cluster. |
| `null_resource.apply_k8s` | `null_resource` | Seleciona o contexto do cluster, aplica os manifestos de `k8s/` e aguarda os rollouts do PostgreSQL e da API. |

### Recursos Kubernetes aplicados pelo Terraform

| Grupo | Recursos principais |
|---|---|
| Namespace | `oficina` |
| Configuração | ConfigMap `oficina-config` e Secret `oficina-secrets` |
| Banco de dados | Deployment e Service `oficina-postgres`, com PVC de 5 GiB |
| Aplicação | Deployment e Service `oficina-app` |
| Escalabilidade | HPA `oficina-app-hpa`, de 2 a 6 réplicas, com metas de 60% de CPU e 70% de memória |
| E-mail local | Deployment e Service `oficina-mailhog` |

O banco é provisionado por meio dos manifestos Kubernetes aplicados pelo `null_resource.apply_k8s`. As migrações de esquema são executadas pelo Flyway durante a inicialização da API.

## Variáveis

| Variável | Tipo | Padrão | Descrição |
|---|---|---|---|
| `cluster_name` | `string` | `oficina-k8s` | Nome do cluster Kind. O contexto criado será `kind-oficina-k8s`. |
| `app_image` | `string` | `oficina-mecanica:local` | Imagem Docker carregada no cluster. Ela deve existir no Docker local antes do `apply`. |
| `http_node_port` | `number` | `30080` | Porta exposta no host e utilizada pelo Service da API. |

Valores diferentes podem ser informados pela linha de comando:

```powershell
terraform apply `
  -var="cluster_name=oficina-k8s" `
  -var="app_image=oficina-mecanica:local" `
  -var="http_node_port=30080"
```

## Outputs

| Output | Conteúdo |
|---|---|
| `kubeconfig_path` | Caminho do kubeconfig gerado pelo provider Kind. |
| `cluster_name` | Nome efetivo do cluster. |
| `api_url` | URL-base da API exposta no host. |
| `recursos_criados` | Resumo dos componentes de cluster, banco, aplicação, configuração, e-mail e migrações. |

Consulte os valores após o provisionamento:

```powershell
terraform output
terraform output api_url
```

## Provisionamento

Execute a partir da raiz do repositório:

```powershell
docker build -t oficina-mecanica:local .
Set-Location infra
terraform init
terraform fmt -check
terraform validate
terraform plan
terraform apply
```

Revise o plano e confirme o `apply` quando solicitado. Ao concluir, a documentação Swagger estará disponível em:

```text
http://localhost:30080/api/swagger-ui.html
```

Como alternativa, depois que o cluster existir, o script `apply-k8s.ps1` pode recarregar a imagem e reaplicar os manifestos:

```powershell
Set-Location ..
.\infra\apply-k8s.ps1 -Image "oficina-mecanica:local" -ClusterName "oficina-k8s"
```

## Validação

Verifique o cluster e os rollouts:

```powershell
kubectl config use-context kind-oficina-k8s
kubectl cluster-info
kubectl -n oficina get deployments,services,pods,hpa,pvc
kubectl -n oficina rollout status deployment/oficina-postgres --timeout=180s
kubectl -n oficina rollout status deployment/oficina-app --timeout=300s
```

Valide a saúde da aplicação:

```powershell
Invoke-RestMethod http://localhost:30080/api/actuator/health
```

O resultado esperado é `status: UP`. Se o HPA exibir métricas como `<unknown>`, confirme que o Metrics Server está disponível no cluster.

## Destruição

No diretório `infra/`, visualize e execute a remoção:

```powershell
terraform plan -destroy
terraform destroy
```

Esse comando remove o cluster Kind e, com ele, os recursos Kubernetes e os dados do PVC. A imagem `oficina-mecanica:local` permanece no Docker do host.

## Solução de problemas

| Sintoma | Verificação |
|---|---|
| Terraform não consegue criar o cluster | Confirme que o Docker Desktop está em execução com `docker version`. |
| `kind load docker-image` informa imagem inexistente | Execute novamente `docker build -t oficina-mecanica:local .` na raiz. |
| Contexto Kubernetes não encontrado | Liste os clusters com `kind get clusters` e os contextos com `kubectl config get-contexts`. |
| Rollout da API não conclui | Consulte `kubectl -n oficina get pods` e `kubectl -n oficina logs deployment/oficina-app`. |
| Porta `30080` ocupada | Libere a porta ou altere `http_node_port`, mantendo o Service e o mapeamento do Kind consistentes. |
