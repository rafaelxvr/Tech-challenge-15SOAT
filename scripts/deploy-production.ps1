[CmdletBinding()]
param(
    [string]$Enabled='', [string]$RuntimeEnabled='', [string]$ProtectedEnvironment='',
    [string]$InputsFile='', [string]$ExpectedInputsSha256='', [string]$RoleArn='',
    [string]$SourceCommit='', [string]$EventName=$env:GITHUB_EVENT_NAME, [string]$BranchRef=$env:GITHUB_REF,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [switch]$ExecuteReviewedPlan
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'production-executor-inputs.ps1')
$review=Read-ProductionExecutorInputs -Enabled $Enabled -RuntimeEnabled $RuntimeEnabled -ProtectedEnvironment $ProtectedEnvironment `
    -InputsFile $InputsFile -ExpectedInputsSha256 $ExpectedInputsSha256 -RoleArn $RoleArn `
    -SourceCommit $SourceCommit -EventName $EventName -BranchRef $BranchRef
$arguments=@{
    ReleaseFile=$review.ReleaseFile.Path; ExpectedReleaseSha256=$review.ReleaseFile.Sha256
    PlatformInputsFile=$review.Platform.Path; OutputDirectory=$OutputDirectory
    ProductionEnabled=$Enabled; ProductionRuntimeEnabled=$RuntimeEnabled; ProtectedEnvironment=$ProtectedEnvironment
    ProductionInputsFile=$InputsFile; ExpectedProductionInputsSha256=$ExpectedInputsSha256; ProductionRoleArn=$RoleArn
    EventName=$EventName; BranchRef=$BranchRef
}
# Always render/preflight the real rollout entry point. No infrastructure root,
# credentials, role assumption or implicit source/image build is introduced.
& (Join-Path $PSScriptRoot 'deploy-app.ps1') @arguments | Out-Null
if (-not $ExecuteReviewedPlan -or $RuntimeEnabled -cne 'true') {
    Write-Output 'PRODUCTION_RUNTIME_VALIDATED_DEPLOYMENT_DISABLED'
    return
}
& (Join-Path $PSScriptRoot 'deploy-app.ps1') @arguments -ExecuteReviewedPlan
