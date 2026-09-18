[CmdletBinding()]
param([Parameter(Mandatory)][string]$Repository,[Parameter(Mandatory)][string]$Suite,[string]$MavenSettings)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$global:LASTEXITCODE=0
$guard=Join-Path $PSScriptRoot 'phase3-local-command-guard.ps1'
function global:aws {throw 'LOCAL_ACCEPTANCE_COMMAND_REJECTED: aws'}
function global:kubectl {throw 'LOCAL_ACCEPTANCE_COMMAND_REJECTED: kubectl'}
function global:terraform {
    & (Join-Path $PSHOME $(if($IsWindows){'pwsh.exe'}else{'pwsh'})) -NoProfile -NonInteractive -File $guard -Tool terraform @args
    $global:LASTEXITCODE=$LASTEXITCODE
}
try {
    Set-Location -LiteralPath $Repository
    if($Suite -ceq 'app-focused-java'){
        $wrapper=Join-Path $Repository $(if($IsWindows){'mvnw.cmd'}else{'mvnw'})
        & $wrapper -B -s $MavenSettings -gs $MavenSettings '-Dtest=Phase3ContractTest,TokenTrustTest,ClienteIdentityTest,HealthGroupsTest' test
    } else {
        $allowed=@('tests/pipeline-contract.ps1','tests/verify-infrastructure.ps1','tests/verify.ps1','tests/application-rollout-tests.ps1','tests/platform-manifests-tests.ps1','tests/newrelic-chart-tests.ps1','tests/workload-capacity-tests.ps1','tests/staging-app-workload-tests.ps1','tests/runtime-public-configmap-tests.ps1')
        if($Suite -cnotin $allowed){throw 'LOCAL_ACCEPTANCE_SUITE_NOT_ALLOWED'}
        & (Join-Path $Repository $Suite)
    }
    if($LASTEXITCODE -ne 0){throw "LOCAL_ACCEPTANCE_SUITE_EXIT: $LASTEXITCODE"}
} catch {Write-Error $_;exit 1}
