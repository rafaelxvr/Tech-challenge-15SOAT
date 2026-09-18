Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'bootstrap-release-contract.ps1')
function Get-AppFileHash([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Get-AppMigrationDigest {
    $directory = Join-Path $PSScriptRoot '../src/main/resources/db/migration'
    $entries = @(Get-ChildItem -LiteralPath $directory -Filter '*.sql' -File | Sort-Object Name)
    if ($entries.Count -ne 8) { throw 'Review the migration contract before changing the V1-V8 bundle.' }
    $index = ($entries | ForEach-Object { $_.Name + ':' + (Get-AppFileHash $_.FullName) + "`n" }) -join ''
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($index))).ToLowerInvariant()
}
function Read-AppRelease([string]$ReleaseFile, [string]$ExpectedSha256, [string]$PlatformInputsFile) {
    if ($ExpectedSha256 -cnotmatch '\A[a-f0-9]{64}\z' -or (Get-AppFileHash $ReleaseFile) -cne $ExpectedSha256) { throw 'Reviewed APP release digest mismatch.' }
    $release = Get-Content -LiteralPath $ReleaseFile -Raw | ConvertFrom-Json
    $platform = Get-Content -LiteralPath $PlatformInputsFile -Raw | ConvertFrom-Json
    if ($release.schemaVersion -ne 1 -or $release.environment -cnotin @('staging','production') -or $release.mode -cnotin @('FirstWriter','Compatible','Rollback')) { throw 'Invalid APP release schema/environment/mode.' }
    if ($release.sourceCommit -cnotmatch '\A[a-f0-9]{40}\z' -or $release.contractVersion -cne 'phase3-v2' -or $release.databaseSchemaVersion -cne 'V8') { throw 'Unreviewed source, schema or security contract.' }
    if ((Get-AppFileHash $PlatformInputsFile) -cne $release.platformInputsSha256 -or $platform.Environment -cne $release.environment -or $platform.Image -cne $release.image) { throw 'K8S renderer inputs are not bound to this APP release.' }
    $imagePattern = '\A([0-9]{12})\.dkr\.ecr\.us-east-1\.amazonaws\.com/[a-z0-9][a-z0-9/_.-]*@sha256:[a-f0-9]{64}\z'
    if ($release.image -cnotmatch $imagePattern) { throw 'APP image must be an immutable us-east-1 ECR digest.' }
    $account = $Matches[1]
    foreach ($image in @($release.previousImage, $release.migrationImage)) {
        if ($image -cnotmatch $imagePattern -or $Matches[1] -cne $account) { throw 'Previous and migration images must be immutable references in the reviewed account.' }
    }
    if ($release.mode -cne 'Rollback') {
        if ($release.bootstrapImage -isnot [string] -or $release.bootstrapImage -cnotmatch $imagePattern -or $Matches[1] -cne $account) {
            throw 'Writer releases require a dedicated immutable APP bootstrap image in the reviewed account.'
        }
        $bootstrapReview = Read-BootstrapReview $release $account
        if ($bootstrapReview.Review.databaseHost -cne $platform.DbHost) {
            throw 'bootstrapReview databaseHost must match the reviewed platform database host.'
        }
    }
    $environment = $release.environment
    if ($release.kubeContext -cnotmatch "\Aarn:aws:eks:us-east-1:${account}:cluster/[a-zA-Z0-9][a-zA-Z0-9_-]+\z") { throw 'Reviewed EKS context must match the image account and region.' }
    if ($platform.DbHost -cnotmatch '\A[a-z0-9][a-z0-9.-]+\z' -or $platform.AppIrsaRoleArn -cnotmatch "\Aarn:aws:iam::${account}:role/[A-Za-z0-9_/-]*${environment}[A-Za-z0-9_/-]*\z") { throw 'Invalid database host or APP IRSA environment.' }
    if ($release.migrationSecretName -cne "oficina-migration-$environment" -or $release.migrationServiceAccount -cne "oficina-migration-$environment") { throw 'Migration must use its distinct environment Secret and service account.' }
    if ($release.migrationSqlSha256 -cne (Get-AppMigrationDigest)) { throw 'Migration SQL differs from the reviewed release bundle.' }
    if ($release.mode -ceq 'Rollback') {
        if ($release.rollback.image -cnotmatch $imagePattern -or $Matches[1] -cne $account -or $release.rollback.databaseSchemaVersion -cne $release.databaseSchemaVersion -or $release.rollback.contractVersion -cne $release.contractVersion -or $release.rollback.compatibilityEvidence -cnotmatch '\A[a-zA-Z0-9][a-zA-Z0-9/_.-]{2,160}\z') { throw 'Rollback requires an explicit same-schema/security immutable compatibility receipt.' }
    }
    return @{ Release = $release; Platform = $platform; ReleaseSha256 = $ExpectedSha256 }
}
