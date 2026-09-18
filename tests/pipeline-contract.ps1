[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'deployment-lock-race-contract.ps1')
& (Join-Path $PSScriptRoot 'app-rollout-contract.ps1')
& (Join-Path $PSScriptRoot 'staging-rollout-contract.ps1')
$repo=Split-Path -Parent $PSScriptRoot
function aws { throw 'Offline tests forbid AWS.' }
function Reject([scriptblock]$Action) { try { & $Action | Out-Null } catch { return }; throw 'Expected invalid output rejection.' }
& "$PSScriptRoot/source-package-contract.ps1"
& "$PSScriptRoot/offline-release-handoff-contract.ps1"
& "$PSScriptRoot/workflow-context-contract.ps1"
& "$PSScriptRoot/cloud-window-tests.ps1"
& "$PSScriptRoot/release-guards-contract.ps1"
& "$PSScriptRoot/staging-deploy-workflow-contract.ps1"
& "$PSScriptRoot/staging-activation-contract.ps1"
# Isolate the mock AWS/sleep commands from this script's no-cloud guard.
& pwsh -NoLogo -NoProfile -NonInteractive -File "$PSScriptRoot/start-deploy-contract.ps1"
if ($LASTEXITCODE -ne 0) { throw 'Offline APP launcher contract failed.' }
$workflow=Get-Content -LiteralPath "$repo/.github/workflows/ci-cd.yml" -Raw
foreach($required in @('branches: [main, develop]','contents: read','cancel-in-progress: false','./mvnw -B verify','./tests/pipeline-contract.ps1','local-kind-smoke:')) {
    if(-not $workflow.Contains($required)) { throw "Missing APP workflow contract: $required" }
}
if($workflow -match 'id-token: write|configure-aws-credentials|AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY|pull_request_target|refs/heads/master') { throw 'APP cloud activation or legacy branch escape is not approved.' }
$temp=Join-Path ([IO.Path]::GetTempPath()) ('oficina-app-output-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    $values=@{schema_version='V8'; auth_view_version='V5'; recipient_view_version='V7'; app_secret_arn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/app-AbCdEf'; migration_secret_arn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/migration-AbCdEf'; auth_lookup_secret_arn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/auth-AbCdEf'; notification_lookup_secret_arn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/notification-AbCdEf'}
    $raw=@{}; foreach($key in $values.Keys) {$raw[$key]=@{sensitive=$false; value=$values[$key]}}
    $raw.password=@{sensitive=$true; value='must-not-export'}
    $inputPath="$temp/outputs.json"; $outputPath="$temp/filtered.json"
    $raw | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $inputPath
    $parameters=@{TerraformOutputFile=$inputPath; Environment='staging'; SourceCommit=('a'*40); OutputFile=$outputPath}
    & "$repo/scripts/export-outputs.ps1" @parameters | Out-Null
    if((Get-Content -LiteralPath $outputPath -Raw).Contains('must-not-export')) { throw 'Credential value escaped allowlist.' }
    $raw.app_secret_arn.sensitive=$true; $raw | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $inputPath
    Reject { & "$repo/scripts/export-outputs.ps1" @parameters }
    $raw.app_secret_arn.sensitive=$false
    foreach($invalid in @($values.migration_secret_arn,$values.app_secret_arn.Replace('/staging/','/production/'),'plaintext-password')) {
        $raw.app_secret_arn.value=$invalid; $raw | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $inputPath
        Reject { & "$repo/scripts/export-outputs.ps1" @parameters }
    }
    Write-Output 'PASS: APP pipeline/output contracts; cloud deployment remains disabled.'
}
finally {
    $resolved=[IO.Path]::GetFullPath($temp)
    if(-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('oficina-app-output-')) { throw 'Unsafe cleanup target.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
