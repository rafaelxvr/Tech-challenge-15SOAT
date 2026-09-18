Set-StrictMode -Version Latest

function Read-ProductionExecutorInputs {
    param([string]$Enabled, [string]$RuntimeEnabled, [string]$ProtectedEnvironment,
        [string]$InputsFile, [string]$ExpectedInputsSha256, [string]$RoleArn,
        [string]$SourceCommit, [string]$EventName, [string]$BranchRef)
    if ($Enabled -cne 'true') { throw 'APP_PRODUCTION_GATE_DISABLED' }
    if ($RuntimeEnabled -cnotin @('','false','true')) { throw 'APP_PRODUCTION_RUNTIME_GATE_INVALID' }
    if ($ProtectedEnvironment -cne 'production') { throw 'APP_PRODUCTION_ENVIRONMENT_REQUIRED' }
    $review = & (Join-Path $PSScriptRoot 'check-production-promotion.ps1') -Enabled $Enabled -RoleArn $RoleArn `
        -InputsFile $InputsFile -ExpectedInputsSha256 $ExpectedInputsSha256 -SourceCommit $SourceCommit `
        -EventName $EventName -BranchRef $BranchRef -PassThru
    $bucket=$review.Inputs.PSObject.Properties['stateBucket']
    if ($null -eq $bucket -or $bucket.Value -isnot [string] -or $bucket.Value -cnotmatch '\A[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]\z') {
        throw 'APP_PRODUCTION_STATE_BUCKET_REQUIRED'
    }
    $mode=$review.Release.PSObject.Properties['mode']
    if ($null -eq $mode -or $mode.Value -isnot [string] -or $mode.Value -cne 'Compatible') {
        throw 'APP_PRODUCTION_EXISTING_WORKLOAD_REQUIRED: only Compatible V8 rollout is reviewed.'
    }
    foreach ($field in @('bootstrapImage','migrationImage','migrationSqlSha256')) {
        $target=$review.Release.PSObject.Properties[$field]
        $staged=$review.StagingRelease.PSObject.Properties[$field]
        if ($null -eq $target -or $null -eq $staged -or $target.Value -isnot [string] -or
            [string]::IsNullOrWhiteSpace($target.Value) -or $staged.Value -isnot [string] -or $target.Value -cne $staged.Value) {
            throw "APP_PRODUCTION_BOOTSTRAP_NOT_PROMOTED: $field"
        }
    }
    return $review
}
