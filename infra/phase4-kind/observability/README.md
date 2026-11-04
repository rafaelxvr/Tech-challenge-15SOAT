# New Relic no Kind (perfil observável, opt-in)

Sem `-Observability` nada muda: os três serviços sobem como antes, sem licença.

```powershell
pwsh -NoProfile -File scripts/phase4-kind-stack.ps1 -Action Up -Observability
pwsh -NoProfile -File scripts/phase4-kind-stack.ps1 -Action Verify -Observability
```

- Arquivo local de credencial: `D:\unravel-private\fiap\newrelic.env` (ou `$env:PHASE4_NEWRELIC_ENV_FILE`, ou `-NewRelicEnvFile`). Fica **fora** do repositório. O script só **valida** o formato por regex (nunca captura o valor em variável) e o entrega ao kubectl.
- Formato: linha `NEW_RELIC_LICENSE_KEY=<valor>` sem aspas nem `export` (o `--from-env-file` do kubectl não os remove).
- A validação roda **antes** de buildar imagens ou criar o cluster. Falhas claras, sem eco do valor: arquivo ausente, chave ausente/duplicada, valor vazio ou com aspas.
- O Secret `newrelic-license` (chave `NEW_RELIC_LICENSE_KEY`) nasce de `kubectl create secret generic --from-env-file --dry-run=client -o yaml | kubectl apply --server-side -f -` nos namespaces `oficina`, `oficina-billing` e `oficina-execution` (sem `last-applied-configuration`). O patch `newrelic-env-patch.json` injeta nos três Deployments: `NEW_RELIC_LICENSE_KEY` por `secretKeyRef`, `NEW_RELIC_APP_NAME` (`oficina-os-service`, `oficina-billing-service`, `oficina-execution-service`), `NEW_RELIC_AGENT_ENABLED=true` e `OBSERVABILITY_ENABLED=true`. Nenhum manifesto versionado contém o valor. O account ID (`8521907`, US) é variável não secreta dos repositórios, não do cluster.
- Falha no meio do wiring remove os Secrets `newrelic-license` criados.
- `Verify -Observability` confere, por serviço, secretKeyRef (nome e chave), presença da chave no Secret (sem imprimir valor), as duas flags e o app name.
- `Down` apaga o cluster e, com ele, os Secrets.

Limite: o patch só injeta variáveis; o agente Java precisa estar na imagem do serviço (o os-service tem; billing e execution dependem das próprias imagens nos SHAs pinados). Sem agente, a variável é inócua. Não foi executado em cluster nesta entrega (ver PR).
