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
$sql = [ordered]@{}
Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '../src/main/resources/db/migration') -Filter '*.sql' -File | Sort-Object Name | ForEach-Object { $sql[$_.Name] = [IO.File]::ReadAllText($_.FullName) }
$metadata = @{ name=$name; namespace=$namespace; annotations=@{'oficina.io/release-sha256'=$ExpectedReleaseSha256; 'oficina.io/source-commit'=$release.sourceCommit} }
$configMap = @{apiVersion='v1'; kind='ConfigMap'; metadata=$metadata; immutable=$true; data=$sql}
$envs = @(
    @{name='FLYWAY_URL'; value="jdbc:postgresql://$($platform.DbHost):5432/oficina?sslmode=verify-full&sslrootcert=/etc/oficina/public/rds-ca.pem"},
    @{name='FLYWAY_USER'; valueFrom=@{secretKeyRef=@{name=$release.migrationSecretName; key='username'}}},
    @{name='FLYWAY_PASSWORD'; valueFrom=@{secretKeyRef=@{name=$release.migrationSecretName; key='password'}}},
    @{name='FLYWAY_LOCATIONS'; value='filesystem:/flyway/sql'}, @{name='FLYWAY_TARGET'; value='8'},
    @{name='FLYWAY_CLEAN_DISABLED'; value='true'}, @{name='FLYWAY_BASELINE_ON_MIGRATE'; value='false'},
    @{name='FLYWAY_OUT_OF_ORDER'; value='false'}, @{name='FLYWAY_VALIDATE_ON_MIGRATE'; value='true'},
    @{name='FLYWAY_CONNECT_RETRIES'; value='0'}
)
$job = @{apiVersion='batch/v1'; kind='Job'; metadata=$metadata; spec=@{
    backoffLimit=0; activeDeadlineSeconds=600; template=@{metadata=@{labels=@{'app.kubernetes.io/name'='oficina-migration'}}; spec=@{
        restartPolicy='Never'; serviceAccountName=$release.migrationServiceAccount; automountServiceAccountToken=$false
        securityContext=@{runAsNonRoot=$true; seccompProfile=@{type='RuntimeDefault'}}
        containers=@(@{name='migrate'; image=$release.migrationImage; args=@('migrate'); env=$envs
            securityContext=@{allowPrivilegeEscalation=$false; readOnlyRootFilesystem=$true; capabilities=@{drop=@('ALL')}}
            resources=@{requests=@{cpu='250m'; memory='256Mi'}; limits=@{cpu='1'; memory='512Mi'}}
            volumeMounts=@(@{name='sql'; mountPath='/flyway/sql'; readOnly=$true}, @{name='public'; mountPath='/etc/oficina/public'; readOnly=$true}, @{name='tmp'; mountPath='/tmp'})})
        volumes=@(@{name='sql'; configMap=@{name=$name}}, @{name='public'; configMap=@{name="oficina-runtime-public-$($release.environment)"; items=@(@{key='rds-ca.pem'; path='rds-ca.pem'})}}, @{name='tmp'; emptyDir=@{}})
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
foreach ($entry in @(@{Name='migration-config.json'; Value=$configMap}, @{Name='migration-job.json'; Value=$job}, @{Name='hpa.json'; Value=$hpa}, @{Name='rollout-patch.json'; Value=$rollout}, @{Name='drain-patch.json'; Value=$drain})) {
    $entry.Value | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputDirectory $entry.Name) -NoNewline
}
Write-Output ([IO.Path]::GetFullPath($OutputDirectory))
