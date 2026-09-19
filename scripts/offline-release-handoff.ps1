[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('\A[a-f0-9]{40}\z')][string]$SourceCommit,
    [Parameter(Mandatory)][string]$ReleaseInputFile,
    [Parameter(Mandatory)][string]$PlatformInputsFile,
    [string]$RuntimePublicConfigMapFile,
    [Parameter(Mandatory)][string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-Sha256([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Convert-ToCanonicalObject([object]$Value) {
    if ($null -eq $Value) { return $null }
    if ($Value -is [pscustomobject]) {
        $ordered = [ordered]@{}
        foreach ($property in @($Value.PSObject.Properties | Sort-Object Name)) {
            $ordered[$property.Name] = Convert-ToCanonicalObject $property.Value
        }
        return $ordered
    }
    if ($Value -is [System.Collections.IDictionary]) {
        $ordered = [ordered]@{}
        foreach ($key in @($Value.Keys | Sort-Object)) {
            $ordered[[string]$key] = Convert-ToCanonicalObject $Value[$key]
        }
        return $ordered
    }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        return @($Value | ForEach-Object { Convert-ToCanonicalObject $_ })
    }
    return $Value
}

function Write-CanonicalJson([object]$Value, [string]$Path) {
    $json = $Value | ConvertTo-Json -Depth 30 -Compress
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($Path), $json, [Text.UTF8Encoding]::new($false))
}

function Read-JsonFile([string]$Path, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Label is missing." }
    try { return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }
    catch { throw "$Label must be valid JSON." }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) {
    if (@(Get-ChildItem -LiteralPath $output -Force).Count -ne 0) { throw 'Output directory must be empty for a deterministic handoff.' }
} else {
    New-Item -ItemType Directory -Path $output -Force | Out-Null
}

$releaseInput = Read-JsonFile $ReleaseInputFile 'Release input'
$platform = Read-JsonFile $PlatformInputsFile 'Platform input'
if($releaseInput.environment -ceq 'staging' -and $releaseInput.mode -ceq 'FirstWriter'){
    . (Join-Path $PSScriptRoot 'runtime-public-configmap-contract.ps1')
    $null=Read-StagingPublicConfigMap $RuntimePublicConfigMapFile $releaseInput
    Copy-Item -LiteralPath $RuntimePublicConfigMapFile -Destination (Join-Path $output 'runtime-public.json')
}elseif(-not[string]::IsNullOrWhiteSpace($RuntimePublicConfigMapFile)){throw 'APP_PUBLIC_CONFIG_INVALID: staging FirstWriter artifact only.'}
$sourceCommitProperty = $releaseInput.PSObject.Properties['sourceCommit']
if ($null -ne $sourceCommitProperty -and [string]$sourceCommitProperty.Value -cne $SourceCommit) { throw 'Release input sourceCommit does not match the reviewed source.' }
if ($null -ne $releaseInput.PSObject.Properties['artifactSha256']) { throw 'Release input must not predeclare artifactSha256.' }

$sourceZip = Join-Path $output 'source.zip'
$releaseManifest = Join-Path $output 'release-manifest.json'
$rendered = Join-Path $output 'rendered'
$receipt = Join-Path $output 'release-receipt.json'

$artifactSha256 = (& (Join-Path $PSScriptRoot 'package-source.ps1') -SourceCommit $SourceCommit -OutputFile $sourceZip | Select-Object -Last 1).ToString().Trim().ToLowerInvariant()
if ($artifactSha256 -notmatch '\A[a-f0-9]{64}\z' -or (Get-Sha256 $sourceZip) -cne $artifactSha256) { throw 'Packaged source digest verification failed.' }

$manifest = [ordered]@{}
foreach ($property in @($releaseInput.PSObject.Properties | Sort-Object Name)) { $manifest[$property.Name] = Convert-ToCanonicalObject $property.Value }
$manifest['artifactSha256'] = $artifactSha256
$manifest['sourceCommit'] = $SourceCommit
if (-not $manifest.Contains('schemaVersion') -or -not $manifest.Contains('environment')) { throw 'Release input must include schemaVersion and environment.' }
Write-CanonicalJson (Convert-ToCanonicalObject $manifest) $releaseManifest
$manifestSha256 = Get-Sha256 $releaseManifest
$manifestReadback = Read-JsonFile $releaseManifest 'Generated release manifest'
if ($manifestReadback.sourceCommit -cne $SourceCommit -or $manifestReadback.artifactSha256 -cne $artifactSha256) { throw 'Generated release manifest does not bind the packaged source.' }
if ((Get-Sha256 $releaseManifest) -cne $manifestSha256) { throw 'Release manifest digest changed during verification.' }

& (Join-Path $PSScriptRoot 'render-app-release.ps1') -ReleaseFile $releaseManifest -ExpectedReleaseSha256 $manifestSha256 -PlatformInputsFile $PlatformInputsFile -OutputDirectory $rendered | Out-Null
$renderedEntries = @(Get-ChildItem -LiteralPath $rendered -File | Sort-Object Name | ForEach-Object { $_.Name + ':' + (Get-Sha256 $_.FullName) + "`n" }) -join ''
$renderedSha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($renderedEntries))).ToLowerInvariant()

$handoff = [ordered]@{
    schemaVersion = 1
    status = 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED'
    success = $false
    deploymentAttempted = $false
    sourceCommit = $SourceCommit
    artifactSha256 = $artifactSha256
    releaseManifestSha256 = $manifestSha256
    renderedOutputSha256 = $renderedSha256
    reason = 'APP cloud activation remains disabled; no AWS, OIDC, CodeBuild, Terraform or kubectl operation was attempted.'
}
Write-CanonicalJson (Convert-ToCanonicalObject $handoff) $receipt
$receiptReadback = Read-JsonFile $receipt 'Release receipt'
if ($receiptReadback.status -cne 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED' -or $receiptReadback.success -ne $false -or $receiptReadback.deploymentAttempted -ne $false) { throw 'Release receipt must record a disabled non-success outcome.' }
Write-Output 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED'
