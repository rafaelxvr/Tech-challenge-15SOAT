# New Relic no Kind (perfil observável, opt-in)

Sem `-Observability` nada muda: os três serviços sobem como antes, sem licença.

```powershell
pwsh -NoProfile -File scripts/phase4-kind-stack.ps1 -Action Up -Observability
pwsh -NoProfile -File scripts/phase4-kind-stack.ps1 -Action Verify -Observability
```

- Arquivo local de credencial: `D:\unravel-private\fiap\newrelic.env` (ou `$env:PHASE4_NEWRELIC_ENV_FILE`, ou `-NewRelicEnvFile`). Fica **fora** do repositório; o script só o lê em memória.
- Formato esperado: uma linha `NEW_RELIC_LICENSE_KEY=<valor>` (aspas e prefixo `export` aceitos). Outras chaves (inclusive account ID) **não** são lidas nem repassadas.
- A validação roda **antes** de buildar imagens ou criar o cluster. Falhas claras, sem eco do valor:
  - `arquivo de credencial New Relic ausente: <caminho>`
  - `NEW_RELIC_LICENSE_KEY ausente em <caminho>`
  - `NEW_RELIC_LICENSE_KEY vazia em <caminho>`
- O script cria o Secret `newrelic-license` (chave `license_key`) nos namespaces `oficina`, `oficina-billing` e `oficina-execution` via stdin (nunca argumento de processo) e aplica `newrelic-env-patch.json` nos três Deployments: `NEW_RELIC_LICENSE_KEY` por `secretKeyRef`, `NEW_RELIC_APP_NAME` e `NEW_RELIC_AGENT_ENABLED=true`. Nenhum manifesto versionado contém o valor.
- `Verify -Observability` confere que cada Deployment referencia o Secret por `secretKeyRef`.
- `Down` apaga o cluster e, com ele, os Secrets.

Limite: o patch só injeta variáveis; o agente Java precisa estar na imagem do serviço (o os-service tem; billing e execution dependem das próprias imagens nos SHAs pinados). Sem agente, a variável é inócua. Não foi executado em cluster nesta entrega (ver PR).
