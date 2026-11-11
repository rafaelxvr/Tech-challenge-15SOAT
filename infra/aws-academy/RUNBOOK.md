# Runbook de sessão no Learner Lab

Executor: Rafael, ou SRE em sessão supervisionada, em foreground, saída filtrada. **Cada passo com ação AWS exige o card aprovado correspondente** (`COST.md`). Credencial nunca em issue/PR/log.

## Antes (preflight, card 1)

1. `Start Lab`; confirmar que `AWS_ACCESS_KEY_ID`/`AWS_PROFILE` do ambiente **não** apontam para outro perfil (abortar se apontarem). Usar sempre `--profile fiap-lab`.
2. Provar região/quota e compatibilidade de `LabRole` com EKS (control plane, default de `lab_eks_cluster_role_name`; confia em `eks.amazonaws.com`) e node group. `LabEksClusterRole` **não existe** na conta (achado da sessão 1); sem IAM novo. Se falhar: acionar o fallback (abaixo).
3. Checkout dos 3 repositórios (core, billing, execution) no `deliveryRef` do manifesto `infra/aws-academy/release-manifest.json` (`refs/tags/fase4-entrega-2026-11-13`, tag a ser criada pelo operador em cada repositório só depois da última promoção `develop` → `main`; os SHAs-alvo das tags são evidência externa em [UNR-1070](/UNR/issues/UNR-1070), nunca gravados no manifesto). O overlay de entrega vem do `deliveryRef`; o `sourceSha` do manifesto só prova a origem da imagem (tag da imagem, digest e `ciRun` são a proveniência imutável) e **não** é usado para obter o overlay. Depois: `bash infra/aws-academy/verify-offline.sh` e, no core, `bash k8s-overlays/aws/verify-overlay.sh --release --billing <billing>/k8s/overlays/aws --execution <execution>/k8s/overlays/aws`. O `--release` exige `deliveryRef` comum e válido no manifesto, exige que cada checkout informado esteja limpo e em `HEAD` igual ao commit da tag (somente leitura, sem rede e sem AWS; `--delivery-tag-pending` só para ensaio antes de a tag existir, nunca para o apply) e cruza **por serviço** (imagem + digest) o manifesto com os 3 overlays renderizados; sem `--billing`/`--execution` ele avisa e só valida o digest do manifesto (não usar para o apply). Os 3 pacotes GHCR (`oficina-mecanica`, `oficina-billing-service`, `oficina-execution-service`) são públicos (pull anônimo, sem `imagePullSecrets`); se algum voltar a privado, o pull falha no cluster.
4. **/32 do endpoint EKS (nunca ampliar para `0.0.0.0/0`):** `operator_cidr` no var-file privado = IP público atual do operador em `/32` (a validação recusa `0.0.0.0`, privado, loopback, CGNAT). Depois do `plan` e **antes** do `apply`, no plan JSON: `terraform show -json <privado>/tfplan | jq -r '.. | .public_access_cidrs? // empty'` deve ser exatamente `["<ip>/32"]` (e `endpoint_private_access == true`). Qualquer outro valor: abortar, não ajustar o CIDR para fazer o nó entrar.

## Apply (cards 3 e 4)

```bash
terraform -chdir=infra/aws-academy init -backend=false
terraform -chdir=infra/aws-academy plan  -var-file=<privado>.tfvars -state=<privado>/terraform.tfstate -out=<privado>/tfplan
terraform -chdir=infra/aws-academy apply <privado>/tfplan
```

Depois: Secret por stdin (S-11), aplicar os 3 serviços (`kubectl apply -k k8s-overlays/aws`, `kubectl apply -k <billing>/k8s/overlays/aws`, `kubectl apply -k <execution>/k8s/overlays/aws`) e esperar `kubectl rollout status` de cada Deployment (`kubectl get deploy -A` para os namespaces), migrations com o usuário master, usuários de runtime sem DDL, `kubectl port-forward svc/oficina-app 8080:8080` (127.0.0.1) para o os-service. Apagar `tfplan`.

### Verificações pós-apply (mesmo card, somente leitura)

0. **Credenciais dos pods = role do nó (sem IRSA):** o Learner Lab não tem OIDC. Billing roda com `SPRING_PROFILES_ACTIVE=aws` e execution com `EXECUCAO_AWS_MODO=NODE_ROLE` (código dos PRs billing #15 e execution #39; execution falha fechado se houver chave estática, `AWS_PROFILE`, web identity ou endpoint alterado; billing usa `DefaultCredentialsProvider` e só bloqueia endpoint/arquivo de credencial alternativo, então chave estática, perfil ou web identity no ambiente do pod poderiam vencer o IMDS: não definir nenhum deles no overlay). Depende do IMDSv2 alcançável pelo pod (`hop_limit=2` no launch template). Conferir pelos logs/readiness: se `kubectl -n <ns> logs deploy/<svc>` mostrar falha de credencial/IMDS (timeout ao `169.254.169.254`), parar e escalar; não criar chaves nem abrir `hostNetwork`.
0a. **Gate de código:** não aplicar antes de billing #15 e execution #39 estarem em `develop` e de as imagens terem sido reconstruídas. Depois, regenerar `release-manifest.json` (SHAs, digests e `ciRun` novos; `deliveryRef` permanece) e os overlays, e rodar `verify-overlay.sh --release --billing <abs> --execution <abs>`. O verify cruza imagem+digest, mas não prova que o `sourceSha` contém o perfil `aws`/`NODE_ROLE`.
1. **/32 efetivo** (consulta AWS só no card aprovado): `aws eks describe-cluster --profile fiap-lab --query 'cluster.resourcesVpcConfig.[publicAccessCidrs,endpointPrivateAccess]'` deve dar `["<ip>/32"]` e `true`. Nunca ampliar o CIDR; se os nós não registrarem, investigar (endpoint privado, SG), não abrir o público.
2. **Webhook do billing inacessível sem assinatura (teste direto no billing):** o billing ainda expõe a rota histórica `POST /billing/webhooks/mercadopago` (context-path `/billing` + `@PostMapping("/webhooks/mercadopago")`, `permitAll` protegida por HMAC), mas não há Ingress/LB: só o port-forward alcança o pod. O port-forward do `oficina-app` **não** prova nada sobre o billing; faça um port-forward próprio (namespace e Service do billing, porta 8080, em 127.0.0.1), em outra porta local:

   ```bash
   # terminal 1 (encerrar com Ctrl+C ao final):
   kubectl -n oficina-billing port-forward svc/oficina-billing 8081:8080
   # terminal 2:
   curl -s -o /dev/null -w '%{http_code}\n' -X POST 'http://127.0.0.1:8081/billing/webhooks/mercadopago?data.id=0&type=payment'
   ```

   Esperado: `401` (sem `x-signature`/`x-request-id`, ou com `MP_WEBHOOK_SECRET` vazio, `AssinaturaWebhook.valida` recusa sempre e o corpo nunca é processado). `404` aqui indicaria rota ou context-path errados (o teste não provou nada); `200`/`202` é violação: parar e escalar. Registrar só o código. O teste não é 404 porque a rota existe no código do billing; "sem webhook" significa sem entrada pública e sem segredo de assinatura configurado.

## Destroy (obrigatório; iniciar em T-60 min do timer; também se a sessão falhar)

**Duração:** o `terraform destroy` do EKS levou ~11 min na sessão 1 (node group, depois control plane, depois RDS). Rodar com margem (começar em T-60 min) e, se o foreground estourar o limite de 10 min da execução, fazer em 2 passadas: `terraform destroy -target=aws_eks_node_group.this` e depois o `destroy` completo, sempre com o mesmo state/var-file. Conferir com `terraform state list` antes do `-target`.

1. **Verificar a ausência** de Service `LoadBalancer` (somente leitura, sem `delete`): `kubectl get svc -A -o jsonpath='{range .items[?(@.spec.type=="LoadBalancer")]}{.metadata.namespace}/{.metadata.name}{"\n"}{end}'` deve sair vazio. Se listar algo, é violação do desenho: parar, registrar no card e escalar ao Rafael/SecurityEngineer antes de qualquer remoção (o `terraform destroy` não conhece NLB criado fora do state).
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
