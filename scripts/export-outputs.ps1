[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$TerraformOutputFile,
    [Parameter(Mandatory)][ValidateSet('staging','production')][string]$Environment,
    [Parameter(Mandatory)][ValidatePattern('\A[a-f0-9]{40}\z')][string]$SourceCommit,
    [Parameter(Mandatory)][string]$OutputFile
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$source=Get-Content -LiteralPath $TerraformOutputFile -Raw | ConvertFrom-Json
$allowlist=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../contracts/outputs-allowlist.json') -Raw | ConvertFrom-Json
$published=[ordered]@{}
foreach($mapping in $allowlist.outputs.PSObject.Properties) {
    $entry=$source.PSObject.Properties[$mapping.Value]
    if($null -eq $entry -or $entry.Value.sensitive -ne $false -or $entry.Value.value -isnot [string]) { throw 'Missing, sensitive or non-scalar APP output.' }
    $published[$mapping.Name]=$entry.Value.value
}
if($published.schemaVersion -cne 'V8' -or $published.authViewVersion -cne 'V5' -or $published.recipientViewVersion -cne 'V7') { throw 'Unreviewed schema/view version.' }
$arns=@($published.appSecretArn,$published.migrationSecretArn,$published.authLookupSecretArn,$published.notificationLookupSecretArn)
foreach($arn in $arns) {
    if($arn -cnotmatch "\Aarn:aws:secretsmanager:us-east-1:[0-9]{12}:secret:oficina/$Environment/[A-Za-z0-9_-]+-[A-Za-z0-9]{6}\z") { throw 'Only environment-scoped runtime credential references may be exported.' }
}
if(@($arns | Select-Object -Unique).Count -ne 4 -or @($arns | ForEach-Object {$_.Split(':')[4]} | Select-Object -Unique).Count -ne 1) { throw 'Runtime role references must be distinct and in one account.' }
@{schemaVersion=1; environment=$Environment; sourceCommit=$SourceCommit; outputs=$published} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $OutputFile -NoNewline
Write-Output 'Only reviewed APP schema and credential references were exported.'
