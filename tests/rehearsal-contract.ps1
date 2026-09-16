$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$scripts = Join-Path $root 'scripts/rehearsal'
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('oficina-r4-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
  $closed = Join-Path $tmp 'closed.json'
  $base = '{"schemaVersion":1,"environment":"staging","sourceCommit":"0000000000000000000000000000000000000000","artifactSha256":"0000000000000000000000000000000000000000000000000000000000000000","planSha256":"0000000000000000000000000000000000000000000000000000000000000000","identityKind":"role","freeTierEligible":true,"creditRemainingUsd":35,"reserveUsd":5,"estimatedCostUsd":30,"quotaConfirmed":true,"windowStatus":"OPEN","secretViewConfirmed":true,"migrationStatus":"PASS","capacityEvidence":{"environment":"staging","status":"PASS","availableCpuMillicores":2000,"requiredCpuMillicores":1000,"availableMemoryMiB":4096,"requiredMemoryMiB":2048}}'
  function Assert-ReadinessRefusal([string]$json,[string]$expected) {
    $json | Set-Content -NoNewline $closed
    $message = $null
    try { & (Join-Path $scripts 'check-readiness.ps1') -InputFile $closed -ExpectedEnvironment staging -ExpectedSourceCommit ('0' * 40) -ExpectedArtifactDigest ('0' * 64) -ExpectedPlanDigest ('0' * 64) } catch { $message = $_.Exception.Message }
    if ($null -eq $message -or $message -notmatch $expected) { throw "expected readiness refusal '$expected', got '$message'" }
  }
  Assert-ReadinessRefusal ($base -replace '"windowStatus":"OPEN"','"windowStatus":"CLOSED"') 'cloud window'
  Assert-ReadinessRefusal ($base -replace '"identityKind":"role"','"identityKind":"root"') 'root identity'
  $studyJustification = [ordered]@{ studyScope='phase-3-staging-readiness'; operatorApprovedPurpose='Validate bounded staging readiness before delegated identities exist.'; boundedWindowReference='window-study-r4-local' } | ConvertTo-Json -Compress
  $fingerprint = ([convert]::ToHexString([security.cryptography.SHA256]::HashData([text.encoding]::UTF8.GetBytes($studyJustification))).ToLowerInvariant())
  $studyEvidence = [ordered]@{ status='APPROVED_FOR_STUDY_STAGING'; environment='staging'; recordedAtUtc='2026-09-16T12:00:00Z'; justificationFingerprint=$fingerprint; studyScope='phase-3-staging-readiness'; boundedWindowReference='window-study-r4-local' } | ConvertTo-Json -Compress
  $studyEvidence | Set-Content -NoNewline (Join-Path $tmp 'study-root-evidence.json')
  $studyRootObject = $base | ConvertFrom-Json -DateKind String
  $studyRootObject.identityKind = 'root'
  $studyRootObject | Add-Member -NotePropertyName studyRootException -NotePropertyValue ([pscustomobject]@{ evidenceRecord='study-root-evidence.json' })
  $studyRoot = $studyRootObject | ConvertTo-Json -Compress
  $studyRoot | Set-Content -NoNewline $closed
  try { & (Join-Path $scripts 'check-readiness.ps1') -InputFile $closed -ExpectedEnvironment staging -ExpectedSourceCommit ('0' * 40) -ExpectedArtifactDigest ('0' * 64) -ExpectedPlanDigest ('0' * 64); throw 'root without exception switch was accepted' } catch { if ($_.Exception.Message -notmatch 'root identity') { throw } }
  try { & (Join-Path $scripts 'check-readiness.ps1') -InputFile $closed -ExpectedEnvironment staging -ExpectedSourceCommit ('0' * 40) -ExpectedArtifactDigest ('0' * 64) -ExpectedPlanDigest ('0' * 64) -AllowStudyRoot -StudyRootJustification '{"studyScope":"phase-3-staging-readiness","operatorApprovedPurpose":"This is a long vague sentence without an action verb.","boundedWindowReference":"window-study-r4-local"}'; throw 'long vague study-root reason was accepted' } catch { if ($_.Exception.Message -notmatch 'too vague') { throw } }
  try { & (Join-Path $scripts 'check-readiness.ps1') -InputFile $closed -ExpectedEnvironment staging -ExpectedSourceCommit ('0' * 40) -ExpectedArtifactDigest ('0' * 64) -ExpectedPlanDigest ('0' * 64) -AllowStudyRoot -StudyRootJustification '{"studyScope":"phase-3-staging-readiness","operatorApprovedPurpose":"Validate AKIAABCDEFGHIJKLMNOP staging readiness safely.","boundedWindowReference":"window-study-r4-local"}'; throw 'credential-like study-root reason was accepted' } catch { if ($_.Exception.Message -notmatch 'credential-like') { throw } }
  try { & (Join-Path $scripts 'check-readiness.ps1') -InputFile $closed -ExpectedEnvironment production -ExpectedSourceCommit ('0' * 40) -ExpectedArtifactDigest ('0' * 64) -ExpectedPlanDigest ('0' * 64) -AllowStudyRoot -StudyRootJustification $studyJustification; throw 'production study-root was accepted' } catch { if ($_.Exception.Message -notmatch 'environment differs|staging-only') { throw } }
  $missingRecord = $studyRoot.Replace('study-root-evidence.json','missing-evidence.json') | Set-Content -NoNewline $closed
  try { & (Join-Path $scripts 'check-readiness.ps1') -InputFile $closed -ExpectedEnvironment staging -ExpectedSourceCommit ('0' * 40) -ExpectedArtifactDigest ('0' * 64) -ExpectedPlanDigest ('0' * 64) -AllowStudyRoot -StudyRootJustification $studyJustification; throw 'missing evidence record was accepted' } catch { if ($_.Exception.Message -notmatch 'does not resolve locally') { throw } }
  $studyRoot | Set-Content -NoNewline $closed
  '{"status":"APPROVED_FOR_STUDY_STAGING","environment":"staging","recordedAtUtc":"2026-09-16T12:00:00Z","justificationFingerprint":"bad","studyScope":"phase-3-staging-readiness","boundedWindowReference":"window-study-r4-local"}' | Set-Content -NoNewline (Join-Path $tmp 'study-root-evidence.json')
  try { & (Join-Path $scripts 'check-readiness.ps1') -InputFile $closed -ExpectedEnvironment staging -ExpectedSourceCommit ('0' * 40) -ExpectedArtifactDigest ('0' * 64) -ExpectedPlanDigest ('0' * 64) -AllowStudyRoot -StudyRootJustification $studyJustification; throw 'mismatched evidence record was accepted' } catch { if ($_.Exception.Message -notmatch 'does not match') { throw } }
  $studyEvidence | Set-Content -NoNewline (Join-Path $tmp 'study-root-evidence.json')
  $studyOutput = & (Join-Path $scripts 'check-readiness.ps1') -InputFile $closed -ExpectedEnvironment staging -ExpectedSourceCommit ('0' * 40) -ExpectedArtifactDigest ('0' * 64) -ExpectedPlanDigest ('0' * 64) -AllowStudyRoot -StudyRootJustification $studyJustification
  if ($studyOutput -notmatch 'exception recorded|Readiness PASS') { throw 'valid staging study-root exception did not record safe evidence' }
  Assert-ReadinessRefusal ($base -replace '"creditRemainingUsd":35','"creditRemainingUsd":34') 'remaining credit'
  Assert-ReadinessRefusal ($base -replace '"environment":"staging"','"environment":"qa"') 'invalid environment'
  Assert-ReadinessRefusal ($base -replace '"artifactSha256":"[0-9a-f]+"','"artifactSha256":"bad"') 'artifactSha256'
  Assert-ReadinessRefusal ($base -replace '"secretViewConfirmed":true','"secretViewConfirmed":false') 'secret/view'
  Assert-ReadinessRefusal ($base -replace '"migrationStatus":"PASS"','"migrationStatus":"FAIL"') 'migration gate'
  Assert-ReadinessRefusal ($base -replace '"capacityEvidence":\{"environment":"staging","status":"PASS"','"capacityEvidence":{"environment":"staging","status":"FAIL"') 'capacity evidence'
  $base | Set-Content -NoNewline $closed
  try { & (Join-Path $scripts 'check-readiness.ps1') -InputFile $closed -ExpectedEnvironment production -ExpectedSourceCommit ('0' * 40) -ExpectedArtifactDigest ('0' * 64) -ExpectedPlanDigest ('0' * 64); throw 'valid wrong environment was accepted' } catch { if ($_.Exception.Message -notmatch 'environment differs') { throw } }
  $wrongRevision = $base -replace '"sourceCommit":"0{40}"',('"sourceCommit":"' + ('1' * 40) + '"')
  Assert-ReadinessRefusal $wrongRevision 'source revision differs'
  $wrongDigest = $base -replace '"artifactSha256":"0{64}"',('"artifactSha256":"' + ('1' * 64) + '"')
  Assert-ReadinessRefusal $wrongDigest 'artifact digest differs'

  function New-EvidenceRecord([string]$repository,[string]$environment) {
    $suffix = if ($environment -eq 'staging') { 'STG' } else { 'PRD' }
    [pscustomobject]@{ requirementId="R4-$repository-$suffix"; repository=$repository; environment=$environment; sourceRevision=('a'*40); artifactDigest=('b'*64); planRevision=('c'*64); timestampUtc='2026-09-16T12:00:00Z'; expectedResult='PASS'; observedResult='PASS'; result='PASS'; durableEvidenceLink="s3://evidence/$repository/$environment" }
  }
  $evidencePath = Join-Path $tmp 'evidence.json'
  $records = @(); foreach ($repo in 'APP','K8S','FUN','DB') { foreach ($env in 'staging','production') { $records += New-EvidenceRecord $repo $env } }
  ([pscustomobject]@{schemaVersion=1;status='PASS';records=$records} | ConvertTo-Json -Depth 5) | Set-Content -NoNewline $evidencePath
  & (Join-Path $scripts 'verify-evidence.ps1') -Manifest $evidencePath | Out-Null
  (Get-Content -Raw $evidencePath).Replace('2026-09-16T12:00:00Z','2026-09-16T12:00:00-03:00') | Set-Content -NoNewline $evidencePath
  try { & (Join-Path $scripts 'verify-evidence.ps1') -Manifest $evidencePath; throw 'offset timestamp was accepted' } catch { if ($_.Exception.Message -notmatch 'Z-suffix') { throw } }
  ([pscustomobject]@{schemaVersion=1;status='PASS';records=$records} | ConvertTo-Json -Depth 5) | Set-Content -NoNewline $evidencePath
  $notRun = [pscustomobject]@{schemaVersion=1;status='NOT_RUN';records=$records}; $notRun.records[0].result = 'NOT_RUN'
  ($notRun | ConvertTo-Json -Depth 5) | Set-Content -NoNewline $evidencePath
  try { & (Join-Path $scripts 'verify-evidence.ps1') -Manifest $evidencePath; throw 'NOT_RUN matrix was accepted' } catch { if ($_.Exception.Message -notmatch 'acceptance remains NOT_RUN') { throw } }
  $duplicate = [pscustomobject]@{schemaVersion=1;status='PASS';records=$records}; $duplicate.records[7].repository='APP'; $duplicate.records[7].environment='production'; $duplicate.records[7].requirementId='R4-APP-PRD'
  ($duplicate | ConvertTo-Json -Depth 5) | Set-Content -NoNewline $evidencePath
  try { & (Join-Path $scripts 'verify-evidence.ps1') -Manifest $evidencePath; throw 'duplicate matrix was accepted' } catch { if ($_.Exception.Message -notmatch 'duplicate') { throw } }
  try { & (Join-Path $scripts 'verify-evidence.ps1') -Manifest (Join-Path $root 'docs/phase-3/evidence/manifest.json'); throw 'committed NOT_RUN evidence was accepted' } catch { if ($_.Exception.Message -notmatch 'source revision') { throw } }
  $preview = & (Join-Path $scripts 'recover-outbox.ps1') -EventId ([guid]::NewGuid()) -OperatorRef operator_1 -Reason INSPECTED_RETRY
  if ($preview -notmatch 'PREVIEW') { throw 'outbox recovery did not preview by default' }
  try { & (Join-Path $scripts 'replay-notification.ps1') -EventId ([guid]::NewGuid()) -OrderId ([guid]::NewGuid()) -OperatorRef operator_1 -Reason INSPECTED_REPLAY -Execute; throw 'offline replay execute was accepted' } catch { if ($_.Exception.Message -notmatch 'never redrives') { throw } }
  $fixture = Join-Path $tmp 'fixture.json'
  '{"environment":"staging","syntheticFixture":true,"fixtureIdentity":"synthetic-customer"}' | Set-Content -NoNewline $fixture
  $smoke = & (Join-Path $scripts 'smoke.ps1') -Environment staging -ReleaseManifest $fixture
  if ($smoke -notmatch 'SMOKE PREVIEW') { throw 'smoke did not remain preview-only' }
  Write-Output 'PASS: R4 local guards refuse readiness/evidence/replay and preview recovery.'
} finally { Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue }
