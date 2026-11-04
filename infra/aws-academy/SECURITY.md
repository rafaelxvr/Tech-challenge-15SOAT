# Segurança do módulo aws-academy (condições UNR-608 C1–C8)

| Condição | Como o módulo atende | Verificação |
|---|---|---|
| C1 rede | Sem LB/NLB/ALB. Nenhuma regra de entrada de `0.0.0.0/0` (inclui 30000-32767). EKS público só para o `/32`; k3s 6443 só do `/32`. Actuator só `health` (`application-aws.yml`). | `verify-offline.sh`, `verify-overlay.sh` |
| C2 LabRole nos pods | IMDSv2 `required`, `hop_limit=2`; sem DaemonSet privilegiado/`hostNetwork`; `automountServiceAccountToken: false`; New Relic por agente em processo, não `nri-bundle`; perfil `aws` recusa credencial estática/dummy e endpoint fora de `*.amazonaws.com` (`AwsLearnerLabGuard`). | testes `AwsLearnerLabGuardTest`, `verify-overlay.sh` |
| C3 trava de conta | `profile=fiap-lab`, `allowed_account_ids`, região travada, `shared_credentials_files`; nenhum `aws_iam_*`. | `verify-offline.sh` |
| C4 endurecimento | RDS não público, 5432 só do SG dos workloads, criptografado, TLS forçado, sem snapshot/backup/deletion protection; EKS sem SSH e sem logs; SQS com SSE e sem policy; DynamoDB sem PITR. Usuários de runtime por serviço sem DDL (migration usa o master). | `verify-offline.sh` |
| C5 segredos | `random_password` no state local (texto claro): ACL restrita e state apagado após destroy; outputs `sensitive`; sem Secret via Terraform; Secret por stdin. | `verify-offline.sh` (regex de chave/ARN), revisão |
| C7 mídia | Mascarar endpoints/DNS e IP; não abrir cabeçalho do console nem "AWS Details" (SecurityEngineer em G10/G12). | fora deste PR |
| C8 | Preflight, execução supervisionada, destroy com inventário. | `RUNBOOK.md` |

## Limitações aceitas e registradas

- **Pods herdam a `LabRole` do nó** (IRSA indisponível no Lab). Sem alegar isolamento por NetworkPolicy. Mitigações: só 3 serviços no cluster, IMDSv2, guarda inversa, `End Lab` após a gravação.
- **Sem auditoria do Kubernetes** (logs do control plane desligados por custo).
- **State com segredos em texto claro** (mitigado por local + ACL + apagar após destroy).
- **Fallback k3s** instala o k3s via `get.k3s.io` na inicialização, com versão pinada (`k3s_version`); aceito só para o caminho alternativo.
- `checkov`/`tfsec` não estavam instalados na máquina desta execução: **não rodados**. A evidência por SHA para G7 deve vir de CI ou do SecurityEngineer.
