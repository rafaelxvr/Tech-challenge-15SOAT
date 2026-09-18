[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ReleaseFile,
    [Parameter(Mandatory)][string]$ExpectedReleaseSha256,
    [Parameter(Mandatory)][string]$PlatformInputsFile,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [switch]$ExecuteReviewedPlan,
    [ValidateRange(1, 600)][int]$DrainTimeoutSeconds = 120
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'app-release-contract.ps1')
$contract = Read-AppRelease $ReleaseFile $ExpectedReleaseSha256 $PlatformInputsFile
$release = $contract.Release
& (Join-Path $PSScriptRoot 'render-app-release.ps1') -ReleaseFile $ReleaseFile -ExpectedReleaseSha256 $ExpectedReleaseSha256 -PlatformInputsFile $PlatformInputsFile -OutputDirectory $OutputDirectory | Out-Null
if (-not $ExecuteReviewedPlan) { Write-Output 'RENDERED_ONLY: no cluster operations performed.'; return }
@{schemaVersion=1; releaseSha256=$ExpectedReleaseSha256; status='IN_PROGRESS'} | ConvertTo-Json |
    Set-Content -LiteralPath (Join-Path $OutputDirectory 'rollout-receipt.json')

# The future release adapter must hold its deployment lock throughout this operation.
# Every invocation pins its context/namespace; never trust the operator's current context.
function Invoke-ReleaseKubectl([string[]]$Arguments) {
    $global:LASTEXITCODE = 0
    $result = & kubectl --context $release.kubeContext --namespace "oficina-$($release.environment)" @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "APP release kubectl operation failed: $($Arguments[0]). Inspect restricted executor evidence." }
    return $result
}
function Read-ClusterObject([string]$Kind, [string]$Name) {
    (Invoke-ReleaseKubectl @('get', $Kind, $Name, '-o', 'json')) -join "`n" | ConvertFrom-Json
}
$deployment = Read-ClusterObject 'deployment' 'oficina-app'
$hpa = Read-ClusterObject 'hpa' 'oficina-app'
$serviceAccount = Read-ClusterObject 'serviceaccount' 'oficina-app'
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

$startedAt = [DateTimeOffset]::UtcNow
$migration = 'NOT_RUN_ROLLBACK'
if ($release.mode -ceq 'FirstWriter') {
    Invoke-ReleaseKubectl @('delete', 'hpa', 'oficina-app', '--wait=true') | Out-Null
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
    Invoke-ReleaseKubectl @('create', '-f', (Join-Path $OutputDirectory 'migration-config.json')) | Out-Null
    Invoke-ReleaseKubectl @('create', '-f', (Join-Path $OutputDirectory 'migration-job.json')) | Out-Null
    $jobName = 'oficina-migrate-' + $ExpectedReleaseSha256.Substring(0, 12)
    Invoke-ReleaseKubectl @('wait', "job/$jobName", '--for=condition=complete', '--timeout=660s') | Out-Null
    $job = Read-ClusterObject 'job' $jobName
    if (@($job.status.conditions | Where-Object { $_.type -ceq 'Complete' -and $_.status -ceq 'True' }).Count -ne 1 -or
        @($job.status.conditions | Where-Object { $_.type -ceq 'Failed' -and $_.status -ceq 'True' }).Count -gt 0 -or
        $job.spec.template.spec.containers[0].image -cne $release.migrationImage) { throw 'Migration Job did not prove completion for the reviewed image.' }
    $migration = 'COMPLETE'
}
# Strategic merge retains platform probes, resources, secret refs and other env entries.
Invoke-ReleaseKubectl @('patch', 'deployment', 'oficina-app', '--type=strategic', '--patch-file', (Join-Path $OutputDirectory 'rollout-patch.json')) | Out-Null
Invoke-ReleaseKubectl @('rollout', 'status', 'deployment/oficina-app', '--timeout=300s') | Out-Null
if ($release.mode -ceq 'FirstWriter') { Invoke-ReleaseKubectl @('apply', '-f', (Join-Path $OutputDirectory 'hpa.json')) | Out-Null }
$targetImage = if ($release.mode -ceq 'Rollback') { $release.rollback.image } else { $release.image }
@{schemaVersion=1; environment=$release.environment; sourceCommit=$release.sourceCommit; releaseSha256=$ExpectedReleaseSha256;
    image=$targetImage; databaseSchemaVersion='V8'; contractVersion='phase3-v2'; migration=$migration;
    startedAt=$startedAt.ToString('o'); completedAt=[DateTimeOffset]::UtcNow.ToString('o'); status='ROLLOUT_COMPLETE'
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'rollout-receipt.json')
Write-Output 'ROLLOUT_COMPLETE: local receipt written; no promotion or publication implied.'
