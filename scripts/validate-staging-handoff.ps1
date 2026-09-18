[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PlatformInputsFile,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedPlatformSha256,
    [Parameter(Mandatory)][string]$DatabaseReceiptFile,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedDatabaseReceiptSha256,
    [Parameter(Mandatory)][string]$FunctionsHandoffFile,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedFunctionsHandoffSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{40}$')][string]$SourceCommit,
    [string]$OutputFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-Hash([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Read-JsonSnapshot([string]$Path, [string]$ExpectedHash, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Label file is missing." }
    if ((Get-Hash $Path) -cne $ExpectedHash) { throw "$Label digest mismatch." }
    $bytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path))
    $raw = [Text.Encoding]::UTF8.GetString($bytes).TrimStart([char]0xfeff)
    try {
        $options = [System.Text.Json.JsonDocumentOptions]::new()
        $options.MaxDepth = 16
        $document = [System.Text.Json.JsonDocument]::Parse($raw, $options)
        try {
            Assert-UniqueProperties $document.RootElement
            if ($document.RootElement.ValueKind -ne [System.Text.Json.JsonValueKind]::Object) { throw "$Label root must be an object." }
        } finally { $document.Dispose() }
        return ($raw | ConvertFrom-Json)
    } catch { throw "$Label must be a JSON object with unique properties." }
}

function Assert-UniqueProperties([System.Text.Json.JsonElement]$Element) {
    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) {
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($property in $Element.EnumerateObject()) {
            if (-not $names.Add($property.Name)) { throw 'Duplicate JSON property.' }
            Assert-UniqueProperties $property.Value
        }
    } elseif ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Array) {
        foreach ($item in $Element.EnumerateArray()) { Assert-UniqueProperties $item }
    }
}

function Get-PropertyNames($Object) { @($Object.PSObject.Properties.Name | Sort-Object) }
function Require-ExactProperties($Object, [string[]]$Expected, [string]$Label) {
    if ((Get-PropertyNames $Object) -join ',' -cne (@($Expected | Sort-Object) -join ',')) { throw "$Label contains unknown or missing fields." }
}
function Require-String([object]$Value, [string]$Label) {
    if ($Value -isnot [string] -or [string]::IsNullOrWhiteSpace($Value)) { throw "$Label must be a non-empty string." }
}

$platform = Read-JsonSnapshot $PlatformInputsFile $ExpectedPlatformSha256 'K8S platform inputs'
$database = Read-JsonSnapshot $DatabaseReceiptFile $ExpectedDatabaseReceiptSha256 'DB bootstrap receipt'
$functions = Read-JsonSnapshot $FunctionsHandoffFile $ExpectedFunctionsHandoffSha256 'FUN gateway handoff'

$platformFields = @('Environment','Image','AppIrsaRoleArn','DeployerPrincipalArn','PlatformBindingPrincipalArn','DbHost','DbCidr','AlbSubnetCidrOne','AlbSubnetCidrTwo','AppSecretArn','AuthorizerTrustSecretArn','NewRelicIngestSecretArn','NewRelicAccountId')
Require-ExactProperties $platform $platformFields 'K8S platform inputs'
if ($platform.Environment -cne 'staging') { throw 'Staging rollout requires staging platform inputs.' }
if ($platform.Image -cnotmatch '\A(?<account>[0-9]{12})\.dkr\.ecr\.us-east-1\.amazonaws\.com/[a-z0-9][a-z0-9/_.-]*@sha256:[a-f0-9]{64}\z') { throw 'Platform image must be an immutable us-east-1 ECR digest.' }
$account = $Matches.account
if ($platform.AppIrsaRoleArn -cnotmatch "\Aarn:aws:iam::${account}:role/[A-Za-z0-9/+=,.@_-]+\z" -or
    $platform.DeployerPrincipalArn -cnotmatch "\Aarn:aws:iam::${account}:role/[A-Za-z0-9/+=,.@_-]+\z" -or
    $platform.PlatformBindingPrincipalArn -cnotmatch "\Aarn:aws:iam::${account}:role/[A-Za-z0-9/+=,.@_-]+\z") { throw 'Platform IAM references must be same-account role ARNs.' }
if ($platform.AppIrsaRoleArn -cnotmatch '[-/]staging(-|$)') { throw 'Platform APP IRSA role must identify staging.' }
if ($platform.DbHost -cnotmatch '\A[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\z') { throw 'Platform database host must be a DNS hostname.' }
foreach ($entry in @(@{Value=$platform.AppSecretArn; Name='app'}, @{Value=$platform.AuthorizerTrustSecretArn; Name='authorizer-trust'}, @{Value=$platform.NewRelicIngestSecretArn; Name='newrelic-ingest'})) {
    if ($entry.Value -cnotmatch "\Aarn:aws:secretsmanager:us-east-1:${account}:secret:oficina/staging/$($entry.Name)-[A-Za-z0-9]{6}\z") { throw 'Platform secret references must be exact same-account staging ARNs.' }
}
if ($platform.NewRelicAccountId -cnotmatch '\A[1-9][0-9]{0,15}\z') { throw 'Platform New Relic account metadata is invalid.' }

Require-ExactProperties $database @('schemaVersion','environment','sourceCommit','outputs') 'DB bootstrap receipt'
if ($database.schemaVersion -ne 2 -or $database.environment -cne 'staging' -or $database.sourceCommit -cne $SourceCommit) { throw 'DB bootstrap receipt is not the reviewed staging V2 receipt.' }
$databaseFields = @('schemaVersion','authViewVersion','recipientViewVersion','migrationSecretArn','migrationSecretVersionId','appSecretArn','appSecretVersionId','authLookupSecretArn','authLookupSecretVersionId','notificationLookupSecretArn','notificationLookupSecretVersionId')
Require-ExactProperties $database.outputs $databaseFields 'DB bootstrap receipt outputs'
if ($database.outputs.schemaVersion -cne 'V8' -or $database.outputs.authViewVersion -cne 'V5' -or $database.outputs.recipientViewVersion -cne 'V7') { throw 'DB schema/view versions are not the reviewed V8/V5/V7 contract.' }
$roles = @(@{Name='migration'; Arn=$database.outputs.migrationSecretArn; Version=$database.outputs.migrationSecretVersionId}, @{
    Name='app'; Arn=$database.outputs.appSecretArn; Version=$database.outputs.appSecretVersionId}, @{Name='auth'; Arn=$database.outputs.authLookupSecretArn; Version=$database.outputs.authLookupSecretVersionId}, @{
    Name='notification'; Arn=$database.outputs.notificationLookupSecretArn; Version=$database.outputs.notificationLookupSecretVersionId})
$seenArns = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($role in $roles) {
    if (-not $seenArns.Add([string]$role.Arn) -or $role.Arn -cnotmatch "\Aarn:aws:secretsmanager:us-east-1:${account}:secret:oficina/staging/$($role.Name)-[A-Za-z0-9]{6}\z") { throw 'DB runtime secret references must be four distinct same-account staging ARNs.' }
    if ($role.Version -cnotmatch '\A[A-Za-z0-9-]{32,64}\z') { throw 'DB runtime secret references require immutable VersionIds.' }
}

Require-ExactProperties $functions @('api_id','execution_arn','authorizer_id','environment') 'FUN gateway handoff'
if ($functions.environment -cne 'staging' -or $functions.api_id -cnotmatch '\A[a-z0-9]+\z' -or $functions.authorizer_id -cnotmatch '\A[A-Za-z0-9]+\z') { throw 'FUN handoff has invalid staging identifiers.' }
if ($functions.execution_arn -cnotmatch "\Aarn:aws:execute-api:us-east-1:${account}:$($functions.api_id)\z") { throw 'FUN gateway execution ARN is not bound to the platform account and API.' }

if ($OutputFile) {
    $destination = [IO.File]::Open([IO.Path]::GetFullPath($OutputFile), [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $metadata = [ordered]@{
            schemaVersion = 1; environment = 'staging'; sourceCommit = $SourceCommit; accountId = $account
            platformInputsSha256 = $ExpectedPlatformSha256; databaseReceiptSha256 = $ExpectedDatabaseReceiptSha256; functionsHandoffSha256 = $ExpectedFunctionsHandoffSha256
            databaseSchemaVersion = $database.outputs.schemaVersion; authViewVersion = $database.outputs.authViewVersion; recipientViewVersion = $database.outputs.recipientViewVersion
            gateway = [ordered]@{ apiId = $functions.api_id; executionArn = $functions.execution_arn; authorizerId = $functions.authorizer_id }
            databaseSecretReferences = [ordered]@{ migration = $database.outputs.migrationSecretArn; app = $database.outputs.appSecretArn; auth = $database.outputs.authLookupSecretArn; notification = $database.outputs.notificationLookupSecretArn }
        }
        $bytes = [Text.Encoding]::UTF8.GetBytes(($metadata | ConvertTo-Json -Depth 10))
        $destination.Write($bytes, 0, $bytes.Length)
    } finally { $destination.Dispose() }
}
Write-Output 'PASS: staging APP handoff binds K8S platform, DB V2 and FUN gateway artifacts; no cloud operation performed.'
