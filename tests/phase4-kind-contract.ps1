<#
.SYNOPSIS
  Static contract for the Phase 4 local Kind infrastructure (no cluster needed): images pinned by digest,
  no AWS endpoints/profiles/OIDC, no host port exposure, no committed secrets, local-only endpoints.
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$kind = Join-Path $root 'infra/phase4-kind'
$scripts = Get-ChildItem (Join-Path $root 'scripts') -Filter '*phase4*.ps1'
$files = @(Get-ChildItem $kind -Recurse -File) + @($scripts)
$failures = New-Object System.Collections.Generic.List[string]
function Assert-That { param([string]$Name, [bool]$Ok) if ($Ok) { Write-Host "PASS $Name" } else { Write-Host "FAIL $Name"; $failures.Add($Name) } }

$manifests = Get-ChildItem (Join-Path $kind 'manifests') -Filter '*.yaml'
$images = $manifests | ForEach-Object { Select-String -Path $_.FullName -Pattern '^\s*image:\s*(\S+)' } | ForEach-Object { $_.Matches[0].Groups[1].Value }
Assert-That 'manifests declare at least one image' ($images.Count -ge 3)
Assert-That 'every manifest image is pinned by sha256 digest' (@($images | Where-Object { $_ -notmatch '@sha256:[0-9a-f]{64}$' }).Count -eq 0)
$flyway = Select-String -Path (Join-Path $root 'scripts/verify-phase4-db.ps1') -Pattern 'FlywayImage = ''(\S+)''' | ForEach-Object { $_.Matches[0].Groups[1].Value }
Assert-That 'flyway job image is pinned by sha256 digest' ($flyway -match '@sha256:[0-9a-f]{64}$')

# The only allowed AWS_PROFILE mention is the fail-closed env cleanup list in common.ps1 (the 'foreach ($name in ...' line).
$hits = { param($pattern) @($files | Where-Object { $_.Name -ne 'phase4-kind-contract.ps1' } | Select-String -Pattern $pattern -CaseSensitive:$false | Where-Object { -not ($_.Path -like '*phase4-kind.common.ps1' -and $_.Line -match '^foreach \(\$name in ''AWS_PROFILE''') }) }
Assert-That 'no amazonaws.com endpoint anywhere' ((& $hits 'amazonaws\.com').Count -eq 0)
Assert-That 'no AWS profile/OIDC/login usage' ((& $hits '--profile|AWS_PROFILE|aws\s+login|sts:AssumeRole|configure-aws-credentials').Count -eq 0)
Assert-That 'no remote registry (ECR) reference' ((& $hits '\.ecr\.').Count -eq 0)
Assert-That 'extraPortMappings pattern catches an indented key' ('  extraPortMappings:' -match '^\s*extraPortMappings')
Assert-That 'kind config has no extraPortMappings (no host exposure)' ((Select-String -Path (Join-Path $kind 'kind-config.yaml') -Pattern '^\s*extraPortMappings').Count -eq 0)
Assert-That 'cluster name is oficina-phase4' ((Select-String -Path (Join-Path $kind 'kind-config.yaml') -Pattern '^name:\s*oficina-phase4\s*$').Count -eq 1)
$pf = Select-String -Path (Join-Path $root 'scripts/phase4-kind.ps1') -Pattern 'port-forward'
Assert-That 'port-forward is always bound to 127.0.0.1' ((@($pf).Count -ge 1) -and (@(Select-String -Path (Join-Path $root 'scripts/phase4-kind.ps1') -Pattern "'--address', '127\.0\.0\.1'").Count -ge 1))
Assert-That 'no wildcard port-forward address' ((& $hits '--address[ ,'']+0\.0\.0\.0').Count -eq 0)
Assert-That 'manifests hold no literal passwords (Secrets are created at runtime)' (@($manifests | Select-String -Pattern '(?i)POSTGRES_PASSWORD' -Context 0, 2 | Where-Object { ($_.Line + ($_.Context.PostContext -join ' ')) -notmatch 'secretKeyRef' }).Count -eq 0)
Assert-That 'LocalStack uses dummy test/test credentials only' ((Select-String -Path (Join-Path $kind 'manifests/localstack.yaml') -Pattern 'AWS_ACCESS_KEY_ID, value: test').Count -eq 1)
Assert-That 'init/verify scripts target only localhost:4566 endpoints' (@(Select-String -Path (Join-Path $kind 'localstack/*.sh') -Pattern 'AWS_ENDPOINT_URL=(\S+)' | Where-Object { $_.Matches[0].Groups[1].Value -ne 'http://localhost:4566' }).Count -eq 0)
Assert-That 'scripts clear inherited AWS profile variables (fail closed)' ((Select-String -Path (Join-Path $root 'scripts/phase4-kind.common.ps1') -Pattern 'AWS_PROFILE').Count -ge 1)
Assert-That 'postgres manifests force scram on socket and loopback (no trust)' (@($manifests | Where-Object Name -like 'postgres-*' | Where-Object { -not (Select-String -Path $_.FullName -Pattern 'auth-local=scram-sha-256 --auth-host=scram-sha-256' -Quiet) }).Count -eq 0)
Assert-That 'secrets are never passed as --from-literal arguments' ((& $hits '--from-literal').Count -eq 0)
Assert-That 'passwords are never passed via PGPASSWORD= process arguments' ((& $hits '"PGPASSWORD=\$').Count -eq 0)

# Bring-up único dos 3 serviços (UNR-272): SHAs pinados, imagem local, sem registry remoto, cluster parametrizável.
$stack = Get-Content -Raw (Join-Path $root 'scripts/phase4-kind-stack.ps1')
Assert-That 'stack pins os/billing/execution SHAs' (($stack -match "OsSha = 'cf34717'") -and ($stack -match "BillingSha = '96c811a'") -and ($stack -match "ExecutionSha = '1bf1e6c'"))
Assert-That 'stack loads local images with kind load (no registry push/pull)' (($stack -match "'load', 'docker-image'") -and ($stack -notmatch "docker'?\s*@?\(?'push'"))
Assert-That 'cluster name is parameterizable via PHASE4_KIND_CLUSTER' ((Select-String -Path (Join-Path $root 'scripts/phase4-kind.common.ps1') -Pattern 'PHASE4_KIND_CLUSTER').Count -ge 1)
$osDir = Join-Path $kind 'os-service'
Assert-That 'os-service overlay: local placeholder image, Service port 8080, dummy AWS credentials' ((Select-String -Path (Join-Path $osDir 'deployment.yaml') -Pattern 'image: oficina-os-service:local' -Quiet) -and (Select-String -Path (Join-Path $osDir 'service.yaml') -Pattern 'port: 8080' -Quiet) -and (Select-String -Path (Join-Path $osDir 'configmap.yaml') -Pattern 'AWS_ACCESS_KEY_ID: test' -Quiet))
Assert-That 'os-service overlay: LocalStack endpoints only' (@(Select-String -Path (Join-Path $osDir 'configmap.yaml') -Pattern 'https?://(\S+)' | Where-Object { $_.Matches[0].Groups[1].Value -notmatch '^localstack\.infra\.svc\.cluster\.local:4566' }).Count -eq 0)
Assert-That 'os-service overlay holds no literal secret values' (@(Select-String -Path (Join-Path $osDir '*.yaml') -Pattern '(?i)^\s*(JWT_SECRET|DB_PASSWORD)\s*:' ).Count -eq 0)

# Redaction regression (dummy values only): SQL literal echoed by psql must not survive Hide-Secret.
. (Join-Path $root 'scripts/phase4-kind.common.ps1')
$echoed = Hide-Secret "LINE 1: ALTER ROLE demo PASSWORD 'DUMMYsecret123'"
Assert-That 'Hide-Secret masks PASSWORD SQL literals' ($echoed -notmatch 'DUMMYsecret123')
Assert-That 'Hide-Secret masks key=value credentials' ((Hide-Secret 'PGPASSWORD=DUMMYsecret123 x') -notmatch 'DUMMYsecret123')
$threw = try { Invoke-Native pwsh @('-NoProfile', '-Command', "[Console]::Error.WriteLine(""near '"" + [Console]::In.ReadToEnd().Trim() + ""'""); exit 3") -StdIn 'DUMMYsecret123'; '' } catch { $_.Exception.Message }
Assert-That 'Invoke-Native errors with stdin never echo quoted literals' (($threw -ne '') -and ($threw -notmatch 'DUMMYsecret123'))
$threwJson = try { Invoke-Native pwsh @('-NoProfile', '-Command', "[Console]::Error.WriteLine([Console]::In.ReadToEnd()); exit 3") -StdIn '{"stringData":{"POSTGRES_PASSWORD":"DUMMYjson456"}}'; '' } catch { $_.Exception.Message }
Assert-That 'Invoke-Native errors with stdin never echo a JSON manifest value' (($threwJson -ne '') -and ($threwJson -notmatch 'DUMMYjson456'))

# New Relic no Kind (UNR-617): secretKeyRef, sem valor versionado, falha clara sem eco do segredo.
$nrPatch = Get-Content -Raw (Join-Path $kind 'observability/newrelic-env-patch.json')
Assert-That 'newrelic patch: license key only via secretKeyRef to newrelic-license/NEW_RELIC_LICENSE_KEY' (($nrPatch -match '"name": "NEW_RELIC_LICENSE_KEY", "valueFrom": \{ "secretKeyRef": \{ "name": "newrelic-license", "key": "NEW_RELIC_LICENSE_KEY"') -and ($nrPatch -notmatch '"name": "NEW_RELIC_LICENSE_KEY", "value"'))
Assert-That 'newrelic patch sets OBSERVABILITY_ENABLED=true and NEW_RELIC_AGENT_ENABLED=true' (($nrPatch -match '"name": "OBSERVABILITY_ENABLED", "value": "true"') -and ($nrPatch -match '"name": "NEW_RELIC_AGENT_ENABLED", "value": "true"'))
Assert-That 'newrelic patch holds no account id nor literal key' (($nrPatch -notmatch '(?i)ACCOUNT_ID') -and ($nrPatch -notmatch '[0-9A-Fa-f]{32,}'))
Assert-That 'newrelic app names are the exact service names (no oficina-phase4-*)' (($stack -match "os = 'oficina-os-service'; billing = 'oficina-billing-service'; execution = 'oficina-execution-service'") -and ($stack -notmatch 'oficina-phase4-\$'))
$common = Get-Content -Raw (Join-Path $PSScriptRoot '../scripts/phase4-kind.common.ps1')
Assert-That 'secret comes from kubectl --from-env-file applied server-side, no PowerShell variable holds the key' (($common -match '--from-env-file=') -and ($common -match 'apply --server-side') -and ($common -notmatch 'Get-NewRelicLicenseKey') -and ($stack -notmatch '\$licenseKey'))
$upAt = $stack.IndexOf('function Invoke-Up')
Assert-That 'stack validates the newrelic file before any cluster/build work' (($stack -match '\[switch\]\$Observability') -and ($stack.IndexOf('Assert-NewRelicEnvFile $NewRelicEnvFile') -gt $upAt) -and ($stack.IndexOf('Assert-NewRelicEnvFile $NewRelicEnvFile') -lt $stack.IndexOf('Invoke-Build', $upAt)))
Assert-That 'newrelic hygiene: try/catch/finally in Enable-NewRelic and Verify checks secret name+key+flags' (($stack -match 'function Enable-NewRelic[\s\S]*?catch[\s\S]*?finally') -and ($stack -match 'OBSERVABILITY_ENABLED') -and ($stack -match 'go-template'))
$nrTmp = Join-Path ([IO.Path]::GetTempPath()) "p4-nr-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $nrTmp | Out-Null
try {
    $msg = { param($path) try { Assert-NewRelicEnvFile $path; '' } catch { $_.Exception.Message } }
    $m = & $msg (Join-Path $nrTmp 'ausente.env')
    Assert-That 'newrelic: missing file fails clearly with path' ($m -match 'ausente' -and $m -match [regex]::Escape($nrTmp))
    Set-Content (Join-Path $nrTmp 'semchave.env') -Value 'OUTRA=DUMMYother789'
    $m = & $msg (Join-Path $nrTmp 'semchave.env')
    Assert-That 'newrelic: missing key fails clearly without echoing file content' ($m -match 'NEW_RELIC_LICENSE_KEY ausente' -and $m -notmatch 'DUMMYother789')
    Set-Content (Join-Path $nrTmp 'vazia.env') -Value 'NEW_RELIC_LICENSE_KEY='
    Assert-That 'newrelic: empty value fails clearly' ((& $msg (Join-Path $nrTmp 'vazia.env')) -match 'vazia')
    Set-Content (Join-Path $nrTmp 'aspas.env') -Value 'NEW_RELIC_LICENSE_KEY="DUMMYquoted456"'
    $m = & $msg (Join-Path $nrTmp 'aspas.env')
    Assert-That 'newrelic: quoted value rejected without echo' (($m -match 'aspas') -and ($m -notmatch 'DUMMYquoted456'))
    Set-Content (Join-Path $nrTmp 'ok.env') -Value @('NEW_RELIC_ACCOUNT_ID=999', 'NEW_RELIC_LICENSE_KEY=DUMMYlicense123')
    Assert-That 'newrelic: valid file passes and returns nothing' ($null -eq (Assert-NewRelicEnvFile (Join-Path $nrTmp 'ok.env')))
    Assert-That 'Hide-Secret masks license key assignments' ((Hide-Secret 'NEW_RELIC_LICENSE_KEY=DUMMYlicense123') -notmatch 'DUMMYlicense123')
}
finally { Remove-Item $nrTmp -Recurse -Force -ErrorAction SilentlyContinue }

if ($failures.Count) { Write-Host "contract FAILED: $($failures -join '; ')"; exit 1 }
Write-Host 'phase4-kind-contract=PASS'
