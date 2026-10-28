<#
.SYNOPSIS
  Runner skeleton for the Phase 4 BDD matrix (tests/phase4-kind) against the disposable Kind cluster.
.DESCRIPTION
  Preflight: checks tools, cluster and that the three services are ready. Run: iterates the scenarios of oraculos.json,
  executes the ones that have a handler in $Handlers and records everything else honestly as NAO_EXECUTADO. Report: prints
  the last results file. Results are always tied to the SHAs of the three services (never invented).
  No scenario handler is implemented yet: they are filled in together with the Kind execution (UNR-69).
  Only local endpoints are used; AWS variables are cleared by phase4-kind.common.ps1.
.PARAMETER Action
  Preflight | Run | Report
.PARAMETER OsSha, BillingSha, ExecutionSha
  Revisions that were built and deployed. OsSha defaults to this checkout's HEAD; the other two must be passed.
.PARAMETER Scenario
  Optional list of ids (BDD-01a ...). Default: all.
.PARAMETER OutputDir
  Directory for bdd-results.json. Default: a new directory under the system temp folder (never the repository).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Preflight', 'Run', 'Report')][string]$Action,
    [string]$OsSha,
    [string]$BillingSha,
    [string]$ExecutionSha,
    [string[]]$Scenario,
    [string]$OutputDir
)
. (Join-Path $PSScriptRoot 'phase4-kind.common.ps1')

$kindTests = Join-Path $script:RepoRoot 'tests/phase4-kind'
$services = @(
    @{ Nome = 'os'; Namespace = 'oficina'; Deployment = 'oficina-os-service' },
    @{ Nome = 'billing'; Namespace = 'oficina-billing'; Deployment = 'oficina-billing' },
    @{ Nome = 'execution'; Namespace = 'oficina-execution'; Deployment = 'oficina-execution' }
)

# id => scriptblock returning @{ Status = 'PASS'|'FAIL'; Detalhe = '...' }. Intentionally empty in this skeleton.
$Handlers = @{}
. (Join-Path $PSScriptRoot 'phase4-kind-bdd.support.ps1')
. (Join-Path $PSScriptRoot 'phase4-kind-bdd.handlers.ps1')
. (Join-Path $PSScriptRoot 'phase4-kind-bdd.handlers.pos-pagamento.ps1')
. (Join-Path $PSScriptRoot 'phase4-kind-bdd.handlers.scheduler.ps1')
. (Join-Path $PSScriptRoot 'phase4-kind-bdd.handlers.scheduler-parado.ps1')

function Get-Oraculos { Get-Content -Raw (Join-Path $kindTests 'oraculos.json') | ConvertFrom-Json }

function Invoke-Preflight {
    foreach ($tool in 'kind', 'kubectl', 'docker') {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Ferramenta ausente: $tool" }
    }
    Assert-KindCluster
    foreach ($s in $services) {
        $r = Invoke-Kube @('-n', $s.Namespace, 'get', 'deploy', $s.Deployment, '-o', 'jsonpath={.status.readyReplicas}') -AllowFailure
        if ($r.ExitCode -ne 0 -or [int]('0' + $r.Output) -lt 1) { throw "Serviço '$($s.Nome)' não está pronto ($($s.Namespace)/$($s.Deployment))" }
        Write-Host "PASS pronto: $($s.Nome)"
    }
    $cfg = (Invoke-Kube @('-n', 'oficina', 'get', 'configmap', 'oficina-os-service-config', '-o', 'jsonpath={.data.SAGA_ENABLED}/{.data.SAGA_MENSAGERIA_ENABLED}') -AllowFailure).Output
    if ($cfg -ne 'true/true') { throw "SAGA_ENABLED/SAGA_MENSAGERIA_ENABLED devem ser true/true no perfil Kind (observado: $cfg)" }
    Write-Host 'PASS preflight'
}

function Invoke-Run {
    $oraculos = Get-Oraculos
    $osSha = if ($OsSha) { $OsSha } else { (Invoke-Native git @('-C', $script:RepoRoot, 'rev-parse', 'HEAD')).Output.Trim() }
    $faltam = @()
    if (-not $BillingSha) { $faltam += 'BillingSha' }
    if (-not $ExecutionSha) { $faltam += 'ExecutionSha' }
    if ($faltam) { throw "Informe $($faltam -join ', '): resultados só valem vinculados ao SHA real que foi implantado." }
    $dir = if ($OutputDir) { $OutputDir } else { Join-Path ([IO.Path]::GetTempPath()) ("phase4-bdd-" + [guid]::NewGuid().ToString('N')) }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $resultados = @()
    try {
        Set-HarnessCustomerKey   # reinicia o os-service: antes do port-forward
        Start-Forwards
        foreach ($c in $oraculos.cenarios) {
            if ($Scenario -and $c.id -notin $Scenario) { continue }
            if ($c.modo -eq 'coberto-por-unitario') {
                $resultados += [pscustomobject]@{ id = $c.id; status = 'COBERTO_POR_UNITARIO'; detalhe = ($c.referenciaUnitaria -join ', ') }
            } elseif ($Handlers.ContainsKey($c.id)) {
                try { $o = & $Handlers[$c.id] $c $oraculos } catch { $o = @{ Status = 'FAIL'; Detalhe = (Hide-Secret "$_") } }
                $resultados += [pscustomobject]@{ id = $c.id; status = $o.Status; detalhe = $o.Detalhe }
            } else {
                $resultados += [pscustomobject]@{ id = $c.id; status = 'NAO_EXECUTADO'; detalhe = 'handler pendente (UNR-69)' }
            }
        }
    }
    finally { Stop-Forwards }
    $resumo = $resultados | Group-Object status | ForEach-Object { [pscustomobject]@{ status = $_.Name; total = $_.Count } }
    $saida = [ordered]@{
        executadoEm = (Get-Date).ToUniversalTime().ToString('o')
        cluster = $script:ClusterName
        revisoes = [ordered]@{ os = $osSha; billing = $BillingSha; execution = $ExecutionSha }
        resumo = $resumo
        resultados = $resultados
    }
    $arquivo = Join-Path $dir 'bdd-results.json'
    $saida | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 $arquivo
    Write-Host "Resultados: $arquivo"
    $resumo | Format-Table | Out-String | Write-Host
    if (@($resultados | Where-Object status -eq 'FAIL').Count -gt 0) { exit 1 }
    if (@($resultados | Where-Object status -eq 'NAO_EXECUTADO').Count -gt 0) { exit 2 }
}

switch ($Action) {
    'Preflight' { Invoke-Preflight }
    'Run' { Invoke-Preflight; Invoke-Run }
    'Report' {
        if (-not $OutputDir) { throw 'Informe -OutputDir com o diretório do bdd-results.json' }
        Get-Content -Raw (Join-Path $OutputDir 'bdd-results.json')
    }
}
