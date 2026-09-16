[CmdletBinding()]
param([Parameter(Mandatory)][guid]$EventId,[Parameter(Mandatory)][string]$OperatorRef,[Parameter(Mandatory)][ValidatePattern('^[A-Z][A-Z0-9_]{0,63}$')][string]$Reason,[ValidateSet('RETRY','SKIP')][string]$Action = 'RETRY',[switch]$Execute)
Set-StrictMode -Version Latest
if ($OperatorRef -notmatch '^[A-Za-z0-9_-]{1,128}$') { throw 'Recovery refused: operator reference is invalid.' }
$preview = "OUTBOX PREVIEW action=$Action eventId=$EventId operator=$OperatorRef reason=$Reason; inspect dependencies and append audited outbox_recuperacoes only after approval."
if (-not $Execute) { Write-Output $preview; return }
throw "$preview Offline rehearsal never writes PostgreSQL; execute through the separately authorized production procedure."
