[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ReleaseFile,
    [Parameter(Mandatory)][string]$ExpectedReleaseSha256,
    [Parameter(Mandatory)][string]$PlatformInputsFile,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [switch]$ExecuteReviewedPlan,
    [ValidateRange(1, 600)][int]$DrainTimeoutSeconds = 120,
    [string]$StagingWorkloadFile,
    [string]$RuntimePublicConfigMapFile,
    [string]$CloudWindowEvidenceFile,
    [string]$StateBucket,
    [string]$SourceArchiveFile,
    [string]$SourceKey,
    [string]$ExpectedDeployerImageDigest,
    [string]$ProductionEnabled='', [string]$ProductionRuntimeEnabled='', [string]$ProtectedEnvironment='',
    [string]$ProductionInputsFile='', [string]$ExpectedProductionInputsSha256='', [string]$ProductionRoleArn='',
    [string]$EventName=$env:GITHUB_EVENT_NAME, [string]$BranchRef=$env:GITHUB_REF
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'app-release-contract.ps1')
$contract = Read-AppRelease $ReleaseFile $ExpectedReleaseSha256 $PlatformInputsFile
$release = $contract.Release
if($release.environment -cne 'staging' -and -not[string]::IsNullOrWhiteSpace($RuntimePublicConfigMapFile)){throw 'APP_PUBLIC_CONFIG_INVALID: staging artifact forbidden for production.'}
$productionReview=$null
if ($ExecuteReviewedPlan -and $release.environment -ceq 'production') {
    . (Join-Path $PSScriptRoot 'production-executor-inputs.ps1')
    $productionReview=Read-ProductionExecutorInputs -Enabled $ProductionEnabled -RuntimeEnabled $ProductionRuntimeEnabled -ProtectedEnvironment $ProtectedEnvironment `
        -InputsFile $ProductionInputsFile -ExpectedInputsSha256 $ExpectedProductionInputsSha256 -RoleArn $ProductionRoleArn `
        -SourceCommit $release.sourceCommit -EventName $EventName -BranchRef $BranchRef
    if ($ProductionRuntimeEnabled -cne 'true') { throw 'APP_PRODUCTION_RUNTIME_DISABLED' }
    if ($ExpectedReleaseSha256 -cne $productionReview.ReleaseFile.Sha256 -or
        (Get-AppFileHash $PlatformInputsFile) -cne $productionReview.Platform.Sha256) { throw 'APP_PRODUCTION_RUNTIME_BINDING_MISMATCH' }
    $StateBucket=$productionReview.Inputs.stateBucket
    $CloudWindowEvidenceFile=$productionReview.Window.Path
}
$initializeStaging = -not [string]::IsNullOrWhiteSpace($StagingWorkloadFile)
$guardedExecution=$initializeStaging -or $null -ne $productionReview
if ($initializeStaging) {
    . (Join-Path $PSScriptRoot 'staging-workload-contract.ps1')
    $workload = Read-StagingWorkload $StagingWorkloadFile $release $contract.Platform
    . (Join-Path $PSScriptRoot 'runtime-public-configmap-contract.ps1')
    $publicConfig=Read-StagingPublicConfigMap $RuntimePublicConfigMapFile $release
    if ($StateBucket -cnotmatch '\A[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]\z' -or
        $SourceKey -cne 'releases/app/staging/bundle.zip' -or
        $release.artifactSha256 -isnot [string] -or $release.artifactSha256 -cnotmatch '\A[a-f0-9]{64}\z' -or
        (Get-AppFileHash $SourceArchiveFile) -cne $release.artifactSha256 -or
        $ExpectedDeployerImageDigest -cnotmatch '\Asha256:[a-f0-9]{64}\z' -or
        $release.deployerImageDigest -isnot [string] -or $release.deployerImageDigest -cne $ExpectedDeployerImageDigest) {
        throw 'Unreviewed staging source archive, source key, deployer digest or lock bucket.'
    }
}
& (Join-Path $PSScriptRoot 'render-app-release.ps1') -ReleaseFile $ReleaseFile -ExpectedReleaseSha256 $ExpectedReleaseSha256 -PlatformInputsFile $PlatformInputsFile -OutputDirectory $OutputDirectory | Out-Null
if ($initializeStaging) {
    foreach ($entry in @(@{Name='bootstrap-deployment.json';Value=$workload.Deployment}, @{Name='bootstrap-serviceaccount.json';Value=$workload.ServiceAccount})) {
        $entry.Value | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath (Join-Path $OutputDirectory $entry.Name) -NoNewline
    }
}
if (-not $ExecuteReviewedPlan) { Write-Output 'RENDERED_ONLY: no cluster operations performed.'; return }
@{schemaVersion=1; releaseSha256=$ExpectedReleaseSha256; status='IN_PROGRESS'} | ConvertTo-Json |
    Set-Content -LiteralPath (Join-Path $OutputDirectory 'rollout-receipt.json')

# Staging initialization and reviewed production execution own the shared lock.
# Other staging existing-workload invocations still require their caller's lock.
# Every invocation pins its context/namespace; never trust the operator's current context.
function Invoke-ReleaseKubectl([string[]]$Arguments) {
    if ($guardedExecution -and $Arguments[0] -cin @('create','delete','patch','apply')) {
        if ($null -ne $productionReview -and (Get-AppFileHash $CloudWindowEvidenceFile) -cne $productionReview.Window.Sha256) {
            throw 'APP_PRODUCTION_WINDOW_CHANGED'
        }
        & (Join-Path $PSScriptRoot 'check-cloud-window.ps1') -EvidenceFile $CloudWindowEvidenceFile -Environment $release.environment | Out-Null
    }
    $global:LASTEXITCODE = 0
    $result = & kubectl --context $release.kubeContext --namespace "oficina-$($release.environment)" @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "APP release kubectl operation failed: $($Arguments[0]). Inspect restricted executor evidence." }
    return $result
}
function Read-ClusterObject([string]$Kind, [string]$Name) {
    $arguments = @('get', $Kind, $Name, '-o', 'json')
    if ($initializeStaging) { $arguments += '--ignore-not-found=true' }
    $json = (Invoke-ReleaseKubectl $arguments) -join "`n"
    if ([string]::IsNullOrWhiteSpace($json)) {
        if ($initializeStaging) { return $null }
        throw 'APP cluster response is unexpectedly empty.'
    }
    try { $object = ConvertFrom-Json -InputObject $json -NoEnumerate }
    catch { throw 'APP cluster response is not valid JSON.' }
    $expected = @{
        deployment = @{Kind='Deployment'; ApiVersion='apps/v1'}
        hpa = @{Kind='HorizontalPodAutoscaler'; ApiVersion='autoscaling/v2'}
        serviceaccount = @{Kind='ServiceAccount'; ApiVersion='v1'}
        job = @{Kind='Job'; ApiVersion='batch/v1'}
        configmap = @{Kind='ConfigMap'; ApiVersion='v1'}
    }[$Kind]
    try {
        if ($object -isnot [pscustomobject] -or
            $object.kind -isnot [string] -or $object.kind -cne $expected.Kind -or
            $object.apiVersion -isnot [string] -or $object.apiVersion -cne $expected.ApiVersion -or
            $object.metadata -isnot [pscustomobject] -or
            $object.metadata.name -isnot [string] -or $object.metadata.name -cne $Name -or
            $object.metadata.namespace -isnot [string] -or $object.metadata.namespace -cne "oficina-$($release.environment)" -or
            ($Kind -cnotin @('serviceaccount','configmap') -and $object.spec -isnot [pscustomobject])) { throw 'Invalid object.' }
    } catch { throw 'APP cluster response does not match the requested Kubernetes object.' }
    return $object
}
function Read-BootstrapJobReceipt([string]$JobName) {
    $logs = (Invoke-ReleaseKubectl @('logs', "job/$JobName", '--all-containers=true', '--tail=-1')) -join "`n"
    $begin = 'BOOTSTRAP_RECEIPT_JSON_BEGIN'
    $end = 'BOOTSTRAP_RECEIPT_JSON_END'
    $start = $logs.IndexOf($begin, [StringComparison]::Ordinal)
    $finish = $logs.IndexOf($end, [StringComparison]::Ordinal)
    if ($start -lt 0 -or $finish -le $start) { throw 'Bootstrap Job completed without a bounded receipt.' }
    $jsonStart = $start + $begin.Length
    $receiptJson = $logs.Substring($jsonStart, $finish - $jsonStart).Trim()
    $account = [regex]::Match($release.image, '\A([0-9]{12})\.dkr\.ecr\.us-east-1\.amazonaws\.com/').Groups[1].Value
    $validated = Read-BootstrapReceipt $receiptJson $release $account
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'bootstrap-receipt.json'), $receiptJson, [Text.UTF8Encoding]::new($false))
    return $validated
}
$lockAcquired = $false
$owner = [guid]::NewGuid().ToString()
try {
if ($guardedExecution) {
    & (Join-Path $PSScriptRoot 'check-cloud-window.ps1') -EvidenceFile $CloudWindowEvidenceFile -Environment $release.environment | Out-Null
    & (Join-Path $PSScriptRoot 'deployment-lock.ps1') -Action Acquire -StateBucket $StateBucket -OwnerToken $owner | Out-Null
    $lockAcquired = $true
}
$deployment = Read-ClusterObject 'deployment' 'oficina-app'
$hpa = Read-ClusterObject 'hpa' 'oficina-app'
$serviceAccount = Read-ClusterObject 'serviceaccount' 'oficina-app'
$fresh = $false
if ($initializeStaging) {
    $missing = @(@($deployment, $hpa, $serviceAccount) | Where-Object { $null -eq $_ }).Count
    if ($missing -gt 0 -and $missing -ne 3) { throw 'Partial APP workload found; review recovery before creating or replacing resources.' }
    # Stable public configuration is installed under the shared lock before any
    # bootstrap writer. Existing content must match; never overwrite drift.
    $existingPublic=Read-ClusterObject 'configmap' 'oficina-runtime-public-staging'
    if($null -ne $existingPublic){
        Assert-StagingPublicConfigMapObject $existingPublic $release
        foreach($key in $publicConfig.data.PSObject.Properties.Name){if($existingPublic.data.$key -cne $publicConfig.data.$key){throw 'APP_PUBLIC_CONFIG_DRIFT: review existing public configuration.'}}
    }
    if ($missing -eq 3) {
        if ($release.previousImage -cne $release.image) { throw 'Fresh staging must explicitly use the target digest as previousImage; no previous workload exists.' }
        # Detect orphaned writers before creating any resource.
        $pods = (Invoke-ReleaseKubectl @('get','pods','-l','app.kubernetes.io/name=oficina-app','-o','json')) -join "`n" | ConvertFrom-Json
        if (@($pods.items).Count -ne 0) { throw 'Existing APP pods forbid fresh initialization.' }
        Invoke-ReleaseKubectl @('create','-f',(Join-Path $OutputDirectory 'bootstrap-serviceaccount.json')) | Out-Null
        Invoke-ReleaseKubectl @('create','-f',(Join-Path $OutputDirectory 'bootstrap-deployment.json')) | Out-Null
        $deployment = Read-ClusterObject 'deployment' 'oficina-app'
        $serviceAccount = Read-ClusterObject 'serviceaccount' 'oficina-app'
        $hpa = $workload.HorizontalPodAutoscaler
        $fresh = $true
    }
}
$containers = @($deployment.spec.template.spec.containers | Where-Object name -CEQ 'app')
$expectedCurrent = if ($release.mode -ceq 'Rollback') { $release.image } else { $release.previousImage }
$minimum = if ($release.environment -ceq 'production') { 2 } else { 1 }
if ($containers.Count -ne 1 -or $containers[0].image -cne $expectedCurrent -or
    $deployment.spec.template.spec.serviceAccountName -cne 'oficina-app' -or
    $deployment.spec.selector.matchLabels.'app.kubernetes.io/name' -cne 'oficina-app' -or
    $serviceAccount.metadata.annotations.'eks.amazonaws.com/role-arn' -cne $contract.Platform.AppIrsaRoleArn) { throw 'Existing APP Deployment/IRSA differs from the reviewed release.' }
if ($hpa.spec.minReplicas -ne $minimum -or $hpa.spec.maxReplicas -ne ($minimum * 2) -or
    $hpa.spec.scaleTargetRef.kind -cne 'Deployment' -or $hpa.spec.scaleTargetRef.name -cne 'oficina-app' -or
    @($hpa.spec.metrics).Count -ne 1 -or $hpa.spec.metrics[0].resource.name -cne 'cpu' -or
    $hpa.spec.metrics[0].resource.target.averageUtilization -ne 60) { throw 'Existing HPA differs from the environment capacity contract.' }
if ($release.mode -cne 'FirstWriter' -and
    ($deployment.spec.template.metadata.annotations.'oficina.io/schema-version' -cne 'V8' -or
     $deployment.spec.template.metadata.annotations.'oficina.io/security-contract' -cne 'phase3-v2')) { throw 'Compatible rollout/rollback requires the current V8 security contract.' }

if($initializeStaging -and $null -eq $existingPublic){
    $null=Read-StagingPublicConfigMap $RuntimePublicConfigMapFile $release
    Invoke-ReleaseKubectl @('create','-f',$RuntimePublicConfigMapFile)|Out-Null
    $existingPublic=Read-ClusterObject 'configmap' 'oficina-runtime-public-staging'
    Assert-StagingPublicConfigMapObject $existingPublic $release
    foreach($key in $publicConfig.data.PSObject.Properties.Name){if($existingPublic.data.$key -cne $publicConfig.data.$key){throw 'APP_PUBLIC_CONFIG_READBACK_MISMATCH'}}
}
$startedAt = [DateTimeOffset]::UtcNow
$migration = 'NOT_RUN_ROLLBACK'
$bootstrapReceiptSha256 = 'NOT_RUN_ROLLBACK'
if ($release.mode -ceq 'FirstWriter') {
    if (-not $fresh) { Invoke-ReleaseKubectl @('delete', 'hpa', 'oficina-app', '--wait=true') | Out-Null }
    Invoke-ReleaseKubectl @('patch', 'deployment', 'oficina-app', '--type=strategic', '--patch-file', (Join-Path $OutputDirectory 'drain-patch.json')) | Out-Null
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($DrainTimeoutSeconds)
    do {
        $pods = (Invoke-ReleaseKubectl @('get', 'pods', '-l', 'app.kubernetes.io/name=oficina-app', '-o', 'json')) -join "`n" | ConvertFrom-Json
        $drained = Read-ClusterObject 'deployment' 'oficina-app'
        if (@($pods.items).Count -eq 0 -and $drained.spec.replicas -eq 0) { break }
        if ([DateTimeOffset]::UtcNow -ge $deadline) { throw 'Old APP writers did not drain; migration is forbidden.' }
        Start-Sleep -Seconds 1
    } while ($true)
}
if ($release.mode -cne 'Rollback') {
    # create fails on an existing Job: a stale completed Job cannot satisfy this release.
    Invoke-ReleaseKubectl @('create', '-f', (Join-Path $OutputDirectory 'bootstrap-review.json')) | Out-Null
    Invoke-ReleaseKubectl @('create', '-f', (Join-Path $OutputDirectory 'migration-job.json')) | Out-Null
    $jobName = 'oficina-migrate-' + $ExpectedReleaseSha256.Substring(0, 12)
    Invoke-ReleaseKubectl @('wait', "job/$jobName", '--for=condition=complete', '--timeout=660s') | Out-Null
    $job = Read-ClusterObject 'job' $jobName
    if (@($job.status.conditions | Where-Object { $_.type -ceq 'Complete' -and $_.status -ceq 'True' }).Count -ne 1 -or
        @($job.status.conditions | Where-Object { $_.type -ceq 'Failed' -and $_.status -ceq 'True' }).Count -gt 0 -or
        $job.spec.template.spec.containers[0].image -cne $release.bootstrapImage) { throw 'Bootstrap Job did not prove completion for the reviewed immutable image.' }
    $bootstrapReceipt = Read-BootstrapJobReceipt $jobName
    $bootstrapReceiptSha256 = $bootstrapReceipt.Sha256
    $migration = 'BOOTSTRAP_V2_V8_VERIFIED'
}
# Strategic merge retains platform probes, resources, secret refs and other env entries.
Invoke-ReleaseKubectl @('patch', 'deployment', 'oficina-app', '--type=strategic', '--patch-file', (Join-Path $OutputDirectory 'rollout-patch.json')) | Out-Null
Invoke-ReleaseKubectl @('rollout', 'status', 'deployment/oficina-app', '--timeout=300s') | Out-Null
if ($release.mode -ceq 'FirstWriter') { Invoke-ReleaseKubectl @('apply', '-f', (Join-Path $OutputDirectory 'hpa.json')) | Out-Null }
$targetImage = if ($release.mode -ceq 'Rollback') { $release.rollback.image } else { $release.image }
@{schemaVersion=1; environment=$release.environment; sourceCommit=$release.sourceCommit; releaseSha256=$ExpectedReleaseSha256;
    image=$targetImage; databaseSchemaVersion='V8'; contractVersion='phase3-v2'; migration=$migration; bootstrapReceiptSha256=$bootstrapReceiptSha256;
    runtimePublicConfigMapSha256=$(if($initializeStaging){$release.runtimePublicConfigMapSha256}else{$null});
    startedAt=$startedAt.ToString('o'); completedAt=[DateTimeOffset]::UtcNow.ToString('o'); status='ROLLOUT_COMPLETE'
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'rollout-receipt.json')
Write-Output 'ROLLOUT_COMPLETE: local receipt written; no promotion or publication implied.'
} finally {
    if ($lockAcquired) {
        & (Join-Path $PSScriptRoot 'deployment-lock.ps1') -Action Release -StateBucket $StateBucket -OwnerToken $owner | Out-Null
    }
}
