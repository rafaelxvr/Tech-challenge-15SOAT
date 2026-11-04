# Segurança do módulo aws-academy (condições UNR-608 C1–C8)

| Condição | Como o módulo atende | Verificação |
|---|---|---|
| C1 rede | Sem LB/NLB/ALB. Nenhuma regra de entrada de `0.0.0.0/0` (inclui 30000-32767). EKS: endpoint privado ligado (M1) e público só para o `/32` (`public_access_cidrs = [var.operator_cidr]`, validação rejeita `0.0.0.0`, privado, loopback, CGNAT, link-local); k3s 6443 só do `/32`. Actuator só `health` (`application-aws.yml`). | `verify-offline.sh` (entrada, aws_lb*/elb/NAT/EIP, endpoint EKS), `verify-overlay.sh` (também recusa Ingress/Gateway/HTTPRoute) |
| C2 LabRole nos pods | IMDSv2 `required`, `hop_limit=2`; sem DaemonSet privilegiado/`hostNetwork`; `automountServiceAccountToken: false`; New Relic por agente em processo, não `nri-bundle`; perfil `aws` recusa (`AwsLearnerLabGuard`) toda fonte de credencial que a `DefaultCredentialsProvider` consulta antes do IMDS (chaves por env/JVM, `AWS_PROFILE`/`aws.profile`, `AWS_CONTAINER_CREDENTIALS_*`, web identity, `~/.aws/credentials`/config com credencial), IMDS desligado ou com endpoint alterado, qualquer override de endpoint (`AWS_ENDPOINT_URL*`, `aws.endpointUrl*`, `endpoint_url` na config compartilhada) fora de `https://*.amazonaws.com` e região fora do Lab. | testes `AwsLearnerLabGuardTest`, `verify-overlay.sh` |
| C3 trava de conta | `profile=fiap-lab`, `allowed_account_ids`, região travada, `shared_credentials_files`; nenhum `aws_iam_*`. | `verify-offline.sh` |
| C4 endurecimento | RDS não público, 5432 só do SG dos workloads, criptografado, TLS forçado, sem snapshot/backup/deletion protection; EKS sem SSH e sem logs; SQS com SSE e sem policy; DynamoDB sem PITR. Usuários de runtime por serviço sem DDL (migration usa o master). | `verify-offline.sh` |
| C5 segredos | `random_password` no state local (texto claro): ACL restrita e state apagado após destroy; outputs `sensitive`; sem Secret via Terraform; Secret por stdin. | `verify-offline.sh` (regex de chave/ARN), revisão |
| C7 mídia | Mascarar endpoints/DNS e IP; não abrir cabeçalho do console nem "AWS Details" (SecurityEngineer em G10/G12). | fora deste PR |
| C8 | Preflight, execução supervisionada, destroy com inventário. | `RUNBOOK.md` |

## Limitações aceitas e registradas

- **RDS em subnets públicas com rota para o IGW** (sem NAT, decisão de custo): defesa em profundidade = `publicly_accessible=false` + SG só dos workloads + TLS forçado. Sem rota privada dedicada.

- **Pods herdam a `LabRole` do nó** (IRSA indisponível no Lab). Sem alegar isolamento por NetworkPolicy. Mitigações: só 3 serviços no cluster, IMDSv2, guarda inversa, `End Lab` após a gravação.
- **Sem auditoria do Kubernetes** (logs do control plane desligados por custo).
- **State com segredos em texto claro** (mitigado por local + ACL + apagar após destroy).
- **Fallback k3s** instala o k3s via `get.k3s.io` na inicialização, com versão pinada (`k3s_version`); aceito só para o caminho alternativo.
- `checkov`/`tfsec` não estavam instalados na máquina desta execução: **não rodados**. A evidência por SHA para G7 deve vir de CI ou do SecurityEngineer.
