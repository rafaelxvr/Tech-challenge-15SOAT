# Custo estimado por recurso (Learner Lab)

Valores **de lista**, us-east-1, on-demand, **não medidos**; revalidar no card. O saldo real do Lab é lido pelo Rafael. Estourar o orçamento desativa a conta e apaga tudo.

## Caminho EKS (padrão)

| Recurso | Qtde | US$/h (unit.) | US$/h | US$ por sessão de 4 h |
|---|---|---|---|---|
| EKS control plane | 1 | 0,10 | 0,100 | 0,40 |
| EC2 t3.medium (nós) | 2 | 0,0416 | 0,083 | 0,33 |
| EBS gp3 20 GB (nós) | 2 | ~0,0022 | 0,004 | 0,02 |
| IPv4 público (nós) | 2 | 0,005 | 0,010 | 0,04 |
| RDS db.t3.micro, single-AZ | 2 | 0,017 | 0,034 | 0,14 |
| RDS gp2 20 GB | 2 | ~0,0032 | 0,006 | 0,03 |
| DynamoDB on-demand (3 tabelas) | 3 | desprezível nesta carga | ~0 | ~0 |
| SQS FIFO (7 filas) | 7 | US$0,50/milhão de requisições | ~0 | ~0 |
| NAT Gateway / NLB / ALB | 0 | fora do desenho | 0 | 0 |
| **Total** | | | **~0,24** | **~0,95** |

## Caminho k3s (fallback)

| Recurso | Qtde | US$/h | US$ por sessão de 4 h |
|---|---|---|---|
| EC2 t3.large | 1 | 0,083 | 0,33 |
| EBS gp3 30 GB | 1 | ~0,003 | 0,01 |
| IPv4 público | 1 | 0,005 | 0,02 |
| RDS db.t3.micro ×2 + gp2 | 2 | ~0,040 | 0,16 |
| **Total** | | **~0,13** | **~0,53** |

## Teto e contingência (para os cards)

- Teto por sessão de 4 h: **US$2** (≈2× a estimativa, cobre data transfer, LCU/IPv4 e arredondamentos). 3–4 sessões previstas: **~US$4–8**.
- **Risco principal: recurso esquecido.** EKS ligado por 24 h ≈ US$5,8; por isso o destroy é obrigatório ao fim de cada sessão (`RUNBOOK.md`), com hard stop (início do cleanup) em T-60 min do timer.
- Fora do state e que geram custo se sobrarem: ENIs, SGs, volumes EBS órfãos, snapshots RDS (desligados: `skip_final_snapshot`), filas.

## Cards propostos (cada um exige aprovação do Rafael; nenhuma ação AWS antes)

| # | Card | Ação AWS | Custo |
|---|---|---|---|
| 1 | Preflight de conta/região/quota | `sts get-caller-identity`, `eks describe-cluster-versions`, `iam get-role` (LabRole, LabEksClusterRole), quota de vCPU/instâncias; somente leitura | 0 |
| 2 | `plan` do módulo | `terraform plan` com `-var-file` privado e `plan -out` fora do repositório | 0 |
| 3 | `apply` rede + RDS + DynamoDB + SQS | cria os recursos de dados | ~0,05/h |
| 4 | `apply` EKS + node group | cria cluster e nós | ~0,19/h |
| 5 | Secret/deploy dos 3 serviços | `kubectl` (Secret por stdin, overlay `aws`, port-forward) | 0 |
| 6 | `destroy` + inventário pós-destroy | `kubectl delete svc` (se houver LB), `terraform destroy`, inventário de ENI/SG/EBS/filas/snapshots | 0 |
| F | Fallback k3s (só se G8 acionar) | `apply` com `compute_mode=k3s`, SSM para kubeconfig, destroy | ~0,13/h |
