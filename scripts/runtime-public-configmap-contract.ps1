Set-StrictMode -Version Latest

function Assert-PublicString($Value) {
    if ($Value -isnot [string] -or [string]::IsNullOrWhiteSpace($Value)) { throw 'APP_PUBLIC_CONFIG_INVALID: nonempty scalar strings required.' }
}
function Assert-StagingPublicConfigMapObject($Object, $Release) {
    if ($Release.environment -isnot [string] -or $Release.environment -cne 'staging' -or $Release.mode -isnot [string] -or $Release.mode -cne 'FirstWriter' -or $Object -isnot [pscustomobject]) { throw 'APP_PUBLIC_CONFIG_INVALID: staging FirstWriter only.' }
    foreach($field in @('apiVersion','kind')) { Assert-PublicString $Object.$field }
    if ($Object.apiVersion -cne 'v1' -or $Object.kind -cne 'ConfigMap' -or $Object.metadata -isnot [pscustomobject] -or $Object.data -isnot [pscustomobject]) { throw 'APP_PUBLIC_CONFIG_INVALID: ConfigMap object required.' }
    Assert-PublicString $Object.metadata.name; Assert-PublicString $Object.metadata.namespace
    if ($Object.metadata.name -cne 'oficina-runtime-public-staging' -or $Object.metadata.namespace -cne 'oficina-staging') { throw 'APP_PUBLIC_CONFIG_INVALID: resource identity mismatch.' }
    foreach($label in @(@('app.kubernetes.io/part-of','oficina'),@('app.kubernetes.io/managed-by','oficina-k8s-infra'))) {
        Assert-PublicString $Object.metadata.labels.($label[0])
        if($Object.metadata.labels.($label[0]) -cne $label[1]) { throw 'APP_PUBLIC_CONFIG_INVALID: source labels mismatch.' }
    }
    $keys=@('customer-public-keys.yaml','staff-issuer','staff-audience','staff-key-id','customer-issuer','customer-audience','notification-queue-url','history-zone','rds-ca.pem')
    if((@($Object.data.PSObject.Properties.Name|Sort-Object)-join ',') -cne (($keys|Sort-Object)-join ',') -or $null -ne $Object.PSObject.Properties['binaryData']) { throw 'APP_PUBLIC_CONFIG_INVALID: exact public data keys required.' }
    foreach($key in $keys) {
        Assert-PublicString $Object.data.$key
        if($Object.data.$key -match '(?i)PRIVATE KEY|STAFF_HMAC|JWT_SECRET|PASSWORD|CLIENT_SECRET|ACCESS_KEY|SECRET_KEY|\$\{') { throw 'APP_PUBLIC_CONFIG_INVALID: secret material or unresolved token.' }
    }
    if($Object.data.'staff-issuer' -cne 'oficina-staging-staff' -or $Object.data.'staff-audience' -cne 'oficina-staging-api' -or
       $Object.data.'customer-issuer' -cne 'oficina-staging-customer' -or $Object.data.'customer-audience' -cne 'oficina-staging-api' -or
       $Object.data.'staff-key-id' -cnotmatch '\A[A-Za-z0-9_-]{1,64}\z' -or $Object.data.'history-zone' -cnotmatch '\A[A-Za-z0-9_./+:-]+\z') { throw 'APP_PUBLIC_CONFIG_INVALID: public environment configuration mismatch.' }
    $account=[regex]::Match($Release.image,'\A([0-9]{12})\.dkr\.ecr\.us-east-1\.amazonaws\.com/').Groups[1].Value
    if($account.Length -ne 12 -or $Object.data.'notification-queue-url' -cne "https://sqs.us-east-1.amazonaws.com/$account/oficina-phase3-staging-notifications.fifo") { throw 'APP_PUBLIC_CONFIG_INVALID: reviewed staging queue required.' }
    $caHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Object.data.'rds-ca.pem'))).ToLowerInvariant()
    Assert-PublicString $Release.bootstrapReview.caSha256
    if($caHash -cne $Release.bootstrapReview.caSha256) { throw 'APP_PUBLIC_CONFIG_INVALID: mounted CA bytes differ from bootstrap review.' }
}
function Read-StagingPublicConfigMap([string]$Path, $Release) {
    $hash=$Release.PSObject.Properties['runtimePublicConfigMapSha256']
    if($null -eq $hash -or $hash.Value -isnot [string] -or $hash.Value -cnotmatch '\A[a-f0-9]{64}\z' -or
       [string]::IsNullOrWhiteSpace($Path) -or -not(Test-Path -LiteralPath $Path -PathType Leaf) -or
       (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $hash.Value) { throw 'APP_PUBLIC_CONFIG_INVALID: missing or mismatched artifact digest.' }
    try {$object=ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($Path)) -NoEnumerate} catch {throw 'APP_PUBLIC_CONFIG_INVALID: JSON object required.'}
    Assert-StagingPublicConfigMapObject $object $Release
    if((@($object.PSObject.Properties.Name|Sort-Object)-join ',') -cne 'apiVersion,data,kind,metadata' -or
       (@($object.metadata.PSObject.Properties.Name|Sort-Object)-join ',') -cne 'labels,name,namespace') { throw 'APP_PUBLIC_CONFIG_INVALID: only source-rendered metadata/data permitted.' }
    return $object
}
function Receive-StagingPublicConfigMap($Release,[string]$Bucket,[string]$ObjectKey,[string]$VersionId,[string]$ExpectedSha256,[string]$OutputFile) {
    foreach($value in @($Bucket,$ObjectKey,$VersionId,$ExpectedSha256)){Assert-PublicString $value}
    if($Release.environment -cne 'staging' -or $Release.mode -cne 'FirstWriter' -or
       $ObjectKey -cne "releases/app/staging/inputs/$($Release.sourceCommit)/runtime-public.json" -or
       $VersionId -ceq 'null' -or $VersionId -cnotmatch '\A[A-Za-z0-9._~+/=-]+\z' -or
       $ExpectedSha256 -cnotmatch '\A[a-f0-9]{64}\z' -or $ExpectedSha256 -cne $Release.runtimePublicConfigMapSha256) { throw 'APP_PUBLIC_CONFIG_INVALID: immutable staging object binding mismatch.' }
    $response=& aws s3api get-object --bucket $Bucket --key $ObjectKey --version-id $VersionId $OutputFile --output json 2>$null
    if($LASTEXITCODE -ne 0){throw 'APP_PUBLIC_CONFIG_DOWNLOAD_FAILED'}
    try{$metadata=ConvertFrom-Json -InputObject ($response -join "`n") -NoEnumerate}catch{throw 'APP_PUBLIC_CONFIG_DOWNLOAD_FAILED'}
    if($metadata -isnot [pscustomobject] -or $metadata.VersionId -isnot [string] -or $metadata.VersionId -cne $VersionId){throw 'APP_PUBLIC_CONFIG_VERSION_MISMATCH'}
    return Read-StagingPublicConfigMap $OutputFile $Release
}
