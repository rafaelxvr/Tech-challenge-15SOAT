[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$InputFile,
  [Parameter(Mandatory)][ValidateSet('staging','production')][string]$ExpectedEnvironment,
  [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$ExpectedSourceCommit,
  [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedArtifactDigest,
  [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedPlanDigest
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Fail([string]$message) { throw "Readiness refused: $message" }
if (-not (Test-Path -LiteralPath $InputFile -PathType Leaf)) { Fail 'input file is absent' }
try { $input = Get-Content -Raw -LiteralPath $InputFile | ConvertFrom-Json } catch { Fail 'input is not JSON' }
foreach ($field in 'schemaVersion','environment','sourceCommit','artifactSha256','planSha256','identityKind','freeTierEligible','creditRemainingUsd','reserveUsd','estimatedCostUsd','quotaConfirmed','windowStatus','secretViewConfirmed','migrationStatus','capacityEvidence') {
  if ($null -eq $input.PSObject.Properties[$field] -or [string]::IsNullOrWhiteSpace([string]$input.$field)) { Fail "missing $field" }
}
if ($input.schemaVersion -ne 1) { Fail 'unsupported schema version' }
if ($input.environment -notin @('staging','production')) { Fail 'invalid environment' }
if ([string]$input.sourceCommit -notmatch '^[0-9a-f]{40}$') { Fail 'source commit is not immutable' }
foreach ($field in 'artifactSha256','planSha256') { if ([string]$input.$field -notmatch '^[0-9a-f]{64}$') { Fail "$field is not a SHA-256 digest" } }
if ($input.environment -ne $ExpectedEnvironment) { Fail 'environment differs from the reviewed release' }
if ($input.sourceCommit -ne $ExpectedSourceCommit) { Fail 'source revision differs from the reviewed release' }
if ($input.artifactSha256 -ne $ExpectedArtifactDigest) { Fail 'artifact digest differs from the reviewed release' }
if ($input.planSha256 -ne $ExpectedPlanDigest) { Fail 'plan digest differs from the reviewed release' }
$capacity = $input.capacityEvidence
foreach ($field in 'environment','status','availableCpuMillicores','requiredCpuMillicores','availableMemoryMiB','requiredMemoryMiB') {
  if ($null -eq $capacity.PSObject.Properties[$field] -or [string]::IsNullOrWhiteSpace([string]$capacity.$field)) { Fail "capacity evidence is missing $field" }
}
if ($capacity.environment -ne $ExpectedEnvironment -or $capacity.status -ne 'PASS') { Fail 'capacity evidence is invalid for the reviewed environment' }
try {
  if ([int]$capacity.availableCpuMillicores -lt [int]$capacity.requiredCpuMillicores -or [int]$capacity.availableMemoryMiB -lt [int]$capacity.requiredMemoryMiB) { Fail 'capacity evidence is insufficient' }
} catch { Fail 'capacity evidence has invalid numeric values' }
if ($input.identityKind -eq 'root') { Fail 'root identity is prohibited' }
if (-not [bool]$input.freeTierEligible) { Fail 'free-tier or study-credit eligibility is unconfirmed' }
if (-not [bool]$input.quotaConfirmed) { Fail 'quota is unconfirmed' }
if ([decimal]$input.creditRemainingUsd -lt (([decimal]$input.reserveUsd) + ([decimal]$input.estimatedCostUsd))) { Fail 'remaining credit does not cover reserve plus estimate' }
if ($input.windowStatus -ne 'OPEN') { Fail 'cloud window is not open' }
if (-not [bool]$input.secretViewConfirmed) { Fail 'approved secret/view dependency is unconfirmed' }
if ($input.migrationStatus -ne 'PASS') { Fail 'migration gate did not pass' }
Write-Output 'Readiness PASS: local evidence is internally complete; no deployment was performed.'
