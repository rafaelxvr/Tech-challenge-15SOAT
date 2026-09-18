Set-StrictMode -Version Latest

function Assert-StagingExecutorInputs([object]$Release, [string]$PlatformInputsFile, [string]$StagingWorkloadFile, [string]$CloudWindowEvidenceFile) {
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
}
