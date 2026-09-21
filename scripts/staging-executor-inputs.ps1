Set-StrictMode -Version Latest

function Assert-StagingExecutorBaseInputs([object]$Release, [string]$PlatformInputsFile, [string]$StagingWorkloadFile, [string]$CloudWindowEvidenceFile) {
    if ($Release -isnot [pscustomobject] -or $Release.environment -isnot [string] -or
        $Release.environment -cne 'staging' -or $Release.mode -isnot [string] -or $Release.mode -cne 'FirstWriter') {
        throw 'APP_STAGING_INPUTS_INVALID: only the reviewed staging FirstWriter adapter is executable.'
    }
    foreach ($binding in @(
        @{Field='platformInputsSha256';Path=$PlatformInputsFile},
        @{Field='stagingWorkloadSha256';Path=$StagingWorkloadFile},
        @{Field='cloudWindowEvidenceSha256';Path=$CloudWindowEvidenceFile}
    )) {
        $property=$Release.PSObject.Properties[$binding.Field]
        if ($null -eq $property -or $property.Value -isnot [string] -or $property.Value -cnotmatch '\A[a-f0-9]{64}\z' -or
            [string]::IsNullOrWhiteSpace($binding.Path) -or -not (Test-Path -LiteralPath $binding.Path -PathType Leaf)) {
            throw "APP_STAGING_INPUTS_INVALID: missing reviewed $($binding.Field) input."
        }
        if ((Get-FileHash -LiteralPath $binding.Path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $property.Value) {
            throw "APP_STAGING_INPUTS_INVALID: $($binding.Field) digest mismatch."
        }
        try { $document=ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($binding.Path)) -NoEnumerate }
        catch { throw "APP_STAGING_INPUTS_INVALID: $($binding.Field) must bind a JSON object." }
        if ($document -isnot [pscustomobject]) { throw "APP_STAGING_INPUTS_INVALID: $($binding.Field) must bind a JSON object." }
    }
    . (Join-Path $PSScriptRoot 'bootstrap-release-contract.ps1')
    . (Join-Path $PSScriptRoot 'migration-identity-contract.ps1')
    $platform=Get-Content -LiteralPath $PlatformInputsFile -Raw|ConvertFrom-Json
    $null=Read-StagingMigrationIdentity $platform $Release
    . "$PSScriptRoot/app-prerequisites-contract.ps1"
    $null=Read-AppPrerequisites $platform $Release
}
function Assert-StagingExecutorInputs([object]$Release, [string]$PlatformInputsFile, [string]$StagingWorkloadFile, [string]$CloudWindowEvidenceFile, [string]$RuntimePublicConfigMapFile) {
    Assert-StagingExecutorBaseInputs $Release $PlatformInputsFile $StagingWorkloadFile $CloudWindowEvidenceFile
    . (Join-Path $PSScriptRoot 'runtime-public-configmap-contract.ps1')
    $null=Read-StagingPublicConfigMap $RuntimePublicConfigMapFile $Release
}
