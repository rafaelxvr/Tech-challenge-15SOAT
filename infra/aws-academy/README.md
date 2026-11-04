# infra/aws-academy: Terraform do Learner Lab (escrito offline)

Módulo Terraform da Fase 4 para o AWS Academy Learner Lab (UNR-609, plano UNR-50 v9.2).
**Nada aqui foi planejado nem aplicado.** `plan`/`apply`/`destroy` só com card aprovado pelo Rafael, por recurso.

## O que o módulo descreve

| Recurso | Arquivo | Notas |
|---|---|---|
| VPC, 2 subnets públicas, IGW (sem NAT) | `network.tf` | Sem NAT Gateway, sem Multi-AZ, sem Fargate |
| EKS + managed node group 2× t3.medium | `eks.tf` | Endpoint **privado ligado** (nós em subnet pública alcançam a API sem ampliar o CIDR) e público só no `/32` do operador (`public_access_cidrs = [var.operator_cidr]`); sem SSH; sem logs do control plane; launch template com IMDSv2 (`hop_limit=2`) |
| Fallback EC2 única com k3s | `k3s.tf` | `compute_mode = "k3s"`; sem SSH; API 6443 só do `/32` |
| 2× RDS PostgreSQL 16 db.t3.micro | `rds.tf` | os-service e billing separados; não públicos; 5432 só do SG dos workloads; TLS forçado; criptografado; sem backup/snapshot |
| 3 tabelas DynamoDB on-demand | `dynamodb.tf` | Mesmos nomes/índices do LocalStack (`init.sh`); sem PITR |
| SQS FIFO + DLQs | `messaging.tf` | Mesmos nomes do Kind; `maxReceiveCount=5`; SSE; sem policy/`Principal:*` |
| `LabRole`, `LabEksClusterRole`, `LabInstanceProfile` | `data.tf` | Apenas `data` sources. **Nenhum `aws_iam_*`** (verificado por `verify-offline.sh`) |

Sem NLB/ALB: a entrada é `kubectl port-forward` em `127.0.0.1` (decisão do Rafael, 04/11). Billing consulta o Mercado Pago por polling, sem webhook público.

## Trava de conta (UNR-608 C3)

`provider.tf` fixa `profile = "fiap-lab"`, `allowed_account_ids`, região (`us-east-1`/`us-west-2`) e `shared_credentials_files` explícito.
Antes de qualquer execução supervisionada: abortar se `AWS_ACCESS_KEY_ID`/`AWS_PROFILE` do ambiente apontarem para outro perfil. O `aws` CLI sempre com `--profile fiap-lab`.

## Verificação offline (sem credencial, sem AWS)

```bash
cd infra/aws-academy
bash verify-offline.sh     # IAM proibido, entrada só de var.operator_cidr (inclui aws_security_group_rule/inline), sem aws_lb*/elb/NAT/EIP, endpoint EKS, tfvars/tfstate/tfplan não versionados, segredos, fmt, init -backend=false, validate
```

`terraform init -backend=false` baixa só os providers do registry (lock em `.terraform.lock.hcl`, linux/windows/darwin). Não toca a AWS.
Para o overlay: `bash k8s-overlays/aws/verify-overlay.sh` (e `--release` antes de aplicar: recusa digest placeholder).

## State e segredos (C5)

- State **local**, fora do repositório: `terraform -chdir=infra/aws-academy apply -state=D:\unravel-private\aws-lab\terraform.tfstate`. Sem backend S3. ACL restrita; **apagar após o destroy com inventário zero**.
- `random_password` dos RDS fica em texto claro no state. Outputs são `sensitive`; nunca copiar valores para issue/PR/log.
- `plan -out` só fora do repositório e apagado. `TF_LOG` nunca em DEBUG/TRACE.
- Sem Secret Kubernetes via Terraform. Secret `oficina-secrets` por stdin (S-11): `kubectl create secret generic oficina-secrets --from-file=DB_PASSWORD=/dev/stdin ...`. Chaves: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_*`, `SAGA_FILA_BILLING`, `SAGA_FILA_EXECUCAO`, `SAGA_FILA_RESPOSTAS` (URLs contêm o ID da conta), licença do New Relic.
- `*.tfvars`/`*.tfstate` no `.gitignore`. Exemplo em `terraform.tfvars.example`; valores reais só em arquivo privado.

## Quem executa

Rafael, ou SRE em sessão supervisionada, em foreground, com saída filtrada. Só logs redigidos são postados.

## Documentos

- [`COST.md`](COST.md): custo por recurso, teto por sessão, cards propostos.
- [`SECURITY.md`](SECURITY.md): condições C1–C8, limitações do Learner Lab.
- [`RUNBOOK.md`](RUNBOOK.md): sessão, destroy (ordem obrigatória) e fallback k3s.
- Overlay Kubernetes: [`k8s-overlays/aws`](../../k8s-overlays/aws). Perfil Spring: `application-aws.yml` + `AwsLearnerLabGuard`.
