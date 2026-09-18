[CmdletBinding()]
param([string]$MetadataFile)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$contractPath = Join-Path $repo 'docs/phase-3/app-staging-activation-contract.md'
if (-not (Test-Path -LiteralPath $contractPath -PathType Leaf)) { throw 'APP staging activation specification is missing.' }

# This is an offline review aid, not a production preflight or an apply switch.
# Its input contains allowlisted control-plane metadata, never credentials.
function Assert-ExecutorMetadata($Metadata) {
    $fields = @('schemaVersion','recordedAtUtc','projectName','sourceLocation','configuredDeployerImage','reviewedDeployerDigest','ecrRepositoryUri','ecrImageDigests')
    if ($Metadata -isnot [pscustomobject] -or
        (@($Metadata.PSObject.Properties.Name | Sort-Object) -join ',') -cne (@($fields | Sort-Object) -join ',')) { throw 'INVALID_METADATA_FIELDS' }
    if ($Metadata.schemaVersion -isnot [int] -and $Metadata.schemaVersion -isnot [long]) { throw 'INVALID_METADATA_TYPES' }
    foreach ($field in @('recordedAtUtc','projectName','sourceLocation','configuredDeployerImage','reviewedDeployerDigest','ecrRepositoryUri')) {
        if ($Metadata.$field -isnot [string] -or [string]::IsNullOrWhiteSpace($Metadata.$field)) { throw 'INVALID_METADATA_TYPES' }
    }
    if ($Metadata.ecrImageDigests -isnot [array] -or
        @($Metadata.ecrImageDigests | Where-Object { $_ -isnot [string] -or $_ -cnotmatch '\Asha256:[a-f0-9]{64}\z' }).Count -ne 0) {
        throw 'INVALID_ECR_DIGEST_LIST'
    }
    if ($Metadata.schemaVersion -ne 1) { throw 'INVALID_METADATA_FIELDS' }
    try { $recorded = [datetimeoffset]::Parse($Metadata.recordedAtUtc, [Globalization.CultureInfo]::InvariantCulture) }
    catch { throw 'INVALID_METADATA_TIMESTAMP' }
    if ($recorded.Offset -ne [timespan]::Zero -or $recorded -gt [datetimeoffset]::UtcNow) { throw 'INVALID_METADATA_TIMESTAMP' }
    if ($Metadata.projectName -cne 'oficina-phase3-oficina-app-staging-deploy') { throw 'PROJECT_MISMATCH' }
    if ($Metadata.sourceLocation -cne 'oficina-phase3-artifacts-16225b7358/releases/app/staging/bundle.zip') { throw 'SOURCE_LOCATION_MISMATCH' }
    $repository = '638612472889.dkr.ecr.us-east-1.amazonaws.com/oficina-phase3-deployer'
    if ($Metadata.ecrRepositoryUri -cne $repository) { throw 'DEPLOYER_REPOSITORY_MISMATCH' }
    if ($Metadata.configuredDeployerImage -cnotmatch ('\A' + [regex]::Escape($repository) + '@(?<digest>sha256:[a-f0-9]{64})\z')) {
        throw 'DEPLOYER_IMAGE_NOT_PINNED'
    }
    $configuredDigest = $Matches.digest
    if ($Metadata.reviewedDeployerDigest -cnotmatch '\Asha256:[a-f0-9]{64}\z' -or
        $Metadata.reviewedDeployerDigest -cne $configuredDigest) { throw 'DEPLOYER_DIGEST_MISMATCH' }
    if ($Metadata.ecrImageDigests -cnotcontains $configuredDigest) { throw 'DEPLOYER_IMAGE_NOT_FOUND' }
    return 'METADATA_VALIDATED_DEPLOYMENT_DISABLED'
}

function Reject-Metadata($Metadata, [string]$Expected) {
    try { Assert-ExecutorMetadata $Metadata | Out-Null }
    catch {
        if ($_.Exception.Message -ceq $Expected) { return }
        throw "Expected '$Expected', received '$($_.Exception.Message)'."
    }
    throw "Expected metadata rejection: $Expected"
}

function Read-MetadataJson([string]$Json) {
    $options = @{InputObject=$Json; NoEnumerate=$true}
    # PowerShell 7.5+ otherwise turns ISO date strings into DateTime values.
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) { $options.DateKind = 'String' }
    return ,(ConvertFrom-Json @options)
}

$fixture = [pscustomobject]@{
    schemaVersion=1; recordedAtUtc='2026-01-01T00:00:00Z'
    projectName='oficina-phase3-oficina-app-staging-deploy'
    sourceLocation='oficina-phase3-artifacts-16225b7358/releases/app/staging/bundle.zip'
    configuredDeployerImage=('638612472889.dkr.ecr.us-east-1.amazonaws.com/oficina-phase3-deployer@sha256:' + ('a'*64))
    reviewedDeployerDigest=('sha256:' + ('a'*64))
    ecrRepositoryUri='638612472889.dkr.ecr.us-east-1.amazonaws.com/oficina-phase3-deployer'
    ecrImageDigests=@(('sha256:' + ('a'*64)))
}
if ((Assert-ExecutorMetadata $fixture) -cne 'METADATA_VALIDATED_DEPLOYMENT_DISABLED') { throw 'Metadata validation must not claim deployment readiness.' }
foreach ($case in @(
    @{Field='sourceLocation'; Value='oficina-phase3-artifacts-16225b7358/releases/application/staging/bundle.zip'; Error='SOURCE_LOCATION_MISMATCH'},
    @{Field='sourceLocation'; Value='oficina-phase3-artifacts-16225b7358/releases/app/production/bundle.zip'; Error='SOURCE_LOCATION_MISMATCH'},
    @{Field='sourceLocation'; Value='another-bucket/releases/app/staging/bundle.zip'; Error='SOURCE_LOCATION_MISMATCH'},
    @{Field='projectName'; Value='oficina-phase3-oficina-app-production-deploy'; Error='PROJECT_MISMATCH'},
    @{Field='configuredDeployerImage'; Value='638612472889.dkr.ecr.us-east-1.amazonaws.com/oficina-phase3-deployer:latest'; Error='DEPLOYER_IMAGE_NOT_PINNED'},
    @{Field='reviewedDeployerDigest'; Value=('sha256:' + ('b'*64)); Error='DEPLOYER_DIGEST_MISMATCH'},
    @{Field='ecrImageDigests'; Value=@(('sha256:' + ('b'*64))); Error='DEPLOYER_IMAGE_NOT_FOUND'},
    @{Field='ecrImageDigests'; Value=@(); Error='DEPLOYER_IMAGE_NOT_FOUND'},
    @{Field='ecrImageDigests'; Value=@('latest'); Error='INVALID_ECR_DIGEST_LIST'},
    @{Field='ecrRepositoryUri'; Value='638612472889.dkr.ecr.us-east-1.amazonaws.com/oficina-phase3-app'; Error='DEPLOYER_REPOSITORY_MISMATCH'},
    @{Field='recordedAtUtc'; Value='invalid'; Error='INVALID_METADATA_TIMESTAMP'}
)) {
    $bad = $fixture.PSObject.Copy()
    $bad.($case.Field) = $case.Value
    Reject-Metadata $bad $case.Error
}
$extra = $fixture.PSObject.Copy()
$extra | Add-Member -NotePropertyName unexpectedField -NotePropertyValue 'not-allowlisted'
Reject-Metadata $extra 'INVALID_METADATA_FIELDS'

# Exercise JSON types, including arrays that PowerShell comparison operators
# would otherwise filter/coerce instead of comparing as scalar strings.
$fixtureJson = $fixture | ConvertTo-Json -Depth 5 -Compress
if ((Assert-ExecutorMetadata (Read-MetadataJson $fixtureJson)) -cne 'METADATA_VALIDATED_DEPLOYMENT_DISABLED') {
    throw 'Valid JSON metadata must remain a disabled result.'
}
foreach ($field in @('projectName','sourceLocation','configuredDeployerImage','reviewedDeployerDigest','ecrRepositoryUri','recordedAtUtc')) {
    foreach ($case in @(@{Value=@()}, @{Value=@($fixture.$field)}, @{Value=$null}, @{Value=1}, @{Value=$true}, @{Value=''})) {
        $bad = $fixture.PSObject.Copy()
        $bad.$field = $case.Value
        Reject-Metadata (Read-MetadataJson ($bad | ConvertTo-Json -Depth 5 -Compress)) 'INVALID_METADATA_TYPES'
    }
}
foreach ($field in $fixture.PSObject.Properties.Name) {
    $bad = $fixture.PSObject.Copy()
    $bad.PSObject.Properties.Remove($field)
    Reject-Metadata (Read-MetadataJson ($bad | ConvertTo-Json -Depth 5 -Compress)) 'INVALID_METADATA_FIELDS'
}
foreach ($case in @(@{Value=@()}, @{Value=@(1)}, @{Value=$null}, @{Value='1'}, @{Value=$true}, @{Value=1.5})) {
    $bad = $fixture.PSObject.Copy()
    $bad.schemaVersion = $case.Value
    Reject-Metadata (Read-MetadataJson ($bad | ConvertTo-Json -Depth 5 -Compress)) 'INVALID_METADATA_TYPES'
}
foreach ($json in @('[]', ('[' + $fixtureJson + ']'), 'null')) {
    Reject-Metadata (Read-MetadataJson $json) 'INVALID_METADATA_FIELDS'
}
foreach ($case in @(@{Value=$null}, @{Value=('sha256:' + ('a'*64))}, @{Value=@($null)})) {
    $bad = $fixture.PSObject.Copy()
    $bad.ecrImageDigests = $case.Value
    Reject-Metadata (Read-MetadataJson ($bad | ConvertTo-Json -Depth 5 -Compress)) 'INVALID_ECR_DIGEST_LIST'
}

# A removed gate or renamed variable must invalidate the documented contract.
$contract = Get-Content -LiteralPath $contractPath -Raw
$workflow = Get-Content -LiteralPath (Join-Path $repo '.github/workflows/staging-deploy.yml') -Raw
$variables = @('APP_CLOUD_DEPLOYMENT_ENABLED','APP_CLOUD_DEPLOYMENT_ROLE_ARN','APP_RELEASE_INPUT_PATH','APP_PLATFORM_INPUTS_PATH','APP_CLOUD_WINDOW_EVIDENCE_PATH','APP_TERRAFORM_VARIABLES_PATH','APP_TERRAFORM_VARIABLES_SHA256','APP_DEPLOYER_IMAGE_DIGEST')
foreach ($name in $variables) {
    if (-not $contract.Contains($name) -or -not $workflow.Contains('vars.' + $name)) { throw "Activation variable differs from specification/workflow: $name" }
}
if (-not $workflow.Contains('APP_SOURCE_PREFIX: releases/app/staging')) { throw 'Workflow source prefix differs from activation specification.' }

if ($MetadataFile) {
    $metadata = Read-MetadataJson (Get-Content -LiteralPath $MetadataFile -Raw)
    Assert-ExecutorMetadata $metadata
} else {
    Write-Output 'PASS: staging activation metadata rejects absent/mismatched deployer images and wrong source locations; metadata validation never activates deployment.'
}
