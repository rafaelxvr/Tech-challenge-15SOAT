[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
. "$repo/scripts/app-release-contract.ps1"
$temp = Join-Path ([IO.Path]::GetTempPath()) ('oficina-bootstrap-adapter-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
$assertions = 0
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message }; $script:assertions++ }
function Reject([scriptblock]$Action) { $failed=$false; try { & $Action | Out-Null } catch { $failed=$true }; Assert $failed 'Expected contract rejection.' }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Save([object]$Value, [string]$Path) { $Value | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $Path -NoNewline }

try {
    $prefix='123456789012.dkr.ecr.us-east-1.amazonaws.com/'
    $platform=[ordered]@{Environment='staging';Image=($prefix+'oficina@sha256:'+('a'*64));DbHost='private.example.test';AppIrsaRoleArn='arn:aws:iam::123456789012:role/oficina-staging-app'}
    $platformPath=Join-Path $temp 'platform.json'; Save $platform $platformPath
    $review=[ordered]@{schemaVersion=1;environment='staging';sourceCommit=('b'*40);databaseHost='private.example.test';caSha256=('f'*64);master=[ordered]@{arn='arn:aws:secretsmanager:us-east-1:123456789012:secret:rds!db-example';versionId=('1'*32)};roles=[ordered]@{migration=[ordered]@{arn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/migration-AbCdEf';versionId=('2'*32)};app=[ordered]@{arn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/app-AbCdEf';versionId=('3'*32)};auth=[ordered]@{arn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/auth-AbCdEf';versionId=('4'*32)};notification=[ordered]@{arn='arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/notification-AbCdEf';versionId=('5'*32)}}}
    $release=[ordered]@{schemaVersion=1;environment='staging';mode='FirstWriter';sourceCommit=('b'*40);contractVersion='phase3-v2';databaseSchemaVersion='V8';platformInputsSha256=(Hash $platformPath);image=$platform.Image;previousImage=$platform.Image;migrationImage=($prefix+'flyway@sha256:'+('c'*64));bootstrapImage=($prefix+'bootstrap@sha256:'+('d'*64));bootstrapReview=$review;kubeContext='arn:aws:eks:us-east-1:123456789012:cluster/oficina';migrationSecretName='oficina-migration-staging';migrationServiceAccount='oficina-migration-staging';migrationSqlSha256=(Get-AppMigrationDigest)}
    $releasePath=Join-Path $temp 'release.json'; Save $release $releasePath
    $release=Get-Content -LiteralPath $releasePath -Raw | ConvertFrom-Json

    $loaded=Read-BootstrapReview $release '123456789012'
    Assert ($loaded.Json -notmatch '(?i)password|username|secretString|secretValue') 'Review serialization contains credential-like content.'
    Assert ($loaded.Sha256 -ceq (Get-BootstrapReviewSha256 $loaded.Json)) 'Review digest must be reproducible.'
    & "$repo/scripts/render-app-release.ps1" -ReleaseFile $releasePath -ExpectedReleaseSha256 (Hash $releasePath) -PlatformInputsFile $platformPath -OutputDirectory (Join-Path $temp 'rendered') | Out-Null
    $rendered=Join-Path $temp 'rendered'
    $config=Get-Content -LiteralPath (Join-Path $rendered 'bootstrap-review.json') -Raw | ConvertFrom-Json
    $job=Get-Content -LiteralPath (Join-Path $rendered 'migration-job.json') -Raw | ConvertFrom-Json
    $container=$job.spec.template.spec.containers[0]
    Assert ($config.kind -ceq 'ConfigMap' -and $config.immutable -eq $true) 'Bootstrap review must be an immutable ConfigMap.'
    Assert ($config.data.'review.json' -ceq $loaded.Json) 'ConfigMap bytes must equal the reviewed input.'
    Assert ($container.image -ceq $release.bootstrapImage -and $container.args[1] -ceq $loaded.Sha256) 'Job must use the dedicated digest and exact review digest.'
    Assert ($container.args[0] -ceq '/work/review.json' -and $container.args[2] -ceq '/etc/oficina/public/rds-ca.pem' -and $container.args[3] -ceq '/work/bootstrap-receipt.json') 'BootstrapMain argument contract must be explicit.'
    Assert ($job.spec.backoffLimit -eq 0 -and $job.spec.template.spec.automountServiceAccountToken -eq $false) 'Bootstrap must be single-attempt and use the reviewed identity.'
    Assert ($job.spec.template.spec.securityContext.runAsNonRoot -eq $true -and $job.spec.template.spec.securityContext.runAsUser -eq 10001 -and $job.spec.template.spec.securityContext.runAsGroup -eq 10001) 'Bootstrap Pod must use the image UID/GID under runAsNonRoot.'
    Assert ((Get-Content -LiteralPath (Join-Path $rendered 'migration-job.json') -Raw) -notmatch '(?i)password|username|secretString|secretValue') 'Rendered workload must not contain credential values.'

    $receipt=[ordered]@{schemaVersion=2;environment='staging';sourceCommit=('b'*40);outputs=[ordered]@{schemaVersion='V8';authViewVersion='V5';recipientViewVersion='V7';migrationSecretArn=$review.roles.migration.arn;migrationSecretVersionId=$review.roles.migration.versionId;appSecretArn=$review.roles.app.arn;appSecretVersionId=$review.roles.app.versionId;authLookupSecretArn=$review.roles.auth.arn;authLookupSecretVersionId=$review.roles.auth.versionId;notificationLookupSecretArn=$review.roles.notification.arn;notificationLookupSecretVersionId=$review.roles.notification.versionId}}
    $receiptJson=$receipt | ConvertTo-Json -Depth 20 -Compress
    $validated=Read-BootstrapReceipt $receiptJson $release '123456789012'
    Assert ($validated.Receipt.outputs.schemaVersion -ceq 'V8') 'Receipt parser must prove V8.'
    Reject { Read-BootstrapReceipt (($receipt | ConvertTo-Json -Depth 20 -Compress).Replace('"V8"','"V7"')) $release '123456789012' }
    Reject { Read-BootstrapReceipt (($receipt | ConvertTo-Json -Depth 20 -Compress).Replace($review.roles.app.versionId,('9'*32))) $release '123456789012' }
    Reject { Read-BootstrapReceipt (($receipt | ConvertTo-Json -Depth 20 -Compress).Replace('"schemaVersion":2','"schemaVersion":"2"')) $release '123456789012' }
    Reject { Read-BootstrapReceipt (($receipt | ConvertTo-Json -Depth 20 -Compress).Replace('"schemaVersion":"V8"','"schemaVersion":8')) $release '123456789012' }
    foreach ($field in @('environment','sourceCommit')) {
        $badReceipt = $receipt | ConvertTo-Json -Depth 20 | ConvertFrom-Json
        $badReceipt.$field = @()
        Reject { Read-BootstrapReceipt ($badReceipt | ConvertTo-Json -Depth 20 -Compress) $release '123456789012' }
        $badReceipt = $receipt | ConvertTo-Json -Depth 20 | ConvertFrom-Json
        $badReceipt.$field = $null
        Reject { Read-BootstrapReceipt ($badReceipt | ConvertTo-Json -Depth 20 -Compress) $release '123456789012' }
    }
    foreach ($field in @('appSecretArn','appSecretVersionId','authLookupSecretArn','authLookupSecretVersionId','migrationSecretArn','migrationSecretVersionId','notificationLookupSecretArn','notificationLookupSecretVersionId')) {
        $badReceipt = $receipt | ConvertTo-Json -Depth 20 | ConvertFrom-Json
        $badReceipt.outputs.$field = @()
        Reject { Read-BootstrapReceipt ($badReceipt | ConvertTo-Json -Depth 20 -Compress) $release '123456789012' }
        $badReceipt = $receipt | ConvertTo-Json -Depth 20 | ConvertFrom-Json
        $badReceipt.outputs.$field = $null
        Reject { Read-BootstrapReceipt ($badReceipt | ConvertTo-Json -Depth 20 -Compress) $release '123456789012' }
    }
    foreach ($role in @('app','auth','migration','notification')) {
        foreach ($field in @('arn','versionId')) {
            $badReviewRelease = $release | ConvertTo-Json -Depth 20 | ConvertFrom-Json
            $badReviewRelease.bootstrapReview.roles.$role.$field = @()
            Reject { Read-BootstrapReview $badReviewRelease '123456789012' }
            $badReviewRelease = $release | ConvertTo-Json -Depth 20 | ConvertFrom-Json
            $badReviewRelease.bootstrapReview.roles.$role.$field = $null
            Reject { Read-BootstrapReview $badReviewRelease '123456789012' }
        }
    }
    foreach ($field in @('environment','sourceCommit')) {
        $badReviewRelease = $release | ConvertTo-Json -Depth 20 | ConvertFrom-Json
        $badReviewRelease.bootstrapReview.$field = @()
        Reject { Read-BootstrapReview $badReviewRelease '123456789012' }
        $badReviewRelease = $release | ConvertTo-Json -Depth 20 | ConvertFrom-Json
        $badReviewRelease.bootstrapReview.$field = $null
        Reject { Read-BootstrapReview $badReviewRelease '123456789012' }
    }

    $bad=[ordered]@{}; foreach($p in $review.PSObject.Properties){$bad[$p.Name]=$p.Value}; $bad.roles=[ordered]@{}; foreach($p in $review.roles.PSObject.Properties){$bad.roles[$p.Name]=$p.Value}; $bad.roles.app=[ordered]@{arn=$review.roles.app.arn;versionId=('9'*32)}
    $badRelease=[ordered]@{}; foreach($p in $release.PSObject.Properties){$badRelease[$p.Name]=$p.Value}; $badRelease.bootstrapReview=$bad
    $badReleasePath=Join-Path $temp 'bad-release.json'; Save $badRelease $badReleasePath
    Reject { Read-AppRelease $badReleasePath (Hash $badReleasePath) $platformPath }
    $badHostRelease=[ordered]@{}; foreach($p in $release.PSObject.Properties){$badHostRelease[$p.Name]=$p.Value}; $badHostReview=[ordered]@{}; foreach($p in $review.PSObject.Properties){$badHostReview[$p.Name]=$p.Value}; $badHostReview.databaseHost='other.example.test'; $badHostRelease.bootstrapReview=$badHostReview
    $badHostPath=Join-Path $temp 'bad-host-release.json'; Save $badHostRelease $badHostPath
    Reject { Read-AppRelease $badHostPath (Hash $badHostPath) $platformPath }

    $entry=Get-Content -LiteralPath (Join-Path $repo 'docker/bootstrap/entrypoint.sh') -Raw
    $docker=Get-Content -LiteralPath (Join-Path $repo 'docker/bootstrap/Dockerfile') -Raw
    Assert ($entry.Contains('com.oficina.bootstrap.BootstrapMain') -and $entry.Contains('BOOTSTRAP_RECEIPT_JSON_BEGIN') -and $entry.Contains('BOOTSTRAP_RECEIPT_JSON_END')) 'Bootstrap wrapper must execute BootstrapMain and emit a bounded receipt.'
    Assert ($docker.Contains('target/classes') -and $docker.Contains('target/bootstrap-libs') -and $docker.Contains('addgroup -S -g 10001') -and $docker.Contains('adduser -S -D -u 10001') -and $docker.Contains('ENTRYPOINT ["/opt/oficina/entrypoint.sh"]')) 'Dedicated bootstrap image must contain the reviewed Java entrypoint, dependencies and numeric identity.'
    Write-Output "PASS: $assertions bootstrap review/image/job/receipt assertions; no AWS or Kubernetes calls."
} finally {
    $resolved=[IO.Path]::GetFullPath($temp)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('oficina-bootstrap-adapter-')) { throw 'Unsafe test cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
