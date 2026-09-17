[CmdletBinding()]
param([string]$Manifest = 'docs/phase-3/evidence/manifest.json')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Manifest -PathType Leaf)) { throw 'Evidence refused: manifest is absent.' }
$e = Get-Content -Raw -LiteralPath $Manifest | ConvertFrom-Json -DateKind String
if ($e.schemaVersion -ne 1 -or @($e.records).Count -ne 8) { throw 'Evidence refused: schema or eight deployment records are incomplete.' }
$expected = @{}
foreach ($repository in 'APP','K8S','FUN','DB') {
  $expected["$repository/staging"] = "R4-$repository-STG"
  $expected["$repository/production"] = "R4-$repository-PRD"
}
$seen = @{}
$results = @()
foreach ($record in @($e.records)) {
  foreach ($field in 'requirementId','repository','environment','sourceRevision','artifactDigest','planRevision','timestampUtc','expectedResult','observedResult','result','durableEvidenceLink') {
    if ($null -eq $record.PSObject.Properties[$field] -or [string]::IsNullOrWhiteSpace([string]$record.$field)) { throw "Evidence refused: record is missing $field." }
  }
  $key = "$($record.repository)/$($record.environment)"
  if (-not $expected.ContainsKey($key) -or $record.requirementId -ne $expected[$key]) { throw "Evidence refused: invalid requirement matrix record $key/$($record.requirementId)." }
  if ($seen.ContainsKey($key)) { throw "Evidence refused: duplicate repository/environment record $key." }
  $seen[$key] = $true
  if ($record.sourceRevision -notmatch '^[0-9a-f]{40}$') { throw 'Evidence refused: source revision is not immutable.' }
  if ($record.artifactDigest -notmatch '^[0-9a-f]{64}$' -or $record.planRevision -notmatch '^[0-9a-f]{64}$') { throw 'Evidence refused: digest is invalid.' }
  if ([string]$record.timestampUtc -notmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$') { throw 'Evidence refused: timestamp is not a UTC ISO-8601 Z-suffix value.' }
  try { $null = [datetimeoffset]::Parse([string]$record.timestampUtc, [cultureinfo]::InvariantCulture, [globalization.DateTimeStyles]::AssumeUniversal) } catch { throw 'Evidence refused: timestamp is not a UTC ISO-8601 Z-suffix value.' }
  if ($record.durableEvidenceLink -notmatch '^(https://|s3://)') { throw 'Evidence refused: durable evidence link is invalid.' }
  if ($record.result -notin @('PASS','FAIL','NOT_RUN')) { throw 'Evidence refused: record result is invalid.' }
  $results += $record.result
}
if ($seen.Count -ne $expected.Count) { throw 'Evidence refused: required repository/environment matrix is incomplete.' }
$derivedStatus = if ($results -contains 'FAIL') { 'FAIL' } elseif ($results -contains 'NOT_RUN') { 'NOT_RUN' } else { 'PASS' }
if ($e.status -ne $derivedStatus) { throw "Evidence refused: manifest status $($e.status) is inconsistent with record results $derivedStatus." }
if ($derivedStatus -ne 'PASS') { throw "Evidence refused: acceptance remains $derivedStatus." }
Write-Output 'Evidence PASS: all required acceptance records are complete.'
