[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot/runtime-public-fixture.ps1"
$temp = Join-Path ([IO.Path]::GetTempPath()) ('oficina-offline-handoff-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Reject([scriptblock]$Action) { try { & $Action | Out-Null } catch { return }; throw 'Expected handoff rejection.' }
function Save-Json($Value, [string]$Path) { $Value | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Path -NoNewline }
try {
    $sourceCommit = (& git -C $repo rev-parse HEAD).Trim()
    $prefix = '123456789012.dkr.ecr.us-east-1.amazonaws.com/'
    $platform = [ordered]@{ Environment='staging'; Image=($prefix + 'oficina@sha256:' + ('a'*64)); DbHost='private.example.test'; AppIrsaRoleArn='arn:aws:iam::123456789012:role/oficina-staging-app' }
    $platformPath = Join-Path $temp 'platform.json'; Save-Json $platform $platformPath
    $tfvarsPath = Join-Path $temp 'reviewed.tfvars.json'; Save-Json @{ environment='staging' } $tfvarsPath
    . "$repo/scripts/app-release-contract.ps1"
    $review = [ordered]@{schemaVersion=1;environment='staging';sourceCommit=$sourceCommit;databaseHost='private.example.test';caSha256=('f'*64);master=[ordered]@{arn='arn:aws:secretsmanager:us-east-1:123456789012:secret:rds!db-example';versionId=('1'*32)};roles=[ordered]@{migration=[ordered]@{arn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/migration-AbCdEf';versionId=('2'*32)};app=[ordered]@{arn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/app-AbCdEf';versionId=('3'*32)};auth=[ordered]@{arn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/auth-AbCdEf';versionId=('4'*32)};notification=[ordered]@{arn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/notification-AbCdEf';versionId=('5'*32)}}}
    $release = [ordered]@{
        schemaVersion=1; environment='staging'; mode='FirstWriter'; sourceCommit=$sourceCommit; contractVersion='phase3-v2'; databaseSchemaVersion='V8'
        platformInputsSha256=(Hash $platformPath); image=$platform.Image; previousImage=($prefix + 'oficina@sha256:' + ('b'*64)); migrationImage=($prefix + 'flyway@sha256:' + ('c'*64)); bootstrapImage=($prefix + 'bootstrap@sha256:' + ('f'*64)); bootstrapReview=$review
        kubeContext='arn:aws:eks:us-east-1:123456789012:cluster/oficina'; migrationSecretName='oficina-migration-staging'; migrationServiceAccount='oficina-migration-staging'; migrationSqlSha256=(Get-AppMigrationDigest)
        runtimeArtifactDigest=('sha256:' + ('d'*64)); deployerImageDigest=('sha256:' + ('e'*64)); terraformVariablesSha256=(Hash $tfvarsPath)
    }
    Add-RuntimePublicFixture $release "$temp/public.json"
    $releaseInputPath = Join-Path $temp 'release-input.json'; Save-Json $release $releaseInputPath
    $outOne = Join-Path $temp 'one'; $outTwo = Join-Path $temp 'two'
    $resultOne = & "$repo/scripts/offline-release-handoff.ps1" -SourceCommit $sourceCommit -ReleaseInputFile $releaseInputPath -PlatformInputsFile $platformPath -RuntimePublicConfigMapFile "$temp/public.json" -OutputDirectory $outOne
    $resultTwo = & "$repo/scripts/offline-release-handoff.ps1" -SourceCommit $sourceCommit -ReleaseInputFile $releaseInputPath -PlatformInputsFile $platformPath -RuntimePublicConfigMapFile "$temp/public.json" -OutputDirectory $outTwo
    Assert (($resultOne | Select-Object -Last 1) -ceq 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED') 'Handoff must emit the disabled status.'
    foreach ($name in @('source.zip','runtime-public.json','release-manifest.json','rendered/migration-job.json','rendered/rollout-patch.json','release-receipt.json')) { Assert ((Hash (Join-Path $outOne $name)) -ceq (Hash (Join-Path $outTwo $name))) "Output is not deterministic: $name" }
    $manifest = Get-Content -LiteralPath (Join-Path $outOne 'release-manifest.json') -Raw | ConvertFrom-Json
    $receipt = Get-Content -LiteralPath (Join-Path $outOne 'release-receipt.json') -Raw | ConvertFrom-Json
    Assert ($manifest.sourceCommit -ceq $sourceCommit -and $manifest.artifactSha256 -ceq (Hash (Join-Path $outOne 'source.zip'))) 'Manifest does not bind source archive.'
    Assert ($manifest.deployerImageDigest -ceq $release.deployerImageDigest) 'Packaging changed or dropped the reviewed deployer digest.'
    Assert ($manifest.terraformVariablesSha256 -ceq (Hash $tfvarsPath)) 'Packaging changed or dropped the reviewed Terraform variables digest.'
    Assert ($receipt.releaseManifestSha256 -ceq (Hash (Join-Path $outOne 'release-manifest.json'))) 'Handoff receipt must bind the manifest including executor and Terraform variables digests.'
    Assert ($receipt.status -ceq 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED' -and $receipt.success -eq $false -and $receipt.deploymentAttempted -eq $false) 'Receipt must be an explicit non-success disabled result.'
    Reject { & "$repo/scripts/offline-release-handoff.ps1" -SourceCommit $sourceCommit -ReleaseInputFile $releaseInputPath -PlatformInputsFile $platformPath -RuntimePublicConfigMapFile "$temp/public.json" -OutputDirectory $outOne }
    Write-Output 'PASS: deterministic offline APP handoff packages, verifies, renders and records disabled deployment.'
} finally {
    $resolved = [IO.Path]::GetFullPath($temp)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('oficina-offline-handoff-')) { throw 'Unsafe cleanup target.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
