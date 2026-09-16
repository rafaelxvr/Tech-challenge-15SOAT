[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$InputFile,
  [Parameter(Mandatory)][ValidateSet('staging','production')][string]$ExpectedEnvironment,
  [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$ExpectedSourceCommit,
  [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedArtifactDigest,
  [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedPlanDigest,
  [switch]$AllowStudyRoot,
  [string]$StudyRootJustification = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Fail([string]$message) { throw "Readiness refused: $message" }
function Fingerprint([string]$value) {
  $bytes = [text.encoding]::UTF8.GetBytes($value)
  return ([convert]::ToHexString([security.cryptography.SHA256]::HashData($bytes))).ToLowerInvariant()
}
if (-not (Test-Path -LiteralPath $InputFile -PathType Leaf)) { Fail 'input file is absent' }
try { $input = Get-Content -Raw -LiteralPath $InputFile | ConvertFrom-Json -DateKind String } catch { Fail 'input is not JSON' }
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
if ($input.identityKind -eq 'root') {
  if (-not $AllowStudyRoot) { Fail 'root identity is prohibited' }
  if ($ExpectedEnvironment -ne 'staging' -or $input.environment -ne 'staging') { Fail 'study-root exception is staging-only' }
  $justificationText = $StudyRootJustification.Trim()
  try { $justification = $justificationText | ConvertFrom-Json -DateKind String } catch { Fail 'study-root justification must be structured JSON' }
  foreach ($field in 'studyScope','operatorApprovedPurpose','boundedWindowReference') {
    if ($null -eq $justification.PSObject.Properties[$field] -or [string]::IsNullOrWhiteSpace([string]$justification.$field)) { Fail "study-root justification is missing $field" }
  }
  $sensitive = '(?i)(akia|aws_access_key|secret|password|token|credential|account(?:id)?|arn:aws|\b\d{12}\b)'
  if ($justificationText -match $sensitive) { Fail 'study-root justification contains account or credential-like content' }
  if ($justification.studyScope -notmatch '^phase-3-staging-[a-z0-9-]{3,64}$' -or $justification.boundedWindowReference -notmatch '^window-[A-Za-z0-9._-]{6,120}$') { Fail 'study-root justification scope or bounded window reference is invalid' }
  if ([string]$justification.operatorApprovedPurpose -notmatch '(?i)\b(validate|rehearse|verify)\b' -or ([string]$justification.operatorApprovedPurpose).Length -lt 24) { Fail 'study-root justification purpose is too vague' }
  $fingerprint = Fingerprint $justificationText
  $exception = $input.studyRootException
  foreach ($field in 'evidenceRecord') {
    if ($null -eq $exception -or $null -eq $exception.PSObject.Properties[$field] -or [string]::IsNullOrWhiteSpace([string]$exception.$field)) { Fail "study-root safe evidence is missing $field" }
  }
  if ([string]$exception.evidenceRecord -notmatch '^[A-Za-z0-9._-]{8,128}\.json$') { Fail 'study-root evidence record reference is unsafe' }
  $inputDirectory = [io.path]::GetFullPath((Split-Path -Parent $InputFile))
  $evidencePath = [io.path]::GetFullPath((Join-Path $inputDirectory $exception.evidenceRecord))
  if (-not $evidencePath.StartsWith($inputDirectory, [stringcomparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath $evidencePath -PathType Leaf)) { Fail 'study-root evidence record does not resolve locally' }
  try { $evidence = Get-Content -Raw -LiteralPath $evidencePath | ConvertFrom-Json -DateKind String } catch { Fail 'study-root evidence record is invalid JSON' }
  foreach ($field in 'status','environment','recordedAtUtc','justificationFingerprint','studyScope','boundedWindowReference') {
    if ($null -eq $evidence.PSObject.Properties[$field] -or [string]::IsNullOrWhiteSpace([string]$evidence.$field)) { Fail "study-root evidence record is missing $field" }
  }
  if ($evidence.status -ne 'APPROVED_FOR_STUDY_STAGING' -or $evidence.environment -ne 'staging' -or $evidence.justificationFingerprint -ne $fingerprint -or $evidence.studyScope -ne $justification.studyScope -or $evidence.boundedWindowReference -ne $justification.boundedWindowReference) { Fail 'study-root evidence record does not match the requested exception' }
  if ([string]$evidence.recordedAtUtc -notmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$') { Fail 'study-root evidence timestamp must be UTC' }
  try { $null = [datetimeoffset]::Parse([string]$evidence.recordedAtUtc, [cultureinfo]::InvariantCulture, [globalization.DateTimeStyles]::AssumeUniversal) } catch { Fail 'study-root evidence timestamp is invalid' }
  Write-Output 'Readiness exception recorded: staging study-root identity; no credentials or account data emitted.'
}
if (-not [bool]$input.freeTierEligible) { Fail 'free-tier or study-credit eligibility is unconfirmed' }
if (-not [bool]$input.quotaConfirmed) { Fail 'quota is unconfirmed' }
if ([decimal]$input.creditRemainingUsd -lt (([decimal]$input.reserveUsd) + ([decimal]$input.estimatedCostUsd))) { Fail 'remaining credit does not cover reserve plus estimate' }
if ($input.windowStatus -ne 'OPEN') { Fail 'cloud window is not open' }
if (-not [bool]$input.secretViewConfirmed) { Fail 'approved secret/view dependency is unconfirmed' }
if ($input.migrationStatus -ne 'PASS') { Fail 'migration gate did not pass' }
Write-Output 'Readiness PASS: local evidence is internally complete; no deployment was performed.'
