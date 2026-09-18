[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$temp = Join-Path ([IO.Path]::GetTempPath()) ('oficina-staging-handoff-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
$script:checks = 0
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message }; $script:checks++ }
function Reject([scriptblock]$Action) { $rejected=$false; try { & $Action | Out-Null } catch { $rejected=$true }; Assert $rejected 'Expected handoff rejection.' }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Write-Inputs {
    $platform = [ordered]@{
        Environment='staging'; Image=('123456789012.dkr.ecr.us-east-1.amazonaws.com/oficina-app@sha256:' + ('a'*64)); AppIrsaRoleArn='arn:aws:iam::123456789012:role/oficina-app-staging'; DeployerPrincipalArn='arn:aws:iam::123456789012:role/oficina-deployer-staging'; PlatformBindingPrincipalArn='arn:aws:iam::123456789012:role/oficina-platform-staging'; DbHost='db.oficina.internal'; DbCidr='10.20.0.0/24'; AlbSubnetCidrOne='10.42.0.0/24'; AlbSubnetCidrTwo='10.42.1.0/24'; AppSecretArn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/app-AbCdEf'; AuthorizerTrustSecretArn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/authorizer-trust-AbCdEf'; NewRelicIngestSecretArn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/newrelic-ingest-AbCdEf'; NewRelicAccountId='8521907'
    }
    $outputs=[ordered]@{schemaVersion='V8';authViewVersion='V5';recipientViewVersion='V7';migrationSecretArn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/migration-AbCdEf';migrationSecretVersionId=('b'*32);appSecretArn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/app-XyZ123';appSecretVersionId=('c'*32);authLookupSecretArn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/auth-QrS456';authLookupSecretVersionId=('d'*32);notificationLookupSecretArn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/notification-TuV789';notificationLookupSecretVersionId=('e'*32)}
    $database=[ordered]@{schemaVersion=2;environment='staging';sourceCommit=('f'*40);outputs=$outputs}
    $functions=[ordered]@{api_id='qcm8l43flb';execution_arn='arn:aws:execute-api:us-east-1:123456789012:qcm8l43flb';authorizer_id='aBc123';environment='staging'}
    $platform | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath "$temp/platform.json" -NoNewline
    $database | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath "$temp/database.json" -NoNewline
    $functions | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath "$temp/functions.json" -NoNewline
    $script:platform=$platform; $script:database=$database; $script:functions=$functions
}
function Invoke-Check {
    $params=@{PlatformInputsFile="$temp/platform.json"; ExpectedPlatformSha256=(Hash "$temp/platform.json"); DatabaseReceiptFile="$temp/database.json"; ExpectedDatabaseReceiptSha256=(Hash "$temp/database.json"); FunctionsHandoffFile="$temp/functions.json"; ExpectedFunctionsHandoffSha256=(Hash "$temp/functions.json"); SourceCommit=('f'*40); OutputFile="$temp/validated.json"}
    & "$repo/scripts/validate-staging-handoff.ps1" @params | Out-Null
}
try {
    Write-Inputs; Invoke-Check
    $validated=Get-Content -LiteralPath "$temp/validated.json" -Raw | ConvertFrom-Json
    Assert ($validated.environment -ceq 'staging' -and $validated.databaseSchemaVersion -ceq 'V8' -and $validated.gateway.apiId -ceq 'qcm8l43flb') 'Validated metadata is incomplete.'
    Assert ($validated.databaseSecretReferences.migration -match '^arn:aws:secretsmanager:') 'Validated metadata must contain references only.'
    foreach ($mutation in @(
        {$script:functions.execution_arn='arn:aws:execute-api:us-east-1:999999999999:qcm8l43flb'},
        {$script:functions.environment='production'},
        {$script:database.outputs.schemaVersion='V4'},
        {$script:database.outputs.appSecretVersionId='AWSCURRENT'},
        {$script:platform.Image='oficina-app:latest'},
        {$script:platform.Environment='production'}
    )) {
        Write-Inputs; & $mutation; $script:functions | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath "$temp/functions.json" -NoNewline; $script:database | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath "$temp/database.json" -NoNewline; $script:platform | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath "$temp/platform.json" -NoNewline
        Remove-Item -LiteralPath "$temp/validated.json" -Force -ErrorAction SilentlyContinue
        Reject { Invoke-Check }; Assert (-not (Test-Path -LiteralPath "$temp/validated.json")) 'Rejected handoff must not produce metadata.'
    }
    Write-Output "PASS: $script:checks staging handoff assertions; no AWS, Terraform or kubectl calls."
} finally {
    $resolved=[IO.Path]::GetFullPath($temp)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('oficina-staging-handoff-')) { throw 'Unsafe cleanup target.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
