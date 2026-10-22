<#
.SYNOPSIS
  Idempotent bootstrap of the Phase 4 local dependencies: Postgres roles (migrator/runtime split,
  random per-cluster passwords kept only in Kubernetes Secrets) and LocalStack SQS FIFO/DLQs + DynamoDB tables.
#>
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'phase4-kind.common.ps1')
Assert-KindCluster

$instances = @(
    @{ Instance = 'os'; Database = 'oficina'; Migrator = 'oficina_migrator'; App = 'oficina_app' },
    @{ Instance = 'billing'; Database = 'billing'; Migrator = 'billing_migrator'; App = 'billing_app' }
)
$rolesSql = Get-Content -Raw (Join-Path $script:KindDir 'postgres/init-roles.sql')

foreach ($i in $instances) {
    $secretName = "postgres-$($i.Instance)-roles"
    $exists = (Invoke-Kube @('get', 'secret', $secretName, '-n', 'data') -AllowFailure).ExitCode -eq 0
    if (-not $exists) {
        Set-KubeSecret data $secretName @{ migrator_password = (New-RandomSecret); app_password = (New-RandomSecret) }
    }
    $vars = @{
        db          = $i.Database
        migrator    = $i.Migrator
        app         = $i.App
        migrator_pw = (Get-KubeSecretValue data $secretName migrator_password)
        app_pw      = (Get-KubeSecretValue data $secretName app_password)
    }
    Invoke-Psql -Instance $i.Instance -Database $i.Database -Sql $rolesSql -Variables $vars | Out-Null
    # Runtime/migration roles must not wander into the maintenance database.
    Invoke-Psql -Instance $i.Instance -Database $i.Database -Sql 'REVOKE ALL ON DATABASE postgres FROM PUBLIC;' | Out-Null
    Write-Host "postgres-$($i.Instance): roles $($i.Migrator)/$($i.App) ready on database $($i.Database)"
}

$initSh = (Get-Content -Raw (Join-Path $script:KindDir 'localstack/init.sh')) -replace "`r", ''
$r = Invoke-Kube @('exec', '-i', '-n', 'infra', 'deploy/localstack', '--', 'sh', '-s') -StdIn $initSh
Write-Host $r.Output
