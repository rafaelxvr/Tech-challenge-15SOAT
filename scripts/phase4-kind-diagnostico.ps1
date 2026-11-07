<#
.SYNOPSIS
  Coleta o diagnóstico do cluster Kind em falha e grava diagnostico.txt já sanitizado (Hide-Secret).
.DESCRIPTION
  A coleta acumula em memória; o caminho final só recebe texto sanitizado (ver Write-SanitizedDiagnostico).
  Uma seção que falha vira uma nota e a coleta continua. Logs limitados (--tail=200).
#>
[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputPath)
. (Join-Path $PSScriptRoot 'phase4-kind.common.ps1')

$ctx = $script:KubeContext
$secoes = @(
    @{ Titulo = 'processos'; Coletar = { & ps -eo pid,etime,args 2>&1 | Select-String 'kubectl|pwsh' | ForEach-Object { $_.Line } } }
)
foreach ($ns in 'oficina', 'oficina-billing', 'oficina-execution', 'data', 'infra') {
    $secoes += @{ Titulo = "pods $ns"; Coletar = { & kubectl --context $ctx -n $ns get pods -o wide 2>&1 | ForEach-Object { "$_" } }.GetNewClosure() }
}
foreach ($d in @(@('oficina', 'oficina-os-service'), @('oficina-billing', 'oficina-billing'), @('oficina-execution', 'oficina-execution'))) {
    $secoes += @{ Titulo = "logs $($d[1])"; Coletar = { & kubectl --context $ctx -n $d[0] logs "deploy/$($d[1])" --tail=200 2>&1 | ForEach-Object { "$_" } }.GetNewClosure() }
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputPath) | Out-Null
Write-SanitizedDiagnostico -Path $OutputPath -Secoes $secoes
Write-Host "Diagnóstico sanitizado: $OutputPath"
