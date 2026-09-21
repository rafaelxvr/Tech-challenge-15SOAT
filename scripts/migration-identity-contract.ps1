Set-StrictMode -Version Latest
function Read-StagingMigrationIdentity($Platform,$Release) {
    if($Release.environment -isnot [string] -or $Release.environment -cne 'staging' -or $Release.mode -cne 'FirstWriter'){throw 'APP_MIGRATION_IDENTITY_INVALID: staging FirstWriter only.'}
    foreach($field in @('MigrationIdentityJson','MigrationIdentitySha256','MigrationNetworkPolicySha256')){
        $property=$Platform.PSObject.Properties[$field]
        if($null -eq $property -or $property.Value -isnot [string] -or [string]::IsNullOrWhiteSpace($property.Value)){throw 'APP_MIGRATION_IDENTITY_INVALID: reviewed identity/network fields required.'}
    }
    if($Platform.MigrationIdentitySha256 -cnotmatch '^[a-f0-9]{64}$' -or $Platform.MigrationNetworkPolicySha256 -cnotmatch '^[a-f0-9]{64}$' -or
        [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Platform.MigrationIdentityJson))).ToLowerInvariant() -cne $Platform.MigrationIdentitySha256){throw 'APP_MIGRATION_IDENTITY_INVALID: identity hash mismatch.'}
    try{$identity=ConvertFrom-Json -InputObject $Platform.MigrationIdentityJson -NoEnumerate}catch{throw 'APP_MIGRATION_IDENTITY_INVALID: identity JSON required.'}
    if($identity -isnot [pscustomobject]){throw 'APP_MIGRATION_IDENTITY_INVALID: identity object required.'}
    foreach($field in @('environment','sourceCommit','namespace','serviceAccountName','roleArn','bootstrapReviewSha256','oidcProviderArn','oidcIssuer')){
        if($identity.$field -isnot [string] -or [string]::IsNullOrWhiteSpace($identity.$field)){throw 'APP_MIGRATION_IDENTITY_INVALID: identity scalar required.'}
    }
    $account=[regex]::Match($Release.image,'^([0-9]{12})\.dkr\.ecr\.us-east-1\.amazonaws\.com/').Groups[1].Value
    if($identity.schemaVersion -isnot [long] -or $identity.schemaVersion -ne 1 -or $identity.environment -cne 'staging' -or $identity.sourceCommit -cne $Release.sourceCommit -or
       $identity.namespace -cne 'oficina-staging' -or $identity.serviceAccountName -cne $Release.migrationServiceAccount -or
       $identity.serviceAccountName -cne 'oficina-migration-staging' -or $identity.roleArn -cne "arn:aws:iam::${account}:role/oficina-phase3-staging-migration" -or
       $identity.oidcIssuer -cnotmatch '^https://oidc\.eks\.us-east-1\.amazonaws\.com/id/[A-Fa-f0-9]{32}$' -or
       $identity.oidcProviderArn -cne "arn:aws:iam::${account}:oidc-provider/$($identity.oidcIssuer.Substring(8))"){throw 'APP_MIGRATION_IDENTITY_INVALID: source/environment/role/OIDC mismatch.'}
    $review=Read-BootstrapReview $Release $account
    if($review.Sha256 -cne $identity.bootstrapReviewSha256 -or (@($identity.secretReferences.PSObject.Properties.Name|Sort-Object)-join ',') -cne 'app,auth,master,migration,notification'){throw 'APP_MIGRATION_IDENTITY_INVALID: bootstrap review mismatch.'}
    foreach($slot in @('master','migration','app','auth','notification')){
        $reference=$identity.secretReferences.$slot;$expected=if($slot -ceq 'master'){$review.Review.master}else{$review.Review.roles.$slot}
        if($reference.arn -isnot [string] -or $reference.versionId -isnot [string] -or $reference.arn -cne $expected.arn -or $reference.versionId -cne $expected.versionId -or
           $reference.metadataSha256 -isnot [string] -or $reference.metadataSha256 -cnotmatch '^[a-f0-9]{64}$' -or $reference.kmsKeyManager -isnot [string]){throw 'APP_MIGRATION_IDENTITY_INVALID: secret/version/metadata mismatch.'}
        if($reference.kmsKeyManager -ceq 'AWS') {if($null -ne $reference.kmsKeyArn){throw 'APP_MIGRATION_IDENTITY_INVALID: AWS key must not add a customer grant.'}}
        elseif($reference.kmsKeyManager -cne 'CUSTOMER' -or $reference.kmsKeyArn -isnot [string] -or $reference.kmsKeyArn -cnotmatch "^arn:aws:kms:us-east-1:${account}:key/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$"){throw 'APP_MIGRATION_IDENTITY_INVALID: explicit KMS ownership/key required.'}
    }
    return $identity
}
function Assert-MigrationServiceAccount($ServiceAccount,$Identity) {
    if($null -eq $ServiceAccount -or $ServiceAccount.kind -cne 'ServiceAccount' -or $ServiceAccount.metadata.name -cne $Identity.serviceAccountName -or
       $ServiceAccount.metadata.namespace -cne 'oficina-staging' -or $ServiceAccount.metadata.annotations.'eks.amazonaws.com/role-arn' -isnot [string] -or
       $ServiceAccount.metadata.annotations.'eks.amazonaws.com/role-arn' -cne $Identity.roleArn -or
       $ServiceAccount.automountServiceAccountToken -isnot [bool] -or $ServiceAccount.automountServiceAccountToken){throw 'APP_MIGRATION_SERVICE_ACCOUNT_MISMATCH: provision/review migration identity before rollout.'}
}
