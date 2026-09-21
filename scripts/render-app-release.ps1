[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ReleaseFile,
    [Parameter(Mandatory)][string]$ExpectedReleaseSha256,
    [Parameter(Mandatory)][string]$PlatformInputsFile,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'app-release-contract.ps1')
$inputContract = Read-AppRelease $ReleaseFile $ExpectedReleaseSha256 $PlatformInputsFile
$release = $inputContract.Release; $platform = $inputContract.Platform
$namespace = "oficina-$($release.environment)"
$name = 'oficina-migrate-' + $ExpectedReleaseSha256.Substring(0, 12)
$metadata = @{ name=$name; namespace=$namespace; annotations=@{'oficina.io/release-sha256'=$ExpectedReleaseSha256; 'oficina.io/source-commit'=$release.sourceCommit} }
$account = [regex]::Match($release.image, '\A([0-9]{12})\.dkr\.ecr\.us-east-1\.amazonaws\.com/').Groups[1].Value
$bootstrap = if ($release.mode -cne 'Rollback') { Read-BootstrapReview $release $account } else { $null }
$reviewName = 'oficina-bootstrap-review-' + $ExpectedReleaseSha256.Substring(0, 12)
$reviewMetadata = @{ name=$reviewName; namespace=$namespace; annotations=@{'oficina.io/release-sha256'=$ExpectedReleaseSha256; 'oficina.io/source-commit'=$release.sourceCommit} }
$reviewConfigMap = if ($null -ne $bootstrap) {
    @{apiVersion='v1'; kind='ConfigMap'; metadata=$reviewMetadata; immutable=$true; data=@{'review.json'=$bootstrap.Json}}
} else { $null }
$bootstrapMounts = @(
    @{name='review'; mountPath='/work/review.json'; subPath='review.json'; readOnly=$true},
    @{name='public'; mountPath='/etc/oficina/public'; readOnly=$true},
    @{name='work'; mountPath='/work'}, @{name='tmp'; mountPath='/tmp'}
)
$job = @{apiVersion='batch/v1'; kind='Job'; metadata=$metadata; spec=@{
    backoffLimit=0; activeDeadlineSeconds=600; template=@{metadata=@{labels=@{'app.kubernetes.io/name'='oficina-migration'}}; spec=@{
        restartPolicy='Never'; serviceAccountName=$release.migrationServiceAccount; automountServiceAccountToken=$false
        securityContext=@{runAsNonRoot=$true; runAsUser=10001; runAsGroup=10001; seccompProfile=@{type='RuntimeDefault'}}
        containers=@(@{name='bootstrap'; image=$(if($null -ne $bootstrap){$release.bootstrapImage}else{$release.migrationImage}); args=$(if($null -ne $bootstrap){@('/work/review.json',$bootstrap.Sha256,'/etc/oficina/public/rds-ca.pem','/work/bootstrap-receipt.json')}else{@('migrate')}); env=$(if($null -ne $bootstrap){@(@{name='AWS_REGION';value='us-east-1'})}else{@()})
            securityContext=@{allowPrivilegeEscalation=$false; readOnlyRootFilesystem=$true; capabilities=@{drop=@('ALL')}}
            resources=@{requests=@{cpu='250m'; memory='256Mi'}; limits=@{cpu='1'; memory='512Mi'}}
            volumeMounts=$bootstrapMounts})
        volumes=@(@{name='review'; configMap=@{name=$reviewName; items=@(@{key='review.json'; path='review.json'})}}, @{name='public'; configMap=@{name="oficina-runtime-public-$($release.environment)"; items=@(@{key='rds-ca.pem'; path='rds-ca.pem'})}}, @{name='work'; emptyDir=@{}}, @{name='tmp'; emptyDir=@{}})
    }}
}}
$replicas = if ($release.environment -ceq 'production') { 2 } else { 1 }
$maximum = $replicas * 2
$hpa = @{apiVersion='autoscaling/v2'; kind='HorizontalPodAutoscaler'; metadata=@{name='oficina-app'; namespace=$namespace}; spec=@{scaleTargetRef=@{apiVersion='apps/v1'; kind='Deployment'; name='oficina-app'}; minReplicas=$replicas; maxReplicas=$maximum; metrics=@(@{type='Resource'; resource=@{name='cpu'; target=@{type='Utilization'; averageUtilization=60}}})}}
$targetImage = if ($release.mode -ceq 'Rollback') { $release.rollback.image } else { $release.image }
$strategy = if ($release.mode -ceq 'FirstWriter') { @{type='Recreate'; rollingUpdate=$null} } else { @{type='RollingUpdate'; rollingUpdate=@{maxSurge=0; maxUnavailable=1}} }
$rollout = @{spec=@{replicas=$replicas; strategy=$strategy; template=@{metadata=@{annotations=@{'oficina.io/release-sha256'=$ExpectedReleaseSha256; 'oficina.io/schema-version'='V8'; 'oficina.io/security-contract'='phase3-v2'}}; spec=@{containers=@(@{name='app'; image=$targetImage; env=@(@{name='SPRING_FLYWAY_ENABLED'; value='false'}, @{name='SPRING_JPA_HIBERNATE_DDL_AUTO'; value='validate'})})}}}}
$drain = @{spec=@{replicas=0; strategy=@{type='Recreate'; rollingUpdate=$null}}}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$entries = @(@{Name='hpa.json'; Value=$hpa}, @{Name='rollout-patch.json'; Value=$rollout}, @{Name='drain-patch.json'; Value=$drain})
if ($null -ne $bootstrap) {
    $entries = @(@{Name='bootstrap-review.json'; Value=$reviewConfigMap}, @{Name='migration-job.json'; Value=$job}) + $entries
}
foreach ($entry in $entries) {
    $entry.Value | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputDirectory $entry.Name) -NoNewline
}
Write-Output ([IO.Path]::GetFullPath($OutputDirectory))
