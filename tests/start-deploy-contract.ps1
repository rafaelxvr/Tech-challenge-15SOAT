[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$temp = Join-Path ([IO.Path]::GetTempPath()) ('oficina-app-launcher-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
$launcherScripts = Join-Path $temp 'launcher/scripts'
New-Item -ItemType Directory -Path $launcherScripts -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repo 'scripts/start-deploy.ps1') -Destination (Join-Path $launcherScripts 'start-deploy.ps1')
$contextMarker = Join-Path $temp 'context-check.txt'
$windowMarker = Join-Path $temp 'window-check.txt'
@"
param([string]`$Environment, [string]`$EventName, [string]`$BranchRef)
Set-Content -LiteralPath '$contextMarker' -Value "`$Environment|`$EventName|`$BranchRef" -NoNewline
"@ | Set-Content -LiteralPath (Join-Path $launcherScripts 'check-workflow-context.ps1') -NoNewline
@"
param([string]`$EvidenceFile, [string]`$Environment)
Set-Content -LiteralPath '$windowMarker' -Value "`$Environment|`$EvidenceFile" -NoNewline
"@ | Set-Content -LiteralPath (Join-Path $launcherScripts 'check-cloud-window.ps1') -NoNewline

function Save-Json([object]$Value, [string]$Path) {
    $Value | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Path -NoNewline
}
function Sha([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Reject([scriptblock]$Action) {
    try { & $Action | Out-Null } catch { return }
    throw 'Expected launcher input rejection.'
}

$global:AwsCalls = [System.Collections.Generic.List[string]]::new()
function global:aws {
    param([Parameter(Position = 0, ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $global:LASTEXITCODE = 0
    $joined = $Arguments -join ' '
    $global:AwsCalls.Add($joined)
    if ($Arguments.Count -ge 3 -and $Arguments[0] -ceq 's3api' -and $Arguments[1] -ceq 'put-object') {
        $keyIndex = [Array]::IndexOf($Arguments, '--key')
        $key = if ($keyIndex -ge 0) { $Arguments[$keyIndex + 1] } else { '' }
        $version = if ($key -match '/promotions/') { 'promotion-version-001' } elseif ($key -match '/manifests/') { 'manifest-version-001' } else { 'source-version-001' }
        return (@{ VersionId = $version } | ConvertTo-Json -Compress)
    }
    if ($Arguments.Count -ge 2 -and $Arguments[0] -ceq 'codebuild' -and $Arguments[1] -ceq 'start-build') {
        return (@{ build = @{ id = 'oficina-phase3-oficina-app-staging-deploy:build-001' } } | ConvertTo-Json -Compress)
    }
    if ($Arguments.Count -ge 2 -and $Arguments[0] -ceq 'codebuild' -and $Arguments[1] -ceq 'batch-get-builds') {
        return (@{ builds = @(@{ buildStatus = 'SUCCEEDED' }) } | ConvertTo-Json -Compress)
    }
    throw "Unexpected AWS command: $joined"
}

try {
    $source = Join-Path $temp 'source.zip'
    'reviewed source bytes' | Set-Content -LiteralPath $source -NoNewline
    $sourceSha = Sha $source
    $commit = 'a' * 40
    $manifestPath = Join-Path $temp 'release-manifest.json'
    Save-Json @{ schemaVersion = 1; environment = 'staging'; sourceCommit = $commit; artifactSha256 = $sourceSha; contractVersion = 'phase3-v2'; migrationVersion = 'V8'; runtimeArtifactDigest = ('sha256:' + ('b' * 64)); promotedFromStaging = $false } $manifestPath
    $manifestSha = Sha $manifestPath
    $windowPath = Join-Path $temp 'cloud-window.json'
    $now = [datetime]::UtcNow
    Save-Json @{ windowStartUtc = $now.AddMinutes(-2).ToString('o'); windowEndUtc = $now.AddMinutes(30).ToString('o'); recordedAtUtc = $now.ToString('o'); accountEvidenceReference = 'offline-fixture'; projectAllowanceUsd = 80; reserveUsd = 20; currentEstimatedSpendUsd = 0 } $windowPath
    $receiptPath = Join-Path $temp 'promotion-receipt.json'
    $launch = @{
        Environment = 'staging'; SourceZip = $source; ExpectedSha256 = $sourceSha; ReleaseManifest = $manifestPath; ExpectedManifestSha256 = $manifestSha
        Bucket = 'oficina-phase3-artifacts-16225b7358'; SourcePrefix = 'releases/app/staging'; ProjectName = 'oficina-phase3-oficina-app-staging-deploy'; SourceCommit = $commit
        CloudWindowEvidenceFile = $windowPath; PromotionEvidenceOutputFile = $receiptPath; EventName = 'push'; BranchRef = 'refs/heads/develop'; TimeoutSeconds = 60
    }

    $dryRun = & (Join-Path $launcherScripts 'start-deploy.ps1') @launch -DryRun
    if (($dryRun | Select-Object -Last 1) -cne 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED') { throw 'Dry run did not validate without calling AWS.' }
    if ($global:AwsCalls.Count -ne 0) { throw 'Dry run called AWS.' }
    if ((Get-Content -LiteralPath $contextMarker -Raw) -cne "staging|push|refs/heads/develop") { throw 'Workflow-context guard was not invoked through the launcher.' }
    if (-not (Get-Content -LiteralPath $windowMarker -Raw).StartsWith('staging|')) { throw 'Cloud-window guard was not invoked through the launcher.' }

    $result = & (Join-Path $launcherScripts 'start-deploy.ps1') @launch
    if (($result | Select-Object -Last 1) -cne 'Deployment build completed successfully: oficina-phase3-oficina-app-staging-deploy:build-001') { throw 'Successful build was not reported.' }
    if (-not (Test-Path -LiteralPath $receiptPath -PathType Leaf)) { throw 'Staging promotion receipt was not written.' }
    $receipt = Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
    foreach ($name in @('sourceVersionId', 'releaseManifestVersionId', 'codeBuildBuildId')) {
        if ([string]::IsNullOrWhiteSpace([string]$receipt.$name)) { throw "Receipt is missing $name." }
    }
    if ($receipt.sourceVersionId -cne 'source-version-001' -or $receipt.releaseManifestVersionId -cne 'manifest-version-001' -or $receipt.codeBuildBuildId -cne 'oficina-phase3-oficina-app-staging-deploy:build-001') { throw 'Receipt does not bind immutable launch identities.' }
    if ((Get-Content -LiteralPath $receiptPath -Raw) -match '(?i)password|secret|token') { throw 'Promotion receipt contains a secret-like field.' }

    $sourcePut = $global:AwsCalls | Where-Object { $_ -match '^s3api put-object' -and $_ -match 'releases/app/staging/bundle\.zip' }
    $manifestPut = $global:AwsCalls | Where-Object { $_ -match '^s3api put-object' -and $_ -match 'releases/app/staging/manifests/' }
    $promotionPut = $global:AwsCalls | Where-Object { $_ -match '^s3api put-object' -and $_ -match 'releases/app/staging/promotions/' }
    if (@($sourcePut).Count -ne 1 -or @($manifestPut).Count -ne 1 -or @($promotionPut).Count -ne 1) { throw 'Versioned source, manifest and staging receipt uploads were not all issued.' }
    $buildCalls = @($global:AwsCalls | Where-Object { $_ -match '^codebuild start-build' })
    if ($buildCalls.Count -ne 1 -or ([string]$buildCalls[0]) -notmatch '--source-version source-version-001') { throw "CodeBuild was not pinned to the source VersionId. Calls: $($global:AwsCalls -join ' | ')" }
    $buildCall = [string]$buildCalls[0]
    $allowed = @('DEPLOY_ENVIRONMENT', 'SOURCE_BUCKET', 'SOURCE_KEY', 'SOURCE_VERSION_ID', 'EXPECTED_SHA256', 'RELEASE_MANIFEST_KEY', 'RELEASE_MANIFEST_VERSION_ID', 'EXPECTED_MANIFEST_SHA256', 'SOURCE_COMMIT')
    foreach ($override in ($buildCall -split 'name=' | Select-Object -Skip 1)) {
        $name = ($override -split ',')[0]
        if ($allowed -notcontains $name) { throw "Unexpected CodeBuild environment override: $name" }
    }

    $production = $launch.Clone(); $production.Environment = 'production'; $production.SourcePrefix = 'releases/app/production'; $production.ProjectName = 'oficina-phase3-oficina-app-production-deploy'; $production.BranchRef = 'refs/heads/main'
    Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @production -DryRun }
    $badTimeout = $launch.Clone(); $badTimeout.TimeoutSeconds = 30
    Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @badTimeout -DryRun }
    $badPrefix = $launch.Clone(); $badPrefix.SourcePrefix = 'releases/app/other'
    Reject { & (Join-Path $launcherScripts 'start-deploy.ps1') @badPrefix -DryRun }

    Write-Output 'PASS: APP launcher validates guards, pins versioned S3/CodeBuild inputs, polls success, and emits a redacted staging receipt without real AWS.'
}
finally {
    Remove-Item function:aws -ErrorAction SilentlyContinue
    Remove-Variable -Name AwsCalls -Scope Global -ErrorAction SilentlyContinue
    $resolved = [IO.Path]::GetFullPath($temp)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('oficina-app-launcher-')) { throw 'Unsafe cleanup target.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
