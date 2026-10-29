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
  Full 40-hex revisions that were built and deployed. OsSha defaults to this checkout's HEAD; the other two must be passed.
  Each one is verified against its deployment (pod template annotation phase4.oficina/source-sha or image tag equal to the
  SHA); a mismatch aborts the run. The running image digests are recorded in bdd-results.json.
.PARAMETER Scenario
  Optional list of ids (BDD-01a ...). Default: all. An unknown id or an empty selection aborts with a non-zero exit code.
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
. (Join-Path $PSScriptRoot 'phase4-kind-bdd.handlers.falhas.ps1')
. (Join-Path $PSScriptRoot 'phase4-kind-bdd.handlers.injecao.ps1')
. (Join-Path $PSScriptRoot 'phase4-kind-bdd.handlers.dlq.ps1')

$script:ResolvedOsSha = if ($OsSha) { $OsSha } else { (Invoke-Native git @('-C', $script:RepoRoot, 'rev-parse', 'HEAD')).Output.Trim() }

function Get-Oraculos { Get-Content -Raw (Join-Path $kindTests 'oraculos.json') | ConvertFrom-Json }

# Effective value of an env var in the first container: literal value or, for valueFrom, the referenced ConfigMap/Secret key
# (Secret values are only compared, never printed). Empty string when absent or unresolved.
function Get-EffectiveEnvValue {
    param([string]$Deployment, [string]$Namespace, [string]$Name)
    $json = (Invoke-Kube @('-n', $Namespace, 'get', 'deploy', $Deployment, '-o', 'json')).Output | ConvertFrom-Json
    $container = $json.spec.template.spec.containers[0]
    $entry = @($container.env | Where-Object { $_.name -eq $Name }) | Select-Object -Last 1
    if ($entry) {
        if ($entry.PSObject.Properties['value']) { return "$($entry.value)".Trim().ToLowerInvariant() }
        $ref = $entry.valueFrom
        if ($ref.configMapKeyRef) { return Get-KubeRefValue 'configmap' $Namespace $ref.configMapKeyRef.name $ref.configMapKeyRef.key }
        if ($ref.secretKeyRef) { return Get-KubeRefValue 'secret' $Namespace $ref.secretKeyRef.name $ref.secretKeyRef.key }
        return ''
    }
    foreach ($from in @($container.envFrom)) {
        if (-not $from) { continue }
        $kind = if ($from.configMapRef) { 'configmap' } elseif ($from.secretRef) { 'secret' } else { continue }
        $refName = if ($from.configMapRef) { $from.configMapRef.name } else { $from.secretRef.name }
        $v = Get-KubeRefValue $kind $Namespace $refName $Name
        if ($v) { return $v }
    }
    ''
}

function Get-KubeRefValue {
    param([string]$Kind, [string]$Namespace, [string]$Name, [string]$Key)
    $r = Invoke-Kube @('-n', $Namespace, 'get', $Kind, $Name, '-o', "jsonpath={.data.$($Key -replace '\.', '\.')}") -AllowFailure
    if ($r.ExitCode -ne 0 -or -not $r.Output) { return '' }
    $text = if ($Kind -eq 'secret') { try { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($r.Output)) } catch { '' } } else { $r.Output }
    $text.Trim().ToLowerInvariant()
}

# The SHA must be the full 40-hex commit and must match what the deployment actually runs: the pod template annotation
# phase4.oficina/source-sha or an image tag equal to the SHA. The running image digest is returned for the evidence.
function Assert-DeployedRevision {
    param($Service, [string]$Sha)
    $d = (Invoke-Kube @('-n', $Service.Namespace, 'get', 'deploy', $Service.Deployment, '-o', 'json')).Output | ConvertFrom-Json
    $anotacao = $d.spec.template.metadata.annotations.'phase4.oficina/source-sha'
    $imagem = "$($d.spec.template.spec.containers[0].image)"
    $porImagem = $imagem -match ":$Sha(@|$)"
    if ("$anotacao" -ne $Sha -and -not $porImagem) {
        throw "SHA informado para '$($Service.Nome)' ($Sha) não corresponde ao implantado (anotação phase4.oficina/source-sha='$anotacao', imagem='$imagem')."
    }
    $imageId = (Invoke-Kube @('-n', $Service.Namespace, 'get', 'pods', '-l', "app=$($Service.Deployment)", '-o', 'jsonpath={.items[0].status.containerStatuses[0].imageID}') -AllowFailure).Output
    "$imageId"
}

function Assert-RunArguments {
    param($Oraculos)
    $shas = [ordered]@{ OsSha = $script:ResolvedOsSha; BillingSha = $BillingSha; ExecutionSha = $ExecutionSha }
    foreach ($k in $shas.Keys) {
        if (-not $shas[$k]) { throw "Informe -$k`: resultados só valem vinculados ao SHA real que foi implantado." }
        if ($shas[$k] -cnotmatch '^[0-9a-f]{40}$') { throw "-$k deve ser o SHA completo (40 hex minúsculos)." }
    }
    if ($null -ne $Scenario) {
        $ids = @($Oraculos.cenarios | ForEach-Object { $_.id })
        $invalidos = @($Scenario | Where-Object { $_ -cnotin $ids })
        if ($invalidos.Count -gt 0 -or @($Scenario).Count -eq 0) { throw "Cenário(s) desconhecido(s): $($invalidos -join ', '). Ids válidos: $($ids -join ', ')" }
    }
}

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
    foreach ($flag in 'SAGA_ENABLED', 'SAGA_MENSAGERIA_ENABLED') {
        $valor = Get-EffectiveEnvValue -Deployment 'oficina-app' -Namespace 'oficina' -Name $flag
        if ($valor -ne 'true') { throw "$flag deve valer 'true' no os-service do Kind (D17-R: ligar só no perfil Kind); valor efetivo: '$valor'" }
        Write-Host "PASS $flag=true"
    }
    Write-Host 'PASS preflight'
}

function Invoke-Run {
    $oraculos = Get-Oraculos
    $osSha = $script:ResolvedOsSha
    $digests = [ordered]@{}
    foreach ($s in $services) {
        $sha = switch ($s.Nome) { 'os' { $osSha } 'billing' { $BillingSha } 'execution' { $ExecutionSha } }
        $digests[$s.Nome] = Assert-DeployedRevision $s $sha
    }
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
    if (@($resultados).Count -eq 0) { throw 'Nenhum cenário selecionado: nada foi executado.' }
    $resumo = $resultados | Group-Object status | ForEach-Object { [pscustomobject]@{ status = $_.Name; total = $_.Count } }
    $saida = [ordered]@{
        executadoEm = (Get-Date).ToUniversalTime().ToString('o')
        cluster = $script:ClusterName
        revisoes = [ordered]@{ os = $osSha; billing = $BillingSha; execution = $ExecutionSha }
        imagensImplantadas = $digests
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
    'Run' { Assert-RunArguments (Get-Oraculos); Invoke-Preflight; Invoke-Run }
    'Report' {
        if (-not $OutputDir) { throw 'Informe -OutputDir com o diretório do bdd-results.json' }
        Get-Content -Raw (Join-Path $OutputDir 'bdd-results.json')
    }
}
