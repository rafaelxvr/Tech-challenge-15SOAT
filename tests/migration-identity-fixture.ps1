. "$PSScriptRoot/../scripts/staging-prerequisites-contract.ps1"
. "$PSScriptRoot/staging-prerequisites-fixture.ps1"
# Synthetic, credential-free fixture shared by the launcher and runtime tests.
function Add-MigrationIdentityFixture([System.Collections.IDictionary]$Release,[string]$PlatformPath) {
    if(-not $Release.Contains('migrationServiceAccount')){$Release.migrationServiceAccount='oficina-migration-staging'}
    if(-not $Release.bootstrapReview.Contains('roles')){
        $ca=$Release.bootstrapReview.caSha256
        $roles=@{};foreach($slot in @('migration','app','auth','notification')){$roles[$slot]=@{arn="arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/$slot-AbCdEf";versionId=('1'*32)}}
        $Release.bootstrapReview=@{schemaVersion=1;environment='staging';sourceCommit=$Release.sourceCommit;databaseHost='private.example.test';caSha256=$ca;master=@{arn='arn:aws:secretsmanager:us-east-1:123456789012:secret:rds!db-example';versionId=('2'*32)};roles=$roles}
    }
    $reviewJson=$Release.bootstrapReview|ConvertTo-Json -Depth 20 -Compress
    $refs=@{};foreach($slot in @('master','migration','app','auth','notification')){
        $ref=if($slot -ceq 'master'){$Release.bootstrapReview.master}else{$Release.bootstrapReview.roles[$slot]}
        $refs[$slot]=@{arn=$ref.arn;versionId=$ref.versionId;kmsKeyManager='AWS';kmsKeyArn=$null;metadataSha256=('c'*64)}
    }
    $issuer='oidc.eks.us-east-1.amazonaws.com/id/'+('A'*32)
    $identity=@{schemaVersion=1;environment='staging';sourceCommit=$Release.sourceCommit;namespace='oficina-staging';serviceAccountName='oficina-migration-staging';roleArn='arn:aws:iam::123456789012:role/oficina-phase3-staging-migration';oidcIssuer="https://$issuer";oidcProviderArn="arn:aws:iam::123456789012:oidc-provider/$issuer";bootstrapReviewSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($reviewJson))).ToLowerInvariant();secretReferences=$refs}
    $json=$identity|ConvertTo-Json -Depth 20 -Compress
    $platform=Get-Content -LiteralPath $PlatformPath -Raw|ConvertFrom-Json -AsHashtable
    $platform.MigrationIdentityJson=$json
    $platform.MigrationIdentitySha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($json))).ToLowerInvariant()
    $platform.MigrationNetworkPolicySha256='d'*64
    $platform|ConvertTo-Json -Depth 30|Set-Content -LiteralPath $PlatformPath -NoNewline
    $Release.platformInputsSha256=(Get-FileHash -LiteralPath $PlatformPath).Hash.ToLowerInvariant()
    Add-StagingPrerequisitesFixture $Release $PlatformPath
}
function New-MigrationServiceAccountFixture {
    @{apiVersion='v1';kind='ServiceAccount';metadata=@{uid='12345678-1111-2222-3333-123456789abc';resourceVersion='100';name='oficina-migration-staging';namespace='oficina-staging';annotations=@{'eks.amazonaws.com/role-arn'='arn:aws:iam::123456789012:role/oficina-phase3-staging-migration'}};automountServiceAccountToken=$false}
}
