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
    throw 'APP_PRODUCTION_DEPLOYMENT_DISABLED: only the reviewed staging executor may apply Terraform.'
}
if (-not $ApplyReviewedPlan) {
    throw 'APP_DEPLOYMENT_DISABLED: staging execution requires the explicit -ApplyReviewedPlan activation.'
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$terraformRoot = Join-Path $repoRoot 'infra/environments/staging'
if (-not (Test-Path -LiteralPath $terraformRoot -PathType Container)) {
    throw "APP_TERRAFORM_ROOT_MISSING: reviewed staging Terraform root does not exist at '$terraformRoot'."
}
if (-not (Test-Path -LiteralPath $TerraformVariablesFile -PathType Leaf)) {
    throw 'APP_TERRAFORM_VARIABLES_MISSING: reviewed Terraform variables file does not exist.'
}

$plan = Join-Path ([System.IO.Path]::GetTempPath()) "oficina-app-staging-$SourceCommit.tfplan"
try {
    $terraformChdir = "-chdir=$terraformRoot"
    & terraform $terraformChdir init -input=false `
        "-backend-config=bucket=$TerraformBackendBucket" `
        "-backend-config=key=$TerraformBackendKey" `
        "-backend-config=region=$TerraformBackendRegion" `
        '-backend-config=use_lockfile=true'
    if ($LASTEXITCODE -ne 0) { throw 'APP_TERRAFORM_INIT_FAILED: Terraform init failed.' }

    & terraform $terraformChdir validate
    if ($LASTEXITCODE -ne 0) { throw 'APP_TERRAFORM_VALIDATE_FAILED: Terraform validate failed.' }

    & terraform $terraformChdir plan -input=false -lock-timeout=5m `
        "-var-file=$TerraformVariablesFile" "-out=$plan"
    if ($LASTEXITCODE -ne 0) { throw 'APP_TERRAFORM_PLAN_FAILED: apply was not attempted.' }

    & terraform $terraformChdir apply -input=false $plan
    if ($LASTEXITCODE -ne 0) { throw 'APP_TERRAFORM_APPLY_FAILED: reviewed Terraform plan failed.' }
    Write-Output 'Reviewed Terraform plan applied.'
}
finally {
    Remove-Item -LiteralPath $plan -Force -ErrorAction SilentlyContinue
}
