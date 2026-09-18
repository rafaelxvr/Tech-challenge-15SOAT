[CmdletBinding()]
param([Parameter(Mandatory)][string]$Repository,[Parameter(Mandatory)][string]$Suite,[string]$MavenSettings)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$global:LASTEXITCODE=0
# PATH shims protect native calls without shadowing the suites' own mock tools.
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
