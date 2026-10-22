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

# Redaction regression (dummy values only): SQL literal echoed by psql must not survive Hide-Secret.
. (Join-Path $root 'scripts/phase4-kind.common.ps1')
$echoed = Hide-Secret "LINE 1: ALTER ROLE demo PASSWORD 'DUMMYsecret123'"
Assert-That 'Hide-Secret masks PASSWORD SQL literals' ($echoed -notmatch 'DUMMYsecret123')
Assert-That 'Hide-Secret masks key=value credentials' ((Hide-Secret 'PGPASSWORD=DUMMYsecret123 x') -notmatch 'DUMMYsecret123')
$threw = try { Invoke-Native pwsh @('-NoProfile', '-Command', "[Console]::In.ReadToEnd() | Out-Null; [Console]::Error.WriteLine(""near 'DUMMYsecret123'""); exit 3") -StdIn 'x'; '' } catch { $_.Exception.Message }
Assert-That 'Invoke-Native errors with stdin never echo quoted literals' (($threw -ne '') -and ($threw -notmatch 'DUMMYsecret123'))

if ($failures.Count) { Write-Host "contract FAILED: $($failures -join '; ')"; exit 1 }
Write-Host 'phase4-kind-contract=PASS'
