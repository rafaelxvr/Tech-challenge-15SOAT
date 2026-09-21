[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('staging', 'production')]
    [string]$Environment,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$SourceZip,

    [Parameter(Mandatory)]
    [ValidatePattern('^[a-f0-9]{64}$')]
    [string]$ExpectedSha256,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$ReleaseManifest,

    [Parameter(Mandatory)]
    [ValidatePattern('^[a-f0-9]{64}$')]
    [string]$ExpectedManifestSha256,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$TerraformVariablesFile,

    [Parameter(Mandatory)]
    [ValidatePattern('\A[a-f0-9]{64}\z')]
    [string]$ExpectedTerraformVariablesSha256,

    [Parameter(Mandatory)]
    [ValidatePattern('\Asha256:[a-f0-9]{64}\z')]
    [string]$DeployerImageDigest,

    [Parameter(Mandatory)]
    [ValidatePattern('^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$')]
    [string]$Bucket,

    [Parameter(Mandatory)]
    [ValidatePattern('^[a-z0-9][a-z0-9/_-]*$')]
    [string]$SourcePrefix,

    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9_.-]+$')]
    [string]$ProjectName,

    [Parameter(Mandatory)]
    [ValidatePattern('^[a-f0-9]{40}$')]
    [string]$SourceCommit,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$CloudWindowEvidenceFile,

    [string]$PlatformInputsFile,
    [string]$StagingWorkloadFile,
    [string]$RuntimePublicConfigMapFile,

    [string]$PromotionEvidenceOutputFile,

    [string]$EventName = $env:GITHUB_EVENT_NAME,
    [string]$BranchRef = $env:GITHUB_REF,

    [ValidateRange(60, 2400)]
    [int]$TimeoutSeconds = 2100,

    [switch]$DryRun
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Fail([string]$Message) { throw "Deployment launch failed: $Message" }
function Get-Sha256([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }

if (-not (Test-Path -LiteralPath $SourceZip -PathType Leaf)) { Fail 'source zip does not exist.' }
if (-not (Test-Path -LiteralPath $ReleaseManifest -PathType Leaf)) { Fail 'release manifest does not exist.' }
if (-not (Test-Path -LiteralPath $TerraformVariablesFile -PathType Leaf)) { Fail 'Terraform variables file does not exist.' }
$expectedProjectName = "oficina-phase3-oficina-app-$Environment-deploy"
$expectedSourcePrefix = "releases/app/$Environment"
if ($ProjectName -cne $expectedProjectName) { Fail 'project name is not the reviewed APP executor for the requested environment.' }
if ($SourcePrefix -cne $expectedSourcePrefix) { Fail 'source prefix is not the reviewed APP prefix for the requested environment.' }

$actualSourceSha = Get-Sha256 $SourceZip
if ($actualSourceSha -cne $ExpectedSha256) { Fail 'source digest mismatch.' }
$actualManifestSha = Get-Sha256 $ReleaseManifest
if ($actualManifestSha -cne $ExpectedManifestSha256) { Fail 'release-manifest digest mismatch.' }
if ((Get-Sha256 $TerraformVariablesFile) -cne $ExpectedTerraformVariablesSha256) { Fail 'Terraform variables digest mismatch.' }
try { $tfvars = Get-Content -LiteralPath $TerraformVariablesFile -Raw | ConvertFrom-Json -NoEnumerate }
catch { Fail 'Terraform variables must be valid JSON.' }
if ($tfvars -isnot [pscustomobject]) { Fail 'Terraform variables must be a JSON object.' }
try { $manifest = Get-Content -LiteralPath $ReleaseManifest -Raw | ConvertFrom-Json }
catch { Fail 'release manifest is not valid JSON.' }
if ($manifest.PSObject.Properties['deployerImageDigest'] -eq $null -or
    $manifest.deployerImageDigest -cne $DeployerImageDigest) { Fail 'release manifest does not bind the reviewed deployer image digest.' }
if ($manifest.PSObject.Properties['terraformVariablesSha256'] -eq $null -or
    $manifest.terraformVariablesSha256 -cne $ExpectedTerraformVariablesSha256) { Fail 'release manifest does not bind the reviewed Terraform variables digest.' }

if ($manifest.schemaVersion -ne 1 -or $manifest.environment -cne $Environment -or $manifest.sourceCommit -cne $SourceCommit -or $manifest.artifactSha256 -cne $ExpectedSha256) {
    Fail 'release manifest does not bind the reviewed source/environment.'
}
if ($manifest.contractVersion -cne 'phase3-v2' -or [string]::IsNullOrWhiteSpace($manifest.migrationVersion) -or $manifest.runtimeArtifactDigest -cnotmatch '\Asha256:[a-f0-9]{64}\z') {
    Fail 'release must bind contract, migration and runtime artifact digest.'
}
if ($Environment -ceq 'production' -and ($manifest.promotedFromStaging -ne $true -or $manifest.stagingArtifactSha256 -cne $ExpectedSha256)) {
    Fail 'production requires the exact staging-tested source artifact.'
}

& (Join-Path $PSScriptRoot 'check-workflow-context.ps1') -Environment $Environment -EventName $EventName -BranchRef $BranchRef | Out-Null
& (Join-Path $PSScriptRoot 'check-cloud-window.ps1') -EvidenceFile $CloudWindowEvidenceFile -Environment $Environment | Out-Null
if ($Environment -ceq 'staging') {
    . (Join-Path $PSScriptRoot 'staging-executor-inputs.ps1')
    Assert-StagingExecutorInputs $manifest $PlatformInputsFile $StagingWorkloadFile $CloudWindowEvidenceFile $RuntimePublicConfigMapFile
}
if($Environment -cne 'staging' -and -not[string]::IsNullOrWhiteSpace($RuntimePublicConfigMapFile)){Fail 'staging public ConfigMap input is forbidden for production.'}
if ($DryRun) {
    Write-Output 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED'
    return
}
if ($Environment -cne 'staging') { Fail 'production deployment remains disabled.' }

# The object keys are reviewed protocol values. VersionIds returned by S3 are
# passed to CodeBuild so a later overwrite cannot change the source being run.
$sourceKey = "$SourcePrefix/bundle.zip"
$manifestKey = "$SourcePrefix/manifests/$SourceCommit.json"
$tfvarsKey = "$SourcePrefix/config/$SourceCommit.tfvars.json"
$sourceResult = & aws s3api put-object --bucket $Bucket --key $sourceKey --body $SourceZip --output json 2>$null
if ($LASTEXITCODE -ne 0) { Fail 'versioned source upload failed.' }
try { $sourceUpload = $sourceResult | ConvertFrom-Json } catch { Fail 'source upload returned invalid JSON.' }
if ([string]::IsNullOrWhiteSpace($sourceUpload.VersionId)) { Fail 'source upload returned no S3 VersionId.' }

$manifestResult = & aws s3api put-object --bucket $Bucket --key $manifestKey --body $ReleaseManifest --output json 2>$null
if ($LASTEXITCODE -ne 0) { Fail 'versioned release-manifest upload failed.' }
try { $manifestUpload = $manifestResult | ConvertFrom-Json } catch { Fail 'release-manifest upload returned invalid JSON.' }
if ([string]::IsNullOrWhiteSpace($manifestUpload.VersionId)) { Fail 'release-manifest upload returned no S3 VersionId.' }

$tfvarsResult = & aws s3api put-object --bucket $Bucket --key $tfvarsKey --body $TerraformVariablesFile --output json 2>$null
if ($LASTEXITCODE -ne 0) { Fail 'versioned Terraform variables upload failed.' }
try { $tfvarsUpload = $tfvarsResult | ConvertFrom-Json } catch { Fail 'Terraform variables upload returned invalid JSON.' }
if ($null -eq $tfvarsUpload -or $null -eq $tfvarsUpload.PSObject.Properties['VersionId'] -or
    [string]::IsNullOrWhiteSpace([string]$tfvarsUpload.VersionId) -or [string]$tfvarsUpload.VersionId -ceq 'null') {
    Fail 'Terraform variables upload returned no immutable S3 VersionId.'
}

# Keep this list closed. In particular, no secret, arbitrary command or
# operator-supplied override is forwarded to the platform-owned project.
$runtimeOverrides=@()
$publicConfigVersion=$null
foreach($entry in @(
    @{Name='PLATFORM_INPUTS';File=$PlatformInputsFile;Leaf='platform.json'},
    @{Name='STAGING_WORKLOAD';File=$StagingWorkloadFile;Leaf='workload.json'},
    @{Name='CLOUD_WINDOW';File=$CloudWindowEvidenceFile;Leaf='cloud-window.json'},
    @{Name='RUNTIME_PUBLIC_CONFIGMAP';File=$RuntimePublicConfigMapFile;Leaf='runtime-public.json'}
)) {
    $key="$SourcePrefix/inputs/$SourceCommit/$($entry.Leaf)"
    $response=& aws s3api put-object --bucket $Bucket --key $key --body $entry.File --output json 2>$null
    if($LASTEXITCODE -ne 0){Fail 'versioned public runtime input upload failed.'}
    try {$upload=ConvertFrom-Json -InputObject ($response -join "`n") -NoEnumerate} catch {Fail 'runtime input upload returned invalid JSON.'}
    if($upload -isnot [pscustomobject] -or $null -eq $upload.PSObject.Properties['VersionId'] -or
        $upload.VersionId -isnot [string] -or [string]::IsNullOrWhiteSpace($upload.VersionId) -or $upload.VersionId -ceq 'null') {Fail 'runtime input upload returned no immutable S3 VersionId.'}
    $runtimeOverrides += "name=$($entry.Name)_OBJECT_KEY,value=$key,type=PLAINTEXT"
    $runtimeOverrides += "name=$($entry.Name)_VERSION_ID,value=$($upload.VersionId),type=PLAINTEXT"
    if($entry.Name -ceq 'RUNTIME_PUBLIC_CONFIGMAP'){$publicConfigVersion=$upload.VersionId}
}
$runtimeOverrides += "name=RUNTIME_PUBLIC_CONFIGMAP_SHA256,value=$($manifest.runtimePublicConfigMapSha256),type=PLAINTEXT"
$overrides = @(
    "name=DEPLOY_ENVIRONMENT,value=$Environment,type=PLAINTEXT",
    "name=SOURCE_BUCKET,value=$Bucket,type=PLAINTEXT",
    "name=SOURCE_KEY,value=$sourceKey,type=PLAINTEXT",
    "name=SOURCE_VERSION_ID,value=$($sourceUpload.VersionId),type=PLAINTEXT",
    "name=EXPECTED_SHA256,value=$ExpectedSha256,type=PLAINTEXT",
    "name=RELEASE_MANIFEST_KEY,value=$manifestKey,type=PLAINTEXT",
    "name=RELEASE_MANIFEST_VERSION_ID,value=$($manifestUpload.VersionId),type=PLAINTEXT",
    "name=EXPECTED_MANIFEST_SHA256,value=$ExpectedManifestSha256,type=PLAINTEXT",
    "name=SOURCE_COMMIT,value=$SourceCommit,type=PLAINTEXT",
    "name=DEPLOYER_IMAGE_DIGEST,value=$DeployerImageDigest,type=PLAINTEXT",
    "name=TFVARS_OBJECT_KEY,value=$tfvarsKey,type=PLAINTEXT",
    "name=TFVARS_VERSION_ID,value=$($tfvarsUpload.VersionId),type=PLAINTEXT",
    "name=EXPECTED_TFVARS_SHA256,value=$ExpectedTerraformVariablesSha256,type=PLAINTEXT"
)
$overrides += $runtimeOverrides
$started = & aws codebuild start-build --project-name $ProjectName --source-version $sourceUpload.VersionId --environment-variables-override $overrides --output json 2>$null
if ($LASTEXITCODE -ne 0) { Fail 'CodeBuild launch failed.' }
try { $build = $started | ConvertFrom-Json } catch { Fail 'CodeBuild launch returned invalid JSON.' }
$buildId = [string]$build.build.id
if ([string]::IsNullOrWhiteSpace($buildId)) { Fail 'CodeBuild launch returned no build ID.' }

$deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Seconds 10
    $current = & aws codebuild batch-get-builds --ids $buildId --output json 2>$null
    if ($LASTEXITCODE -ne 0) { Fail 'unable to retrieve CodeBuild status.' }
    try { $currentBuild = ($current | ConvertFrom-Json).builds[0] } catch { Fail 'CodeBuild status returned invalid JSON.' }
    if ($null -eq $currentBuild -or [string]::IsNullOrWhiteSpace([string]$currentBuild.buildStatus)) { Fail 'CodeBuild status returned no build.' }
    $status = [string]$currentBuild.buildStatus
    if ($status -ceq 'SUCCEEDED') {
        if ($Environment -ceq 'staging') {
            $promotionKey = "$SourcePrefix/promotions/$SourceCommit.json"
            $promotionPath = Join-Path ([System.IO.Path]::GetTempPath()) "oficina-app-promotion-$SourceCommit.json"
            try {
                [ordered]@{
                    schemaVersion = 1
                    environment = 'staging'
                    sourceCommit = $SourceCommit
                    artifactSha256 = $ExpectedSha256
                    sourceKey = $sourceKey
                    sourceVersionId = [string]$sourceUpload.VersionId
                    releaseManifestKey = $manifestKey
                    releaseManifestVersionId = [string]$manifestUpload.VersionId
                    releaseManifestSha256 = $ExpectedManifestSha256
                    terraformVariablesKey = $tfvarsKey
                    terraformVariablesVersionId = [string]$tfvarsUpload.VersionId
                    terraformVariablesSha256 = $ExpectedTerraformVariablesSha256
                    deployerImageDigest = $DeployerImageDigest
                    runtimePublicConfigMapKey = "$SourcePrefix/inputs/$SourceCommit/runtime-public.json"
                    runtimePublicConfigMapVersionId = $publicConfigVersion
                    runtimePublicConfigMapSha256 = $manifest.runtimePublicConfigMapSha256
                    codeBuildProjectName = $ProjectName
                    codeBuildBuildId = $buildId
                    buildStatus = 'SUCCEEDED'
                    issuedAtUtc = [datetime]::UtcNow.ToString('o')
                } | ConvertTo-Json | Set-Content -LiteralPath $promotionPath -NoNewline
                $promotionSha = Get-Sha256 $promotionPath
                $promotionResult = & aws s3api put-object --bucket $Bucket --key $promotionKey --body $promotionPath --output json 2>$null
                if ($LASTEXITCODE -ne 0) { Fail 'successful staging build could not publish promotion evidence.' }
                try { $promotionUpload = $promotionResult | ConvertFrom-Json } catch { Fail 'promotion evidence upload returned invalid JSON.' }
                if ([string]::IsNullOrWhiteSpace($promotionUpload.VersionId)) { Fail 'promotion evidence upload returned no S3 VersionId.' }
                if (-not [string]::IsNullOrWhiteSpace($PromotionEvidenceOutputFile)) {
                    [ordered]@{
                        schemaVersion = 1
                        environment = 'staging'
                        bucket = $Bucket
                        key = $promotionKey
                        versionId = [string]$promotionUpload.VersionId
                        sha256 = $promotionSha
                        sourceKey = $sourceKey
                        sourceVersionId = [string]$sourceUpload.VersionId
                        releaseManifestKey = $manifestKey
                        releaseManifestVersionId = [string]$manifestUpload.VersionId
                        codeBuildBuildId = $buildId
                        artifactSha256 = $ExpectedSha256
                        releaseManifestSha256 = $ExpectedManifestSha256
                        terraformVariablesKey = $tfvarsKey
                        terraformVariablesVersionId = [string]$tfvarsUpload.VersionId
                        terraformVariablesSha256 = $ExpectedTerraformVariablesSha256
                        deployerImageDigest = $DeployerImageDigest
                        runtimePublicConfigMapKey = "$SourcePrefix/inputs/$SourceCommit/runtime-public.json"
                        runtimePublicConfigMapVersionId = $publicConfigVersion
                        runtimePublicConfigMapSha256 = $manifest.runtimePublicConfigMapSha256
                    } | ConvertTo-Json | Set-Content -LiteralPath $PromotionEvidenceOutputFile -NoNewline
                }
            }
            finally { Remove-Item -LiteralPath $promotionPath -Force -ErrorAction SilentlyContinue }
        }
        Write-Output "Deployment build completed successfully: $buildId"
        exit 0
    }
    if ($status -in @('FAILED', 'FAULT', 'STOPPED', 'TIMED_OUT')) { Fail "CodeBuild finished with status '$status'." }
} while ([datetime]::UtcNow -lt $deadline)

Fail 'CodeBuild did not reach a terminal status before its approved timeout.'
