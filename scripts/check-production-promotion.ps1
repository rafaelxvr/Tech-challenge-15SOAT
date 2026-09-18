[CmdletBinding()]
param(
    [string]$Enabled = '',
    [string]$RoleArn = '',
    [string]$InputsFile = '',
    [string]$ExpectedInputsSha256 = '',
    [string]$SourceCommit = '',
    [string]$EventName = $env:GITHUB_EVENT_NAME,
    [string]$BranchRef = $env:GITHUB_REF,
    [switch]$PassThru
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Deliberately offline: this contract never invokes a launcher or obtains an
# identity. Valid review inputs cannot enable the production runtime adapter.
if ($Enabled -cne 'true') { throw 'APP_PRODUCTION_GATE_DISABLED' }
& (Join-Path $PSScriptRoot 'check-workflow-context.ps1') -Environment production -EventName $EventName -BranchRef $BranchRef | Out-Null
if ($SourceCommit -cnotmatch '\A[a-f0-9]{40}\z') { throw 'APP_PRODUCTION_SOURCE_INVALID' }

function Read-PinnedJson([string]$Path, [string]$Digest) {
    if ($Digest -cnotmatch '\A[a-f0-9]{64}\z' -or [string]::IsNullOrWhiteSpace($Path) -or
        -not (Test-Path -LiteralPath $Path -PathType Leaf) -or
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Digest) {
        throw 'APP_PRODUCTION_INPUT_HASH_MISMATCH'
    }
    $options = @{InputObject=(Get-Content -LiteralPath $Path -Raw); NoEnumerate=$true}
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) { $options.DateKind='String' }
    $value = ConvertFrom-Json @options
    if ($value -isnot [pscustomobject]) { throw 'APP_PRODUCTION_INPUT_OBJECT_REQUIRED' }
    return $value
}
function String-Field([object]$Object, [string]$Name) {
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $property.Value -isnot [string] -or
        [string]::IsNullOrWhiteSpace($property.Value) -or $property.Value -ceq 'null') {
        throw "APP_PRODUCTION_STRING_REQUIRED: $Name"
    }
    return $property.Value
}
function Object-Field([object]$Object, [string]$Name) {
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $property.Value -isnot [pscustomobject]) { throw "APP_PRODUCTION_OBJECT_REQUIRED: $Name" }
    return $property.Value
}
function Schema-One([object]$Object) {
    $property = $Object.PSObject.Properties['schemaVersion']
    if ($null -eq $property -or ($property.Value -isnot [int] -and $property.Value -isnot [long]) -or $property.Value -ne 1) {
        throw 'APP_PRODUCTION_SCHEMA_INVALID'
    }
}
function Pinned-File([object]$Object, [string]$Name) {
    $reference = Object-Field $Object $Name
    $path = String-Field $reference 'path'
    if ([IO.Path]::IsPathRooted($path)) { throw 'APP_PRODUCTION_RELATIVE_PATH_REQUIRED' }
    $resolved = [IO.Path]::GetFullPath((Join-Path $inputDirectory $path))
    if (-not $resolved.StartsWith($inputDirectory + [IO.Path]::DirectorySeparatorChar, [StringComparison]::Ordinal)) {
        throw 'APP_PRODUCTION_INPUT_PATH_ESCAPE'
    }
    $digest = String-Field $reference 'sha256'
    if ($digest -cnotmatch '\A[a-f0-9]{64}\z' -or -not (Test-Path -LiteralPath $resolved -PathType Leaf) -or
        (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant() -cne $digest) {
        throw "APP_PRODUCTION_FILE_HASH_MISMATCH: $Name"
    }
    return @{Path=$resolved; Sha256=$digest}
}

$inputs = Read-PinnedJson $InputsFile $ExpectedInputsSha256
$inputDirectory = Split-Path -Parent ([IO.Path]::GetFullPath($InputsFile))
Schema-One $inputs
$account = String-Field $inputs 'accountId'
if ($account -cnotmatch '\A[0-9]{12}\z' -or (String-Field $inputs 'environment') -cne 'production' -or
    (String-Field $inputs 'sourceCommit') -cne $SourceCommit -or
    (String-Field $inputs 'projectName') -cne 'oficina-phase3-oficina-app-production-deploy' -or
    (String-Field $inputs 'sourcePrefix') -cne 'releases/app/production') { throw 'APP_PRODUCTION_TARGET_MISMATCH' }
# The exact role is reviewed in both protected inputs, with production naming
# and account scoping checked locally. IAM trust is an external prerequisite.
if ($RoleArn -cnotmatch ('\Aarn:aws:iam::' + $account + ':role/[A-Za-z0-9+=,.@_/-]*production[A-Za-z0-9+=,.@_/-]*\z') -or
    (String-Field $inputs 'roleArn') -cne $RoleArn) { throw 'APP_PRODUCTION_ROLE_MISMATCH' }
$deployer = String-Field $inputs 'deployerImageDigest'
if ($deployer -cnotmatch '\Asha256:[a-f0-9]{64}\z') { throw 'APP_PRODUCTION_DEPLOYER_INVALID' }

$source = Pinned-File $inputs 'sourceArchive'
$releaseFile = Pinned-File $inputs 'releaseManifest'
$platformFile = Pinned-File $inputs 'platformInputs'
$tfvars = Pinned-File $inputs 'terraformVariables'
$window = Pinned-File $inputs 'cloudWindowEvidence'
$promotionFile = Pinned-File $inputs 'stagingPromotion'
$stagingReleaseFile = Pinned-File $inputs 'stagingReleaseManifest'
$release = Read-PinnedJson $releaseFile.Path $releaseFile.Sha256
$promotion = Read-PinnedJson $promotionFile.Path $promotionFile.Sha256
$stagingRelease = Read-PinnedJson $stagingReleaseFile.Path $stagingReleaseFile.Sha256
$platform = Read-PinnedJson $platformFile.Path $platformFile.Sha256
foreach ($document in @($release,$promotion,$stagingRelease)) { Schema-One $document }

$promotionReference = Object-Field $inputs 'stagingPromotion'
if ((String-Field $promotionReference 'bucket') -cnotmatch '\A[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]\z' -or
    (String-Field $promotionReference 'key') -cne "releases/app/staging/promotions/$SourceCommit.json") { throw 'APP_STAGING_RECEIPT_LOCATION_INVALID' }
$null = String-Field $promotionReference 'versionId'
foreach ($name in @('sourceVersionId','releaseManifestVersionId','terraformVariablesVersionId')) { $null = String-Field $promotion $name }
foreach ($name in @('artifactSha256','releaseManifestSha256','terraformVariablesSha256')) {
    if ((String-Field $promotion $name) -cnotmatch '\A[a-f0-9]{64}\z') { throw 'APP_STAGING_RECEIPT_DIGEST_INVALID' }
}
if ((String-Field $promotion 'environment') -cne 'staging' -or
    (String-Field $promotion 'sourceCommit') -cne $SourceCommit -or
    (String-Field $promotion 'buildStatus') -cne 'SUCCEEDED' -or
    (String-Field $promotion 'codeBuildProjectName') -cne 'oficina-phase3-oficina-app-staging-deploy' -or
    (String-Field $promotion 'codeBuildBuildId') -cnotmatch '\Aoficina-phase3-oficina-app-staging-deploy:[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\z' -or
    (String-Field $promotion 'sourceKey') -cne 'releases/app/staging/bundle.zip' -or
    (String-Field $promotion 'releaseManifestKey') -cne "releases/app/staging/manifests/$SourceCommit.json" -or
    (String-Field $promotion 'terraformVariablesKey') -cne "releases/app/staging/config/$SourceCommit.tfvars.json" -or
    (String-Field $promotion 'artifactSha256') -cne $source.Sha256 -or
    (String-Field $promotion 'releaseManifestSha256') -cne $stagingReleaseFile.Sha256 -or
    (String-Field $promotion 'deployerImageDigest') -cne $deployer) { throw 'APP_STAGING_PROMOTION_MISMATCH' }
$issued = [datetimeoffset]::Parse((String-Field $promotion 'issuedAtUtc'), [Globalization.CultureInfo]::InvariantCulture)
if ($issued.Offset -ne [timespan]::Zero -or $issued -gt [datetimeoffset]::UtcNow) { throw 'APP_STAGING_PROMOTION_TIME_INVALID' }

if ((String-Field $stagingRelease 'environment') -cne 'staging' -or
    (String-Field $stagingRelease 'sourceCommit') -cne $SourceCommit -or
    (String-Field $stagingRelease 'artifactSha256') -cne $source.Sha256 -or
    (String-Field $release 'environment') -cne 'production' -or
    (String-Field $release 'sourceCommit') -cne $SourceCommit -or
    (String-Field $release 'artifactSha256') -cne $source.Sha256 -or
    (String-Field $release 'stagingArtifactSha256') -cne $source.Sha256) { throw 'APP_PRODUCTION_SOURCE_NOT_PROMOTED' }
$promoted = $release.PSObject.Properties['promotedFromStaging']
if ($null -eq $promoted -or $promoted.Value -isnot [bool] -or -not $promoted.Value) { throw 'APP_PRODUCTION_PROMOTION_REQUIRED' }
foreach ($field in @('image','runtimeArtifactDigest','contractVersion','migrationVersion','databaseSchemaVersion')) {
    if ((String-Field $release $field) -cne (String-Field $stagingRelease $field)) { throw "APP_PRODUCTION_RUNTIME_NOT_PROMOTED: $field" }
}
$image = String-Field $release 'image'
if ($image -cnotmatch ('\A' + $account + '\.dkr\.ecr\.us-east-1\.amazonaws\.com/[a-z0-9][a-z0-9/_.-]*@(?<digest>sha256:[a-f0-9]{64})\z') -or
    (String-Field $release 'runtimeArtifactDigest') -cne $Matches.digest -or
    (String-Field $release 'deployerImageDigest') -cne $deployer -or
    (String-Field $release 'terraformVariablesSha256') -cne $tfvars.Sha256 -or
    (String-Field $release 'platformInputsSha256') -cne $platformFile.Sha256 -or
    (String-Field $release 'cloudWindowEvidenceSha256') -cne $window.Sha256 -or
    (String-Field $platform 'Environment') -cne 'production' -or
    (String-Field $platform 'Image') -cne $image) { throw 'APP_PRODUCTION_BINDING_MISMATCH' }
& (Join-Path $PSScriptRoot 'check-cloud-window.ps1') -EvidenceFile $window.Path -Environment production | Out-Null
if ($PassThru) {
    return [pscustomobject]@{Inputs=$inputs; Release=$release; Source=$source; ReleaseFile=$releaseFile;
        Platform=$platformFile; TerraformVariables=$tfvars; Window=$window; StagingPromotion=$promotionFile; StagingRelease=$stagingRelease}
}
Write-Output 'PRODUCTION_CONTRACT_VALIDATED_DEPLOYMENT_DISABLED'
