Set-StrictMode -Version Latest

function Get-BootstrapReviewJson([object]$Review) {
    if ($null -eq $Review) { throw 'APP release is missing bootstrapReview.' }
    # The release manifest binds the review object.  Keep the bytes deterministic so
    # BootstrapMain can verify the exact document before it reads any secret.
    $Review | ConvertTo-Json -Depth 20 -Compress
}

function Get-BootstrapReviewSha256([string]$ReviewJson) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($ReviewJson)
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Read-BootstrapReview([object]$Release, [string]$Account) {
    $review = $Release.bootstrapReview
    if ($null -eq $review) { throw 'APP release must include bootstrapReview for a writer release.' }
    $expected = @('caSha256','databaseHost','environment','master','roles','schemaVersion','sourceCommit')
    if ((@($review.PSObject.Properties.Name | Sort-Object) -join ',') -cne (($expected | Sort-Object) -join ',')) {
        throw 'bootstrapReview contains an unexpected or missing top-level field.'
    }
    if ($review.schemaVersion -ne 1 -or $review.environment -cne $Release.environment -or
        $review.sourceCommit -cne $Release.sourceCommit -or
        $review.databaseHost -notmatch '\A[a-z0-9][a-z0-9.-]+\z' -or
        $review.caSha256 -notmatch '\A[a-f0-9]{64}\z') {
        throw 'bootstrapReview is not bound to the reviewed environment, source or TLS input.'
    }
    if ($null -eq $review.master) {
        throw 'bootstrapReview must include the managed master reference.'
    }
    $masterFields = @($review.master.PSObject.Properties.Name | Sort-Object)
    if (($masterFields -join ',') -cne 'arn,versionId' -or
        $review.master.arn -notmatch "\Aarn:aws:secretsmanager:us-east-1:${Account}:secret:rds!db-[A-Za-z0-9-]+\z" -or
        $review.master.versionId -notmatch '\A[A-Za-z0-9-]{32,64}\z') {
        throw 'bootstrapReview master must be an exact same-account immutable reference.'
    }
    $roleNames = @($review.roles.PSObject.Properties.Name | Sort-Object)
    if (($roleNames -join ',') -cne 'app,auth,migration,notification') {
        throw 'bootstrapReview roles must be exactly app, auth, migration and notification.'
    }
    foreach ($role in @('migration','app','auth','notification')) {
        $reference = $review.roles.$role
        if ($null -eq $reference -or (@($reference.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'arn,versionId' -or
            $reference.arn -notmatch "\Aarn:aws:secretsmanager:us-east-1:${Account}:secret:oficina/$($Release.environment)/${role}-[A-Za-z0-9]{6}\z" -or
            $reference.versionId -notmatch '\A[A-Za-z0-9-]{32,64}\z') {
            throw "bootstrapReview role '$role' must be an exact immutable same-account reference."
        }
    }
    $json = Get-BootstrapReviewJson $review
    if ($json -match '(?i)"(?:password|username|secretString|secretValue|token)"\s*:') {
        throw 'bootstrapReview must contain references only; credential values are forbidden.'
    }
    [pscustomobject]@{ Review = $review; Json = $json; Sha256 = Get-BootstrapReviewSha256 $json }
}

function Read-BootstrapReceipt([string]$ReceiptJson, [object]$Release, [string]$Account) {
    if ([string]::IsNullOrWhiteSpace($ReceiptJson)) { throw 'Bootstrap Job did not emit a receipt.' }
    try { $receipt = $ReceiptJson | ConvertFrom-Json } catch { throw 'Bootstrap Job receipt is not valid JSON.' }
    $expectedTop = @('environment','outputs','schemaVersion','sourceCommit')
    if ((@($receipt.PSObject.Properties.Name | Sort-Object) -join ',') -cne (($expectedTop | Sort-Object) -join ',')) {
        throw 'Bootstrap receipt has an unexpected top-level field.'
    }
    if ($receipt.schemaVersion -isnot [long] -or $receipt.schemaVersion -ne 2 -or $receipt.environment -cne $Release.environment -or $receipt.sourceCommit -cne $Release.sourceCommit) {
        throw 'Bootstrap receipt is not bound to the reviewed source or environment.'
    }
    $expectedOutput = @('appSecretArn','appSecretVersionId','authLookupSecretArn','authLookupSecretVersionId','authViewVersion','migrationSecretArn','migrationSecretVersionId','notificationLookupSecretArn','notificationLookupSecretVersionId','recipientViewVersion','schemaVersion')
    if ((@($receipt.outputs.PSObject.Properties.Name | Sort-Object) -join ',') -cne (($expectedOutput | Sort-Object) -join ',')) {
        throw 'Bootstrap receipt outputs do not match the V2 contract.'
    }
    if ($receipt.outputs.schemaVersion -isnot [string] -or $receipt.outputs.schemaVersion -cne 'V8' -or
        $receipt.outputs.authViewVersion -isnot [string] -or $receipt.outputs.authViewVersion -cne 'V5' -or
        $receipt.outputs.recipientViewVersion -isnot [string] -or $receipt.outputs.recipientViewVersion -cne 'V7') {
        throw 'Bootstrap receipt does not prove the reviewed V8/V5/V7 schema contract.'
    }
    foreach ($role in @('app','auth','migration','notification')) {
        $ref = $Release.bootstrapReview.roles.$role
        $arnField = switch ($role) { app {'appSecretArn'} auth {'authLookupSecretArn'} migration {'migrationSecretArn'} notification {'notificationLookupSecretArn'} }
        $versionField = switch ($role) { app {'appSecretVersionId'} auth {'authLookupSecretVersionId'} migration {'migrationSecretVersionId'} notification {'notificationLookupSecretVersionId'} }
        if ($receipt.outputs.$arnField -cne $ref.arn -or $receipt.outputs.$versionField -cne $ref.versionId) {
            throw "Bootstrap receipt '$role' reference does not match the reviewed input."
        }
    }
    $forbidden = '(?i)(password|username|secretString|secretValue|databaseHost|sql|token)'
    if ($ReceiptJson -match $forbidden) { throw 'Bootstrap receipt contains forbidden credential or database detail.' }
    [pscustomobject]@{ Receipt = $receipt; Sha256 = Get-BootstrapReviewSha256 $ReceiptJson }
}
