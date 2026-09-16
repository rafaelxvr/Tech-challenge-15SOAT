[CmdletBinding()]
param([Parameter(Mandatory)][ValidateSet('staging','production')][string]$Environment,[Parameter(Mandatory)][string]$ReleaseManifest)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ReleaseManifest -PathType Leaf)) { throw 'Smoke refused: release manifest is absent.' }
$manifest = Get-Content -Raw -LiteralPath $ReleaseManifest | ConvertFrom-Json
if ($manifest.environment -ne $Environment -or -not [bool]$manifest.syntheticFixture) { throw 'Smoke refused: only a matching synthetic fixture manifest is accepted offline.' }
$temporaryToken = 'synthetic-token-' + [guid]::NewGuid().ToString('N')
try { Write-Output "SMOKE PREVIEW: $Environment using $($manifest.fixtureIdentity); temporary token remains in memory; no HTTP/AWS/New Relic call was made." }
finally { $temporaryToken = $null }
