<#
.SYNOPSIS
  Disposable Phase 4 Kind cluster (oficina-phase4): lifecycle and health. No AWS resources, ever.
.PARAMETER Action
  Up      create cluster (if absent), namespaces, Postgres OS/billing, LocalStack; wait until ready
  Verify  assert cluster, pods, LocalStack health, SQS/DynamoDB contract (run phase4-kind-init.ps1 first)
  PortForwardCheck  start a port-forward bound to 127.0.0.1, prove the listener is loopback-only, stop it
  Down    delete ONLY the oficina-phase4 cluster and prove it is gone
#>
[CmdletBinding()]
param([Parameter(Mandatory)][ValidateSet('Up', 'Verify', 'PortForwardCheck', 'Down')][string]$Action)
. (Join-Path $PSScriptRoot 'phase4-kind.common.ps1')

function Wait-Rollout {
    param([string]$Namespace, [string]$Name)
    Invoke-Kube @('rollout', 'status', "deploy/$Name", '-n', $Namespace, '--timeout=300s') | Out-Null
    Write-Host "ready: $Namespace/$Name"
}

function Invoke-Up {
    $clusters = (Invoke-Native kind @('get', 'clusters') -AllowFailure).Output -split "`n"
    if ($clusters -contains $script:ClusterName) { Write-Host "cluster $($script:ClusterName): exists" }
    else {
        Invoke-Native kind @('create', 'cluster', '--config', (Join-Path $script:KindDir 'kind-config.yaml'), '--wait', '120s') | Out-Null
        Write-Host "cluster $($script:ClusterName): created"
    }
    Invoke-Kube @('apply', '-f', (Join-Path $script:KindDir 'manifests/namespaces.yaml')) | Out-Null
    foreach ($svc in 'os', 'billing') {
        $exists = (Invoke-Kube @('get', 'secret', "postgres-$svc-admin", '-n', 'data') -AllowFailure).ExitCode -eq 0
        if (-not $exists) {
            Invoke-Kube @('create', 'secret', 'generic', "postgres-$svc-admin", '-n', 'data', "--from-literal=password=$(New-RandomSecret)") | Out-Null
        }
    }
    foreach ($m in 'postgres-os', 'postgres-billing', 'localstack') {
        Invoke-Kube @('apply', '-f', (Join-Path $script:KindDir "manifests/$m.yaml")) | Out-Null
    }
    Wait-Rollout data postgres-os
    Wait-Rollout data postgres-billing
    Wait-Rollout infra localstack
}

function Invoke-Verify {
    Assert-KindCluster
    $fail = 0
    $ctx = (Invoke-Native kubectl @('config', 'current-context') -AllowFailure).Output
    Write-Host "kubectl current-context (untouched): $ctx"
    foreach ($d in @(@('data', 'postgres-os'), @('data', 'postgres-billing'), @('infra', 'localstack'))) {
        $ready = (Invoke-Kube @('get', 'deploy', $d[1], '-n', $d[0], '-o', 'jsonpath={.status.readyReplicas}')).Output
        if ($ready -eq '1') { Write-Host "PASS ready $($d[0])/$($d[1])" } else { Write-Host "FAIL ready $($d[0])/$($d[1]) readyReplicas=$ready"; $fail = 1 }
    }
    foreach ($svc in @(@('os', 'oficina'), @('billing', 'billing'))) {
        $r = Invoke-Psql -Instance $svc[0] -Database $svc[1] -Sql "SELECT current_database() || ' pg=' || current_setting('server_version')"
        Write-Host "PASS postgres-$($svc[0]) $($r.Output)"
    }
    $health = (Invoke-Kube @('exec', '-n', 'infra', 'deploy/localstack', '--', 'curl', '-sf', 'http://localhost:4566/_localstack/health')).Output
    foreach ($s in 'sqs', 'dynamodb') {
        if ($health -match "`"$s`": `"(available|running)`"") { Write-Host "PASS localstack $s=$($Matches[1])" } else { Write-Host "FAIL localstack $s"; $fail = 1 }
    }
    $verifySh = (Get-Content -Raw (Join-Path $script:KindDir 'localstack/verify.sh')) -replace "`r", ''
    $v = Invoke-Kube @('exec', '-i', '-n', 'infra', 'deploy/localstack', '--', 'sh', '-s') -StdIn $verifySh -AllowFailure
    Write-Host $v.Output
    if ($v.ExitCode -ne 0) { $fail = 1 }
    if ($fail) { throw 'Verify FAILED' }
    Write-Host 'Verify: OK'
}

function Invoke-PortForwardCheck {
    Assert-KindCluster
    $port = 18566
    $pfArgs = @('--context', $script:KubeContext, 'port-forward', '--address', '127.0.0.1', '-n', 'infra', 'svc/localstack', "${port}:4566")
    $log = Join-Path ([IO.Path]::GetTempPath()) "p4-portforward-$PID.log"
    $proc = Start-Process kubectl -ArgumentList $pfArgs -PassThru -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    try {
        $deadline = (Get-Date).AddSeconds(30)
        $listeners = @()
        while ((Get-Date) -lt $deadline -and -not $listeners) {
            Start-Sleep -Milliseconds 500
            $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
        }
        if (-not $listeners) { throw "port-forward did not listen on $port" }
        $bad = @($listeners | Where-Object { -not (Test-LoopbackAddress $_.LocalAddress) })
        if ($bad) { throw "port-forward listens on non-loopback: $($bad.LocalAddress -join ',')" }
        Write-Host "PASS listener(s): $(($listeners.LocalAddress | Sort-Object -Unique) -join ',') port $port (loopback only)"
        $h = Invoke-RestMethod "http://127.0.0.1:$port/_localstack/health"
        Write-Host "PASS health via loopback forward: sqs=$($h.services.sqs) dynamodb=$($h.services.dynamodb)"
    }
    finally {
        if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
        Remove-Item $log, "$log.err" -ErrorAction SilentlyContinue
    }
}

function Invoke-Down {
    # Deletes only the named disposable cluster; never touches other kind clusters.
    Invoke-Native kind @('delete', 'cluster', '--name', $script:ClusterName) | Out-Null
    $left = (Invoke-Native kind @('get', 'clusters') -AllowFailure).Output -split "`n"
    if ($left -contains $script:ClusterName) { throw "cluster $($script:ClusterName) still present" }
    Write-Host "cluster $($script:ClusterName): deleted"
}

switch ($Action) {
    'Up' { Invoke-Up }
    'Verify' { Invoke-Verify }
    'PortForwardCheck' { Invoke-PortForwardCheck }
    'Down' { Invoke-Down }
}
