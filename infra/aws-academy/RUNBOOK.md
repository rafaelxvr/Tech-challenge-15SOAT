# Runbook de sessão no Learner Lab

Executor: Rafael, ou SRE em sessão supervisionada, em foreground, saída filtrada. **Cada passo com ação AWS exige o card aprovado correspondente** (`COST.md`). Credencial nunca em issue/PR/log.

## Antes (preflight, card 1)

1. `Start Lab`; confirmar que `AWS_ACCESS_KEY_ID`/`AWS_PROFILE` do ambiente **não** apontam para outro perfil (abortar se apontarem). Usar sempre `--profile fiap-lab`.
2. Provar região/quota e compatibilidade de `LabEksClusterRole`/`LabRole` com EKS e node group. Se falhar: acionar o fallback (abaixo).
3. `bash infra/aws-academy/verify-offline.sh` e `bash k8s-overlays/aws/verify-overlay.sh --release` no SHA que será aplicado.

## Apply (cards 3 e 4)

```bash
terraform -chdir=infra/aws-academy init -backend=false
terraform -chdir=infra/aws-academy plan  -var-file=<privado>.tfvars -state=<privado>/terraform.tfstate -out=<privado>/tfplan
terraform -chdir=infra/aws-academy apply <privado>/tfplan
```

Depois: Secret por stdin (S-11), `kubectl apply -k k8s-overlays/aws`, migrations com o usuário master, usuários de runtime sem DDL, `kubectl port-forward svc/oficina-app 8080:8080` (127.0.0.1). Apagar `tfplan`.

## Destroy (obrigatório; iniciar em T-60 min do timer; também se a sessão falhar)

1. `kubectl delete svc` de todos os Services `LoadBalancer` (não há no desenho, conferir) e esperar sumirem.
2. `terraform destroy` com o mesmo state/var-file.
3. Inventário pós-destroy no mesmo card: NLB, ENI, SG, EBS, filas SQS, snapshots RDS, instâncias. **Zero** esperado.
4. Só com inventário zero: apagar state e `tfplan`.
5. Credencial expirada: abrir sessão só para destruir, reutilizando state e autorização.

## Fallback EC2 + k3s (acionado em G8; troca de caminho é execução)

Gatilho: EKS indisponível/lento ou roles incompatíveis no preflight, ou janela estourada.

1. No var-file privado: `compute_mode = "k3s"` e `k3s_version = "vX.Y.Z+k3sN"` (pinada; registrar no card F).
2. `plan`/`apply` como acima. Cria 1× t3.large com k3s (sem Traefik/ServiceLB), os mesmos RDS/DynamoDB/SQS, `LabInstanceProfile`.
3. Kubeconfig sem SSH: via SSM Session Manager (card F) lendo `/etc/rancher/k3s/k3s.yaml` **sem colar no chat/log**; apontar `server:` para o IP público (`/32` já liberado na 6443). O arquivo contém credencial de admin: guardar fora do repositório e apagar ao fim.
4. Mesmo overlay: `kubectl apply -k k8s-overlays/aws`; entrada por `kubectl port-forward` (127.0.0.1).
5. Destroy: `terraform destroy` (a EC2, o SG e os dados saem juntos) + inventário. Não há Service `LoadBalancer`.
