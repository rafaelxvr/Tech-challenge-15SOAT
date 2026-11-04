# Runbook de sessão no Learner Lab

Executor: Rafael, ou SRE em sessão supervisionada, em foreground, saída filtrada. **Cada passo com ação AWS exige o card aprovado correspondente** (`COST.md`). Credencial nunca em issue/PR/log.

## Antes (preflight, card 1)

1. `Start Lab`; confirmar que `AWS_ACCESS_KEY_ID`/`AWS_PROFILE` do ambiente **não** apontam para outro perfil (abortar se apontarem). Usar sempre `--profile fiap-lab`.
2. Provar região/quota e compatibilidade de `LabEksClusterRole`/`LabRole` com EKS e node group. Se falhar: acionar o fallback (abaixo).
3. `bash infra/aws-academy/verify-offline.sh` e `bash k8s-overlays/aws/verify-overlay.sh --release` no SHA que será aplicado.
4. **/32 do endpoint EKS (nunca ampliar para `0.0.0.0/0`):** `operator_cidr` no var-file privado = IP público atual do operador em `/32` (a validação recusa `0.0.0.0`, privado, loopback, CGNAT). Depois do `plan` e **antes** do `apply`, no plan JSON: `terraform show -json <privado>/tfplan | jq -r '.. | .public_access_cidrs? // empty'` deve ser exatamente `["<ip>/32"]` (e `endpoint_private_access == true`). Qualquer outro valor: abortar, não ajustar o CIDR para fazer o nó entrar.

## Apply (cards 3 e 4)

```bash
terraform -chdir=infra/aws-academy init -backend=false
terraform -chdir=infra/aws-academy plan  -var-file=<privado>.tfvars -state=<privado>/terraform.tfstate -out=<privado>/tfplan
terraform -chdir=infra/aws-academy apply <privado>/tfplan
```

Depois: Secret por stdin (S-11), `kubectl apply -k k8s-overlays/aws`, migrations com o usuário master, usuários de runtime sem DDL, `kubectl port-forward svc/oficina-app 8080:8080` (127.0.0.1). Apagar `tfplan`.

### Verificações pós-apply (mesmo card, somente leitura)

1. **/32 efetivo** (consulta AWS só no card aprovado): `aws eks describe-cluster --profile fiap-lab --query 'cluster.resourcesVpcConfig.[publicAccessCidrs,endpointPrivateAccess]'` deve dar `["<ip>/32"]` e `true`. Nunca ampliar o CIDR; se os nós não registrarem, investigar (endpoint privado, SG), não abrir o público.
2. **Webhook inexistente (teste negativo 404):** o billing usa polling, não há webhook público. Com `kubectl port-forward` ativo: `curl -s -o /dev/null -w '%{http_code}
' -X POST http://127.0.0.1:8080/<path>` deve imprimir `404` para cada path candidato de webhook (`/webhooks/mercadopago`, `/api/webhooks/mercadopago`, `/billing/webhook`); registrar só os códigos.

## Destroy (obrigatório; iniciar em T-60 min do timer; também se a sessão falhar)

1. **Verificar a ausência** de Service `LoadBalancer` (somente leitura, sem `delete`): `kubectl get svc -A -o jsonpath='{range .items[?(@.spec.type=="LoadBalancer")]}{.metadata.namespace}/{.metadata.name}{"
"}{end}'` deve sair vazio. Se listar algo, é violação do desenho: parar, registrar no card e escalar ao Rafael/SecurityEngineer antes de qualquer remoção (o `terraform destroy` não conhece NLB criado fora do state).
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
5. Destroy: `terraform destroy` (a EC2, o SG e os dados saem juntos) + inventário. Verificar a ausência de Service `LoadBalancer` como no destroy do EKS (somente leitura).
