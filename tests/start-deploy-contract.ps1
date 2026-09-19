[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot/runtime-public-fixture.ps1"
$temp = Join-Path ([IO.Path]::GetTempPath()) ('oficina-app-launcher-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
$launcherScripts = Join-Path $temp 'launcher/scripts'
New-Item -ItemType Directory -Path $launcherScripts -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repo 'scripts/start-deploy.ps1') -Destination (Join-Path $launcherScripts 'start-deploy.ps1')
Copy-Item -LiteralPath (Join-Path $repo 'scripts/staging-executor-inputs.ps1') -Destination (Join-Path $launcherScripts 'staging-executor-inputs.ps1')
Copy-Item -LiteralPath "$repo/scripts/runtime-public-configmap-contract.ps1" -Destination "$launcherScripts/runtime-public-configmap-contract.ps1"
$contextMarker = Join-Path $temp 'context-check.txt'
$windowMarker = Join-Path $temp 'window-check.txt'
@"
param([string]`$Environment, [string]`$EventName, [string]`$BranchRef)
Set-Content -LiteralPath '$contextMarker' -Value "`$Environment|`$EventName|`$BranchRef" -NoNewline
"@ | Set-Content -LiteralPath (Join-Path $launcherScripts 'check-workflow-context.ps1') -NoNewline
@"
param([string]`$EvidenceFile, [string]`$Environment)
Set-Content -LiteralPath '$windowMarker' -Value "`$Environment|`$EvidenceFile" -NoNewline
"@ | Set-Content -LiteralPath (Join-Path $launcherScripts 'check-cloud-window.ps1') -NoNewline

function Save-Json([object]$Value, [string]$Path) {
    $Value | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Path -NoNewline
}
function Sha([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Reject([scriptblock]$Action) {
    try { & $Action | Out-Null } catch { return }
    throw 'Expected launcher input rejection.'
}

$global:AwsCalls = [System.Collections.Generic.List[string]]::new()
$global:TfvarsUploadResponse = '{"VersionId":"tfvars-version-001"}'
$global:RuntimeUploadResponse = '{"VersionId":"runtime-version-001"}'
function global:Start-Sleep { param([int]$Seconds) }
function global:aws {
    param([Parameter(Position = 0, ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $global:LASTEXITCODE = 0
    $joined = $Arguments -join ' '
    $global:AwsCalls.Add($joined)
    if ($Arguments.Count -ge 3 -and $Arguments[0] -ceq 's3api' -and $Arguments[1] -ceq 'put-object') {
        $keyIndex = [Array]::IndexOf($Arguments, '--key')
        $key = if ($keyIndex -ge 0) { $Arguments[$keyIndex + 1] } else { '' }
        if ($key -match '/config/') { return $global:TfvarsUploadResponse }
        if ($key -match '/inputs/') { return $global:RuntimeUploadResponse }
        $version = if ($key -match '/promotions/') { 'promotion-version-001' } elseif ($key -match '/manifests/') { 'manifest-version-001' } else { 'source-version-001' }
        return (@{ VersionId = $version } | ConvertTo-Json -Compress)
    }
    if ($Arguments.Count -ge 2 -and $Arguments[0] -ceq 'codebuild' -and $Arguments[1] -ceq 'start-build') {
        return (@{ build = @{ id = 'oficina-phase3-oficina-app-staging-deploy:build-001' } } | ConvertTo-Json -Compress)
    }
    if ($Arguments.Count -ge 2 -and $Arguments[0] -ceq 'codebuild' -and $Arguments[1] -ceq 'batch-get-builds') {
        return (@{ builds = @(@{ buildStatus = 'SUCCEEDED' }) } | ConvertTo-Json -Compress)
    }
    throw "Unexpected AWS command: $joined"
}

try {
    $source = Join-Path $temp 'source.zip'
    'reviewed source bytes' | Set-Content -LiteralPath $source -NoNewline
    $sourceSha = Sha $source
    $commit = 'a' * 40
    $tfvarsPath = Join-Path $temp 'reviewed.tfvars.json'
    Save-Json @{ environment = 'staging' } $tfvarsPath
    $tfvarsSha = Sha $tfvarsPath
    $deployerDigest = 'sha256:' + ('c' * 64)
    $manifestPath = Join-Path $temp 'release-manifest.json'
    $manifest = @{ schemaVersion = 1; environment = 'staging'; sourceCommit = $commit; artifactSha256 = $sourceSha; contractVersion = 'phase3-v2'; migrationVersion = 'V8'; runtimeArtifactDigest = ('sha256:' + ('b' * 64)); promotedFromStaging = $false; deployerImageDigest = $deployerDigest; terraformVariablesSha256 = $tfvarsSha }
    Save-Json $manifest $manifestPath
    $manifestSha = Sha $manifestPath
    $windowPath = Join-Path $temp 'cloud-window.json'
    $now = [datetime]::UtcNow
    Save-Json @{ windowStartUtc = $now.AddMinutes(-2).ToString('o'); windowEndUtc = $now.AddMinutes(30).ToString('o'); recordedAtUtc = $now.ToString('o'); accountEvidenceReference = 'offline-fixture'; projectAllowanceUsd = 80; reserveUsd = 20; currentEstimatedSpendUsd = 0 } $windowPath
    $platformPath=Join-Path $temp 'platform.json'; Save-Json @{Environment='staging'} $platformPath
    $workloadPath=Join-Path $temp 'workload.json'; Save-Json @{kind='List'} $workloadPath
    $manifest.mode='FirstWriter'; $manifest.platformInputsSha256=Sha $platformPath; $manifest.stagingWorkloadSha256=Sha $workloadPath; $manifest.cloudWindowEvidenceSha256=Sha $windowPath
    Add-RuntimePublicFixture $manifest "$temp/public.json"
    Save-Json $manifest $manifestPath; $manifestSha=Sha $manifestPath
    $receiptPath = Join-Path $temp 'promotion-receipt.json'
    $launch = @{
        Environment = 'staging'; SourceZip = $source; ExpectedSha256 = $sourceSha; ReleaseManifest = $manifestPath; ExpectedManifestSha256 = $manifestSha
        TerraformVariablesFile = $tfvarsPath; ExpectedTerraformVariablesSha256 = $tfvarsSha; DeployerImageDigest = $deployerDigest
        Bucket = 'oficina-phase3-artifacts-16225b7358'; SourcePrefix = 'releases/app/staging'; ProjectName = 'oficina-phase3-oficina-app-staging-deploy'; SourceCommit = $commit
        CloudWindowEvidenceFile = $windowPath; PromotionEvidenceOutputFile = $receiptPath; EventName = 'push'; BranchRef = 'refs/heads/develop'; TimeoutSeconds = 60
        PlatformInputsFile=$platformPath; StagingWorkloadFile=$workloadPath; RuntimePublicConfigMapFile="$temp/public.json"
    }

    $dryRun = & (Join-Path $launcherScripts 'start-deploy.ps1') @launch -DryRun
    if (($dryRun | Select-Object -Last 1) -cne 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED') { throw 'Dry run did not validate without calling AWS.' }
    if ($global:AwsCalls.Count -ne 0) { throw 'Dry run called AWS.' }
    if ((Get-Content -LiteralPath $contextMarker -Raw) -cne "staging|push|refs/heads/develop") { throw 'Workflow-context guard was not invoked through the launcher.' }
    if (-not (Get-Content -LiteralPath $windowMarker -Raw).StartsWith('staging|')) { throw 'Cloud-window guard was not invoked through the launcher.' }

    $result = & (Join-Path $launcherScripts 'start-deploy.ps1') @launch
    if (($result | Select-Object -Last 1) -cne 'Deployment build completed successfully: oficina-phase3-oficina-app-staging-deploy:build-001') { throw 'Successful build was not reported.' }
    if (-not (Test-Path -LiteralPath $receiptPath -PathType Leaf)) { throw 'Staging promotion receipt was not written.' }
    $receipt = Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
    foreach ($name in @('sourceVersionId', 'releaseManifestVersionId', 'codeBuildBuildId')) {
        if ([string]::IsNullOrWhiteSpace([string]$receipt.$name)) { throw "Receipt is missing $name." }
    }
    if ($receipt.sourceVersionId -cne 'source-version-001' -or $receipt.releaseManifestVersionId -cne 'manifest-version-001' -or $receipt.codeBuildBuildId -cne 'oficina-phase3-oficina-app-staging-deploy:build-001') { throw 'Receipt does not bind immutable launch identities.' }
    if ((Get-Content -LiteralPath $receiptPath -Raw) -match '(?i)password|secret|token') { throw 'Promotion receipt contains a secret-like field.' }
    if ($receipt.terraformVariablesKey -cne "releases/app/staging/config/$commit.tfvars.json" -or
        $receipt.terraformVariablesVersionId -cne 'tfvars-version-001' -or
        $receipt.terraformVariablesSha256 -cne $tfvarsSha -or $receipt.deployerImageDigest -cne $deployerDigest) { throw 'Receipt does not bind reviewed executor and immutable Terraform inputs.' }

    $sourcePut = $global:AwsCalls | Where-Object { $_ -match '^s3api put-object' -and $_ -match 'releases/app/staging/bundle\.zip' }
    $manifestPut = $global:AwsCalls | Where-Object { $_ -match '^s3api put-object' -and $_ -match 'releases/app/staging/manifests/' }
    $promotionPut = $global:AwsCalls | Where-Object { $_ -match '^s3api put-object' -and $_ -match 'releases/app/staging/promotions/' }
    $tfvarsPut = @($global:AwsCalls | Where-Object { $_ -match '^s3api put-object' -and $_.Contains("--key releases/app/staging/config/$commit.tfvars.json --body $tfvarsPath") })
    if (@($sourcePut).Count -ne 1 -or @($manifestPut).Count -ne 1 -or @($promotionPut).Count -ne 1 -or $tfvarsPut.Count -ne 1) { throw 'Versioned source, manifest, tfvars and staging receipt uploads were not all issued.' }
    $buildCalls = @($global:AwsCalls | Where-Object { $_ -match '^codebuild start-build' })
    if ($buildCalls.Count -ne 1 -or ([string]$buildCalls[0]) -notmatch '--source-version source-version-001') { throw "CodeBuild was not pinned to the source VersionId. Calls: $($global:AwsCalls -join ' | ')" }
    $buildCall = [string]$buildCalls[0]
    if(-not$buildCall.Contains("name=RUNTIME_PUBLIC_CONFIGMAP_SHA256,value=$($manifest.runtimePublicConfigMapSha256),type=PLAINTEXT") -or
       $receipt.runtimePublicConfigMapKey -cne "releases/app/staging/inputs/$commit/runtime-public.json" -or
       $receipt.runtimePublicConfigMapVersionId -cne 'runtime-version-001' -or
       $receipt.runtimePublicConfigMapSha256 -cne $manifest.runtimePublicConfigMapSha256){throw 'Public ConfigMap digest/version receipt binding was lost.'}
    $allowed = @('DEPLOY_ENVIRONMENT', 'SOURCE_BUCKET', 'SOURCE_KEY', 'SOURCE_VERSION_ID', 'EXPECTED_SHA256', 'RELEASE_MANIFEST_KEY', 'RELEASE_MANIFEST_VERSION_ID', 'EXPECTED_MANIFEST_SHA256', 'SOURCE_COMMIT', 'DEPLOYER_IMAGE_DIGEST', 'TFVARS_OBJECT_KEY', 'TFVARS_VERSION_ID', 'EXPECTED_TFVARS_SHA256')
    $allowed += @('PLATFORM_INPUTS_OBJECT_KEY','PLATFORM_INPUTS_VERSION_ID','STAGING_WORKLOAD_OBJECT_KEY','STAGING_WORKLOAD_VERSION_ID','CLOUD_WINDOW_OBJECT_KEY','CLOUD_WINDOW_VERSION_ID','RUNTIME_PUBLIC_CONFIGMAP_OBJECT_KEY','RUNTIME_PUBLIC_CONFIGMAP_VERSION_ID','RUNTIME_PUBLIC_CONFIGMAP_SHA256')
    foreach($binding in @(@{Name='PLATFORM_INPUTS';Leaf='platform.json'},@{Name='STAGING_WORKLOAD';Leaf='workload.json'},@{Name='CLOUD_WINDOW';Leaf='cloud-window.json'},@{Name='RUNTIME_PUBLIC_CONFIGMAP';Leaf='runtime-public.json'})) {
        if(-not $buildCall.Contains("name=$($binding.Name)_OBJECT_KEY,value=releases/app/staging/inputs/$commit/$($binding.Leaf),type=PLAINTEXT") -or
            -not $buildCall.Contains("name=$($binding.Name)_VERSION_ID,value=runtime-version-001,type=PLAINTEXT")){throw 'Public runtime handoff must use exact object keys and immutable versions.'}
    }
    foreach ($name in $allowed) {
        if ([regex]::Matches($buildCall, "name=$name,value=").Count -ne 1) { throw "Required override must appear exactly once: $name" }
    }
    foreach ($binding in @("DEPLOYER_IMAGE_DIGEST,value=$deployerDigest", "TFVARS_OBJECT_KEY,value=releases/app/staging/config/$commit.tfvars.json", 'TFVARS_VERSION_ID,value=tfvars-version-001', "EXPECTED_TFVARS_SHA256,value=$tfvarsSha")) {
        if (-not $buildCall.Contains("name=$binding,type=PLAINTEXT")) { throw "Incorrect reviewed override: $binding" }
    }
    foreach ($override in ($buildCall -split 'name=' | Select-Object -Skip 1)) {
        $name = ($override -split ',')[0]
        if ($allowed -notcontains $name) { throw "Unexpected CodeBuild environment override: $name" }
    }

    $production = $launch.Clone(); $production.Environment = 'production'; $production.SourcePrefix = 'releases/app/production'; $production.ProjectName = 'oficina-phase3-oficina-app-production-deploy'; $production.BranchRef = 'refs/heads/main'
    Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @production -DryRun }
    $badTimeout = $launch.Clone(); $badTimeout.TimeoutSeconds = 30
    Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @badTimeout -DryRun }
    $badPrefix = $launch.Clone(); $badPrefix.SourcePrefix = 'releases/app/other'
    Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @badPrefix -DryRun }
    $liveMismatchPrefix = $launch.Clone(); $liveMismatchPrefix.SourcePrefix = 'releases/application/staging'
    Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @liveMismatchPrefix -DryRun }

    $global:AwsCalls.Clear()
    foreach($field in @('PlatformInputsFile','StagingWorkloadFile','CloudWindowEvidenceFile','RuntimePublicConfigMapFile')) {
        $bad=$launch.Clone(); $bad[$field]="$temp/missing.json"
        Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @bad -DryRun }
        $bad[$field]=$tfvarsPath
        Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @bad -DryRun }
    }
    foreach($field in @('platformInputsSha256','stagingWorkloadSha256','cloudWindowEvidenceSha256','runtimePublicConfigMapSha256')) {
        foreach($value in @($null,@(),@('a'*64),('0'*64))) {
            $badManifest=$manifest.Clone(); $badManifest[$field]=$value; Save-Json $badManifest $manifestPath
            $bad=$launch.Clone(); $bad.ExpectedManifestSha256=Sha $manifestPath
            Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @bad -DryRun }
        }
    }
    Save-Json $manifest $manifestPath
    foreach ($name in @('TerraformVariablesFile', 'ExpectedTerraformVariablesSha256', 'DeployerImageDigest')) {
        $bad = $launch.Clone(); $bad[$name] = ''
        Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @bad -DryRun }
    }
    foreach ($entry in @(@('TerraformVariablesFile', "$temp/missing.json"), @('ExpectedTerraformVariablesSha256', ('d' * 64)), @('DeployerImageDigest', 'latest'), @('DeployerImageDigest', ('sha256:' + ('d' * 64))))) {
        $bad = $launch.Clone(); $bad[$entry[0]] = $entry[1]
        Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @bad -DryRun }
    }
    foreach ($field in @('deployerImageDigest', 'terraformVariablesSha256')) {
        $badManifest = $manifest.Clone(); $badManifest.Remove($field)
        Save-Json $badManifest $manifestPath
        $bad = $launch.Clone(); $bad.ExpectedManifestSha256 = Sha $manifestPath
        Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @bad -DryRun }
    }
    # Rehashing changed bytes must not silently turn them into the reviewed configuration.
    Save-Json @{ environment = 'changed' } $tfvarsPath
    Save-Json $manifest $manifestPath
    $bad = $launch.Clone(); $bad.ExpectedTerraformVariablesSha256 = Sha $tfvarsPath
    Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @bad -DryRun }
    foreach ($invalidJson in @('not-json', '[]', 'null')) {
        Set-Content -LiteralPath $tfvarsPath -Value $invalidJson -NoNewline
        $bad = $launch.Clone(); $bad.ExpectedTerraformVariablesSha256 = Sha $tfvarsPath
        Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @bad -DryRun }
    }
    if ($global:AwsCalls.Count -ne 0) { throw 'Rejected reviewed inputs called AWS.' }

    Save-Json @{ environment = 'staging' } $tfvarsPath
    foreach ($response in @('{}', '{"VersionId":""}', '{"VersionId":"null"}', 'not-json')) {
        $global:AwsCalls.Clear(); $global:TfvarsUploadResponse = $response
        Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @launch }
        if (@($global:AwsCalls | Where-Object { $_ -match '^codebuild ' }).Count -ne 0) { throw 'Missing immutable tfvars VersionId reached CodeBuild.' }
    }
    $global:TfvarsUploadResponse='{"VersionId":"tfvars-version-001"}'
    foreach($response in @('{}','{"VersionId":null}','{"VersionId":[]}','{"VersionId":["value"]}','{"VersionId":"null"}','not-json')) {
        $global:AwsCalls.Clear(); $global:RuntimeUploadResponse=$response
        Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @launch }
        if(@($global:AwsCalls | Where-Object {$_ -match '^codebuild '}).Count -ne 0){throw 'Invalid runtime input VersionId reached CodeBuild.'}
    }

    Write-Output 'PASS: APP launcher validates guards, pins versioned S3/CodeBuild inputs, polls success, and emits a redacted staging receipt without real AWS.'
}
finally {
    Remove-Item function:aws -ErrorAction SilentlyContinue
    Remove-Item function:Start-Sleep -ErrorAction SilentlyContinue
    Remove-Variable -Name TfvarsUploadResponse -Scope Global -ErrorAction SilentlyContinue
    Remove-Variable -Name RuntimeUploadResponse -Scope Global -ErrorAction SilentlyContinue
    Remove-Variable -Name AwsCalls -Scope Global -ErrorAction SilentlyContinue
    $resolved = [IO.Path]::GetFullPath($temp)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('oficina-app-launcher-')) { throw 'Unsafe cleanup target.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
