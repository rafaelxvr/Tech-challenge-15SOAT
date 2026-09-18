[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('staging','production')][string]$Environment,
    [Parameter(Mandatory)][string]$ReleaseManifest,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedSourceSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedManifestSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{40}$')][string]$SourceCommit,
    [Parameter(Mandatory)][ValidatePattern('^sha256:[a-f0-9]{64}$')][string]$ExpectedDeployerImageDigest,
    [Parameter(Mandatory)][string]$TerraformVariablesFile,
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$')][string]$TerraformBackendBucket,
    [Parameter(Mandatory)][string]$TerraformBackendKey,
    [Parameter(Mandatory)][string]$TerraformBackendLockKey,
    [Parameter(Mandatory)][string]$TerraformBackendRegion,
    [string]$SourceArchiveFile,
    [string]$PlatformInputsFile,
    [string]$StagingWorkloadFile,
    [string]$CloudWindowEvidenceFile,
    [string]$StateBucket,
    [string]$SourceKey,
    [switch]$ApplyReviewedPlan,
    [switch]$DryRun
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Require-ScalarString([object]$Object, [string]$Name) {
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $property.Value -isnot [string] -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
        throw "Manifest field '$Name' must be a non-empty scalar string."
    }
    return [string]$property.Value
}

# The platform executor owns these exact paths for the APP repository. Artifact
# uploads use releases/app/<environment>; source and Terraform state identities
# are separate contracts. Keep the existing production namespace unchanged.
$expectedBackendKey = "app/$Environment.tfstate"
$expectedTerraformVariablesFile = "/tmp/oficina/app_${Environment}.tfvars.json"
if ($TerraformBackendRegion -cne 'us-east-1' -or $TerraformBackendKey -cne $expectedBackendKey -or $TerraformBackendLockKey -cne "$expectedBackendKey.tflock" -or $TerraformVariablesFile -cne $expectedTerraformVariablesFile) { throw 'Unreviewed application state, lock, region or trusted tfvars path.' }
if ((Get-FileHash -LiteralPath $ReleaseManifest -Algorithm SHA256).Hash.ToLowerInvariant() -cne $ExpectedManifestSha256) { throw 'Manifest digest mismatch.' }
$manifest = Get-Content -LiteralPath $ReleaseManifest -Raw | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1 -or
    (Require-ScalarString $manifest 'environment') -cne $Environment -or
    (Require-ScalarString $manifest 'sourceCommit') -cne $SourceCommit -or
    (Require-ScalarString $manifest 'artifactSha256') -cne $ExpectedSourceSha256 -or
    (Require-ScalarString $manifest 'deployerImageDigest') -cne $ExpectedDeployerImageDigest) {
    throw 'Manifest does not bind the reviewed executor/source/environment.'
}

# The launcher and the platform-owned CodeBuild bootstrap provide the reviewed
# cloud-window evidence before this script is reached. Keep the dry-run path
# side-effect free and use the existing reviewed apply switch as the explicit
# staging activation. Production has no apply path by design.
if ($DryRun) {
    Write-Output 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED'
    return
}
if ($Environment -cne 'staging') {
    throw 'APP_PRODUCTION_DEPLOYMENT_DISABLED: only the reviewed staging FirstWriter adapter is executable.'
}
if (-not $ApplyReviewedPlan) {
    throw 'APP_DEPLOYMENT_DISABLED: staging execution requires the explicit -ApplyReviewedPlan activation.'
}

$entrypoint = Join-Path $PSScriptRoot 'deploy-app.ps1'
if (-not (Test-Path -LiteralPath $entrypoint -PathType Leaf)) { throw 'APP_ROLLOUT_ENTRYPOINT_MISSING: scripts/deploy-app.ps1 is required.' }
. (Join-Path $PSScriptRoot 'staging-executor-inputs.ps1')
Assert-StagingExecutorInputs $manifest $PlatformInputsFile $StagingWorkloadFile $CloudWindowEvidenceFile
if ($StateBucket -cne $TerraformBackendBucket -or $SourceKey -cne 'releases/app/staging/bundle.zip' -or
    [string]::IsNullOrWhiteSpace($SourceArchiveFile) -or -not (Test-Path -LiteralPath $SourceArchiveFile -PathType Leaf) -or
    (Get-FileHash -LiteralPath $SourceArchiveFile -Algorithm SHA256).Hash.ToLowerInvariant() -cne $ExpectedSourceSha256) {
    throw 'APP_STAGING_INPUTS_INVALID: source archive, source key or shared lock bucket mismatch.'
}
$tfvarsSha=Require-ScalarString $manifest 'terraformVariablesSha256'
if ($tfvarsSha -cnotmatch '\A[a-f0-9]{64}\z' -or -not (Test-Path -LiteralPath $TerraformVariablesFile -PathType Leaf) -or
    (Get-FileHash -LiteralPath $TerraformVariablesFile -Algorithm SHA256).Hash.ToLowerInvariant() -cne $tfvarsSha) {
    throw 'APP_STAGING_INPUTS_INVALID: reviewed executor configuration digest mismatch.'
}
& (Join-Path $PSScriptRoot 'check-cloud-window.ps1') -EvidenceFile $CloudWindowEvidenceFile -Environment staging | Out-Null
$inputs=@{
    ReleaseFile=$ReleaseManifest; ExpectedReleaseSha256=$ExpectedManifestSha256
    PlatformInputsFile=$PlatformInputsFile; StagingWorkloadFile=$StagingWorkloadFile
    CloudWindowEvidenceFile=$CloudWindowEvidenceFile; StateBucket=$StateBucket
    SourceArchiveFile=$SourceArchiveFile; SourceKey=$SourceKey; ExpectedDeployerImageDigest=$ExpectedDeployerImageDigest
    OutputDirectory=(Join-Path (Split-Path -Parent ([IO.Path]::GetFullPath($ReleaseManifest))) 'app-rollout')
}
# Invoke the real I6 entry point. Its preflight verifies the complete release and
# workload before its executing path acquires the shared lock and touches EKS.
& $entrypoint @inputs | Out-Null
& $entrypoint @inputs -ExecuteReviewedPlan
