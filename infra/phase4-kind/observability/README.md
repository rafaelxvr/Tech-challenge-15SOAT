# New Relic no Kind (perfil observável, opt-in)

Sem `-Observability` nada muda: os três serviços sobem como antes, sem licença.

```powershell
pwsh -NoProfile -File scripts/phase4-kind-stack.ps1 -Action Up -Observability
pwsh -NoProfile -File scripts/phase4-kind-stack.ps1 -Action Verify -Observability
```

- Arquivo local de credencial: `D:\unravel-private\fiap\newrelic.env` (ou `$env:PHASE4_NEWRELIC_ENV_FILE`, ou `-NewRelicEnvFile`). Fica **fora** do repositório. O script só **valida** o formato por regex (conta linhas; não guarda valor nem linha em variável) e o entrega ao kubectl.
- Formato (menor privilégio): o arquivo contém **somente** `NEW_RELIC_LICENSE_KEY=<valor>`, valor inteiro sem espaços, aspas nem `export`; comentários e linhas em branco são aceitos. Outras chaves são recusadas, pois o `--from-env-file` as copiaria para os Secrets dos três namespaces (o account ID fica fora do arquivo).
- A validação roda **antes** de buildar imagens ou criar o cluster. Falhas claras, sem eco do valor: arquivo ausente, chave ausente/duplicada, valor vazio, com aspas ou com espaços, e chaves além de `NEW_RELIC_LICENSE_KEY`.
- O Secret `newrelic-license` (chave `NEW_RELIC_LICENSE_KEY`) nasce de `kubectl create secret generic --from-env-file --dry-run=client -o yaml | kubectl apply --server-side -f -` nos namespaces `oficina`, `oficina-billing` e `oficina-execution` (sem `last-applied-configuration`; o Secret existente é apagado antes do apply, então um cluster reaproveitado com anotação antiga também fica limpo). O patch `newrelic-env-patch.json` injeta nos três Deployments: `NEW_RELIC_LICENSE_KEY` por `secretKeyRef`, `NEW_RELIC_APP_NAME` (`oficina-os-service`, `oficina-billing-service`, `oficina-execution-service`), `NEW_RELIC_AGENT_ENABLED=true` e `OBSERVABILITY_ENABLED=true`. Nenhum manifesto versionado contém o valor. O account ID (`8521907`, US) é variável não secreta dos repositórios, não do cluster.
- Falha no meio do wiring remove os Secrets `newrelic-license` criados.
- `Verify -Observability` confere, por serviço, secretKeyRef (nome e chave), presença da chave no Secret e ausência da anotação `last-applied-configuration` (sem imprimir valor), as duas flags e o app name.
- `Down` apaga o cluster e, com ele, os Secrets.

Limite: o patch só injeta variáveis; o agente Java precisa estar na imagem do serviço (o os-service tem; billing e execution dependem das próprias imagens nos SHAs pinados). Sem agente, a variável é inócua. Não foi executado em cluster nesta entrega (ver PR).
