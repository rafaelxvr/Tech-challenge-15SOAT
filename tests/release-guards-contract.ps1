[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$owner = 'app'
$temp = Join-Path ([IO.Path]::GetTempPath()) ('oficina-release-guards-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
function aws { throw 'AWS is forbidden in this offline contract.' }
function terraform { throw 'Terraform is forbidden in this offline contract.' }
function kubectl { throw 'Kubernetes is forbidden in this offline contract.' }
function Reject([scriptblock]$Action) { try { & $Action | Out-Null } catch { return }; throw 'Expected release input rejection.' }
function RejectWithMessage([scriptblock]$Action, [string]$ExpectedMessage) {
    try { & $Action | Out-Null }
    catch {
        if ($_.Exception.Message.StartsWith($ExpectedMessage, [StringComparison]::Ordinal)) { return }
        throw "Expected '$ExpectedMessage', received '$($_.Exception.Message)'."
    }
    throw "Expected rejection: $ExpectedMessage"
}
function Save($object, $path) { $object | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $path -NoNewline }
function Sha($path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
try {
    $terraformRoot = Join-Path $repo 'infra/environments/staging'
    $createdTerraformRoot = $false
    if (-not (Test-Path -LiteralPath $terraformRoot -PathType Container)) {
        New-Item -ItemType Directory -Path $terraformRoot -Force | Out-Null
        $createdTerraformRoot = $true
    }
    $tfvarsDirectory = '/tmp/oficina'
    $tfvarsPathForApply = '/tmp/oficina/app_staging.tfvars.json'
    $createdTfvarsDirectory = $false
    if (-not (Test-Path -LiteralPath $tfvarsDirectory -PathType Container)) {
        New-Item -ItemType Directory -Path $tfvarsDirectory -Force | Out-Null
        $createdTfvarsDirectory = $true
    }
    $tfvarsWasPresent = Test-Path -LiteralPath $tfvarsPathForApply -PathType Leaf
    if (-not $tfvarsWasPresent) { Save @{ reviewedFixture = $true } $tfvarsPathForApply }
    $terraformLog = Join-Path $temp 'terraform.log'
    function terraform {
        Add-Content -LiteralPath $terraformLog -Value (($args | ForEach-Object { [string]$_ }) -join ' ')
        $out = @($args | Where-Object { [string]$_ -like '-out=*' })
        if ($out.Count -eq 1) { New-Item -ItemType File -Path ([string]$out[0]).Substring(5) -Force | Out-Null }
        $global:LASTEXITCODE = 0
    }
    $now = [datetime]::UtcNow
    $evidence = @{ windowStartUtc=$now.AddMinutes(-2).ToString('o'); windowEndUtc=$now.AddMinutes(30).ToString('o'); recordedAtUtc=$now.ToString('o'); accountEvidenceReference='offline-fixture'; projectAllowanceUsd=80; reserveUsd=20; currentEstimatedSpendUsd=0 }
    $window = Join-Path $temp 'window.json'; Save $evidence $window
    $bundle = Join-Path $temp 'bundle.zip'; 'offline' | Set-Content -LiteralPath $bundle
    $manifestPath = Join-Path $temp 'manifest.json'
    $tfvarsPath = Join-Path $temp 'reviewed.tfvars.json'
    Save @{ reviewedFixture = $true } $tfvarsPath
    foreach ($environment in @('staging','production')) {
        $manifest = @{ schemaVersion=1; environment=$environment; sourceCommit=('a'*40); artifactSha256=(Sha $bundle); deployerImageDigest=('sha256:' + ('b'*64)); contractVersion='phase3-v2'; migrationVersion='V8'; runtimeArtifactDigest=('sha256:' + ('c'*64)); promotedFromStaging=($environment -eq 'production'); stagingArtifactSha256=(Sha $bundle) }
        $manifest.terraformVariablesSha256 = Sha $tfvarsPath
        Save $manifest $manifestPath
        $launch = @{Environment=$environment; SourceZip=$bundle; ExpectedSha256=(Sha $bundle); ReleaseManifest=$manifestPath; ExpectedManifestSha256=(Sha $manifestPath); Bucket='oficina-artifacts-fixture'; SourceCommit=('a'*40); ProjectName="oficina-phase3-oficina-$owner-$environment-deploy"; SourcePrefix="releases/$owner/$environment"; CloudWindowEvidenceFile=$window; EventName='push'; BranchRef=$(if($environment -eq 'staging'){'refs/heads/develop'}else{'refs/heads/main'})}
        $launch.TerraformVariablesFile = $tfvarsPath
        $launch.ExpectedTerraformVariablesSha256 = Sha $tfvarsPath
        $launch.DeployerImageDigest = $manifest.deployerImageDigest
        if ((& "$repo/scripts/start-deploy.ps1" @launch -DryRun) -cne 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED') { throw 'Dry run must remain explicitly disabled.' }
        Reject { & "$repo/scripts/start-deploy.ps1" @launch }
        # Both environments use the canonical APP Terraform namespace. The
        # default and dry-run paths remain side-effect free.
        $executorNamespace = 'app'
        $deploy=@{Environment=$environment; ReleaseManifest=$manifestPath; ExpectedSourceSha256=(Sha $bundle); ExpectedManifestSha256=(Sha $manifestPath); SourceCommit=('a'*40); ExpectedDeployerImageDigest=('sha256:' + ('b'*64)); TerraformVariablesFile="/tmp/oficina/${executorNamespace}_$environment.tfvars.json"; TerraformBackendBucket='oficina-state-fixture'; TerraformBackendKey="$executorNamespace/$environment.tfstate"; TerraformBackendLockKey="$executorNamespace/$environment.tfstate.tflock"; TerraformBackendRegion='us-east-1'}
        if ((& "$repo/scripts/deploy.ps1" @deploy -DryRun) -cne 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED') { throw 'Executor dry-run must not report deployment success.' }
        if ($environment -eq 'staging') {
            RejectWithMessage { & "$repo/scripts/deploy.ps1" @deploy } 'APP_DEPLOYMENT_DISABLED:'
        }
        else {
            RejectWithMessage { & "$repo/scripts/deploy.ps1" @deploy } 'APP_PRODUCTION_DEPLOYMENT_DISABLED:'
        }
        if ((& "$repo/scripts/deploy.ps1" @deploy -ApplyReviewedPlan -DryRun) -cne 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED') { throw 'Dry-run must remain disabled even when apply was requested.' }
        if ($environment -eq 'staging') {
            Remove-Item -LiteralPath $terraformLog -Force -ErrorAction SilentlyContinue
            $stagingDeploy = $deploy.Clone(); $stagingDeploy.TerraformVariablesFile = $tfvarsPathForApply
            $activationOutput = & "$repo/scripts/deploy.ps1" @stagingDeploy -ApplyReviewedPlan
            if ($activationOutput -cne 'Reviewed Terraform plan applied.') { throw 'Explicit staging activation must apply the reviewed Terraform plan.' }
            $terraformCalls = @(Get-Content -LiteralPath $terraformLog)
            if ($terraformCalls.Count -ne 4 -or
                $terraformCalls[0] -notmatch 'init.*-backend-config=bucket=oficina-state-fixture.*-backend-config=key=app/staging.tfstate.*-backend-config=region=us-east-1.*use_lockfile=true' -or
                $terraformCalls[1] -notmatch 'validate' -or
                $terraformCalls[2] -notmatch 'plan.*-var-file=/tmp/oficina/app_staging.tfvars.json.*-out=' -or
                $terraformCalls[3] -notmatch 'apply.*oficina-app-staging-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\.tfplan') {
                throw 'Staging activation must invoke Terraform init, validate, plan and apply with reviewed inputs.'
            }
        }
        else {
            RejectWithMessage { & "$repo/scripts/deploy.ps1" @deploy -ApplyReviewedPlan } 'APP_PRODUCTION_DEPLOYMENT_DISABLED:'
        }
        $otherEnvironment = if ($environment -ceq 'staging') { 'production' } else { 'staging' }
        $otherNamespace = 'application'
        foreach ($entry in @(
            @('TerraformBackendKey', "$otherNamespace/$environment.tfstate"),
            @('TerraformBackendKey', "$executorNamespace/$otherEnvironment.tfstate"),
            @('TerraformBackendLockKey', "$executorNamespace/$otherEnvironment.tfstate.tflock"),
            @('TerraformBackendLockKey', "$executorNamespace/$environment.tfstate"),
            @('TerraformVariablesFile', "/tmp/oficina/${otherNamespace}_$environment.tfvars.json"),
            @('TerraformVariablesFile', "/tmp/oficina/${executorNamespace}_$otherEnvironment.tfvars.json"),
            @('TerraformVariablesFile', "/tmp/oficina/../${executorNamespace}_$environment.tfvars.json"),
            @('TerraformBackendRegion', 'us-west-2')
        )) {
            $bad=$deploy.Clone(); $bad[$entry[0]]=$entry[1]
            RejectWithMessage { & "$repo/scripts/deploy.ps1" @bad -DryRun } 'Unreviewed application state, lock, region or trusted tfvars path.'
        }
        foreach ($entry in @(@('EventName','pull_request'),@('BranchRef','refs/heads/master'),@('ExpectedSha256',('d'*64)),@('ProjectName','wrong-project'),@('SourcePrefix','releases/other/staging'))) {
            $bad=$launch.Clone(); $bad[$entry[0]]=$entry[1]; Reject { & "$repo/scripts/start-deploy.ps1" @bad -DryRun }
        }
        $evidence.windowEndUtc=$now.AddMinutes(-1).ToString('o'); Save $evidence $window
        Reject { & "$repo/scripts/start-deploy.ps1" @launch -DryRun }
        $evidence.windowEndUtc=$now.AddMinutes(30).ToString('o'); Save $evidence $window
        $manifest.runtimeArtifactDigest='latest'; Save $manifest $manifestPath; $launch.ExpectedManifestSha256=Sha $manifestPath
        Reject { & "$repo/scripts/start-deploy.ps1" @launch -DryRun }
        $manifest.runtimeArtifactDigest='sha256:' + ('c'*64)
        if($environment -eq 'production') {
            $manifest.stagingArtifactSha256='d'*64; Save $manifest $manifestPath; $launch.ExpectedManifestSha256=Sha $manifestPath
            Reject { & "$repo/scripts/start-deploy.ps1" @launch -DryRun }
        }
    }
    $lock = "$repo/scripts/deployment-lock.ps1"
    $first=[guid]::NewGuid().ToString(); $second=[guid]::NewGuid().ToString()
    $lockInputs=@{StateBucket='oficina-state-fixture'; Offline=$true; OfflineDirectory="$temp/locks"}
    & $lock -Action Acquire -OwnerToken $first @lockInputs | Out-Null
    Reject { & $lock -Action Acquire -OwnerToken $second @lockInputs }
    Reject { & $lock -Action Release -OwnerToken $second @lockInputs }
    & $lock -Action Release -OwnerToken $first @lockInputs | Out-Null
    Write-Output 'PASS: release branch, immutable source/runtime digest, closed window, staging promotion, guarded staging Terraform activation and non-stealable lock contracts.'
}
finally {
    $resolved=[IO.Path]::GetFullPath($temp)
    if(-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('oficina-release-guards-')) { throw 'Unsafe test cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
    if ($createdTerraformRoot -and (Test-Path -LiteralPath $terraformRoot -PathType Container)) { Remove-Item -LiteralPath $terraformRoot -Recurse -Force }
    if (-not $tfvarsWasPresent -and (Test-Path -LiteralPath $tfvarsPathForApply -PathType Leaf)) { Remove-Item -LiteralPath $tfvarsPathForApply -Force }
    if ($createdTfvarsDirectory -and (Test-Path -LiteralPath $tfvarsDirectory -PathType Container)) { Remove-Item -LiteralPath $tfvarsDirectory -Recurse -Force }
}
