[CmdletBinding()]
param([Parameter(Mandatory)][guid]$EventId,[Parameter(Mandatory)][guid]$OrderId,[Parameter(Mandatory)][string]$OperatorRef,[Parameter(Mandatory)][ValidatePattern('^[A-Z][A-Z0-9_]{0,63}$')][string]$Reason,[switch]$Execute)
Set-StrictMode -Version Latest
if ($OperatorRef -notmatch '^[A-Za-z0-9_-]{1,128}$') { throw 'Replay refused: operator reference is invalid.' }
$preview = "DLQ PREVIEW eventId=$EventId orderId=$OrderId operator=$OperatorRef reason=$Reason; retain original event ID and verify stale policy/ledger before replay."
if (-not $Execute) { Write-Output $preview; return }
throw "$preview Offline rehearsal never redrives SQS; execute through the separately authorized production procedure."
