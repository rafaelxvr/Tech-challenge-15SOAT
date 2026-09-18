[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
. "$repo/scripts/app-release-contract.ps1"
$temp = Join-Path ([IO.Path]::GetTempPath()) ('oficina-app-rollout-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
$global:appRolloutFixture = @{Calls=[Collections.Generic.List[string]]::new(); Failure=''; Drained=$false; Mode='FirstWriter'; Environment='staging'}
$global:appRolloutAssertions = 0
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message }; $global:appRolloutAssertions++ }
function Reject([scriptblock]$Action) { $rejected=$false; try { & $Action | Out-Null } catch { $rejected=$true }; Assert $rejected 'Expected contract rejection.' }
function aws {
    $f=$global:appRolloutFixture
    if ($f.Environment -cne 'production') { throw 'Offline test forbids AWS.' }
    $a=@($args); $f.Calls.Add('aws '+($a -join ' ')); $global:LASTEXITCODE=0
    switch ($a[1]) {
        'put-object' { $f.Owner=(Get-Content $a[$a.IndexOf('--body')+1] -Raw | ConvertFrom-Json).ownerToken; $f.Locked=$true; return '{}' }
        'head-object' { return (@{Metadata=@{owner=$f.Owner};ETag='"fixture-etag"'} | ConvertTo-Json) }
        'delete-object' { Assert ($a[$a.IndexOf('--if-match')+1] -ceq '"fixture-etag"') 'Unlock must compare owner ETag.'; $f.Locked=$false; return '{}' }
        default { throw 'Unexpected AWS operation in offline mock.' }
    }
}
function kubectl {
    $f=$global:appRolloutFixture
    $a=@($args); $f.Calls.Add(($a -join ' ')); $global:LASTEXITCODE=0
    if ($f.Environment -ceq 'production') { Assert $f.Locked 'Production operations require the shared deployment lock.' }
    if ($a[0] -cne '--context' -or $a[1] -cne 'arn:aws:eks:us-east-1:123456789012:cluster/oficina' -or $a[2] -cne '--namespace' -or $a[3] -cne "oficina-$($f.Environment)") { throw 'Unpinned context/namespace.' }
    $verb=$a[4]; $kind=$a[5]
    if ($verb -ceq 'patch' -and $a[-1].EndsWith('drain-patch.json')) { $f.Drained=$true }
    if (($verb -ceq 'wait' -and $f.Failure -ceq 'migration') -or ($verb -ceq 'rollout' -and $f.Failure -ceq 'rollout')) { $global:LASTEXITCODE=1; return 'Mock failure' }
    if ($verb -ceq 'logs') {
        if ($f.Failure -ceq 'window') { [IO.File]::AppendAllText($f.WindowFile,' ') }
        if ($f.Failure -ceq 'receipt') { return 'BOOTSTRAP_RECEIPT_JSON_BEGIN`nnot-json`nBOOTSTRAP_RECEIPT_JSON_END' }
        $f.Release.bootstrapReceipt | ConvertTo-Json -Depth 10 -Compress | ForEach-Object { return "BOOTSTRAP_RECEIPT_JSON_BEGIN`n$_`nBOOTSTRAP_RECEIPT_JSON_END" }
    }
    if ($verb -ceq 'get') {
        $r = switch ($kind) {
            deployment {
                $image=if($f.Mode -ceq 'Rollback') {$f.Release.image} else {$f.Release.previousImage}
                @{spec=@{replicas=$(if($f.Drained){0}else{1}); selector=@{matchLabels=@{'app.kubernetes.io/name'='oficina-app'}};
                    template=@{metadata=@{annotations=@{'oficina.io/schema-version'=$(if($f.Failure -ceq 'legacy'){'V4'}else{'V8'}); 'oficina.io/security-contract'='phase3-v2'}};
                        spec=@{serviceAccountName='oficina-app'; containers=@(@{name='app'; image=$image})}}}}
            }
            hpa { $min=if($f.Environment -ceq 'production'){2}else{1}; @{spec=@{minReplicas=$min; maxReplicas=($min*2); scaleTargetRef=@{kind='Deployment';name='oficina-app'};metrics=@(@{resource=@{name='cpu';target=@{averageUtilization=60}}})}} }
            serviceaccount { @{metadata=@{annotations=@{'eks.amazonaws.com/role-arn'="arn:aws:iam::123456789012:role/oficina-$($f.Environment)-app"}}} }
            pods { if($f.Failure -ceq 'drain'){@{items=@(@{metadata=@{name='old-writer'}})}}else{@{items=@()}} }
            job { @{status=@{conditions=@(@{type='Complete';status=$(if($f.Failure -ceq 'incomplete'){'False'}else{'True'})})};spec=@{template=@{spec=@{containers=@(@{image=$f.Release.bootstrapImage})}}}} }
            default { throw "Unexpected mock read $kind" }
        }
        if ($kind -cne 'pods') {
            $r.kind = @{deployment='Deployment'; hpa='HorizontalPodAutoscaler'; serviceaccount='ServiceAccount'; job='Job'}[$kind]
            $r.apiVersion = @{deployment='apps/v1'; hpa='autoscaling/v2'; serviceaccount='v1'; job='batch/v1'}[$kind]
            if (-not $r.ContainsKey('metadata')) { $r.metadata=@{} }
            $r.metadata.name=$a[6]; $r.metadata.namespace="oficina-$($f.Environment)"
        }
        return ($r | ConvertTo-Json -Depth 20)
    }
    return 'mock operation accepted'
}
function Write-Fixture([string]$Mode='FirstWriter', [string]$Environment='staging') {
    $prefix='123456789012.dkr.ecr.us-east-1.amazonaws.com/'
    $platform=@{Environment=$Environment; Image=($prefix+'oficina@sha256:'+('a'*64)); DbHost='private.example.test'; AppIrsaRoleArn="arn:aws:iam::123456789012:role/oficina-$Environment-app"}
    $platform | ConvertTo-Json | Set-Content -LiteralPath "$temp/platform.json"
    $review=@{schemaVersion=1;environment=$Environment;sourceCommit=('b'*40);databaseHost='private.example.test';caSha256=('f'*64);master=@{arn="arn:aws:secretsmanager:us-east-1:123456789012:secret:rds!db-example";versionId=('1'*32)};roles=@{
        migration=@{arn="arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/$Environment/migration-AbCdEf";versionId=('2'*32)};
        app=@{arn="arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/$Environment/app-AbCdEf";versionId=('3'*32)};
        auth=@{arn="arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/$Environment/auth-AbCdEf";versionId=('4'*32)};
        notification=@{arn="arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/$Environment/notification-AbCdEf";versionId=('5'*32)}}}
    $bootstrapReceipt=@{schemaVersion=2;environment=$Environment;sourceCommit=('b'*40);outputs=@{schemaVersion='V8';authViewVersion='V5';recipientViewVersion='V7';migrationSecretArn=$review.roles.migration.arn;migrationSecretVersionId=$review.roles.migration.versionId;appSecretArn=$review.roles.app.arn;appSecretVersionId=$review.roles.app.versionId;authLookupSecretArn=$review.roles.auth.arn;authLookupSecretVersionId=$review.roles.auth.versionId;notificationLookupSecretArn=$review.roles.notification.arn;notificationLookupSecretVersionId=$review.roles.notification.versionId}}
    $script:release=@{schemaVersion=1; environment=$Environment; mode=$Mode; sourceCommit=('b'*40); contractVersion='phase3-v2';databaseSchemaVersion='V8';
        platformInputsSha256=(Get-AppFileHash "$temp/platform.json");image=$platform.Image;previousImage=($prefix+'oficina@sha256:'+('c'*64)); migrationImage=($prefix+'flyway@sha256:'+('d'*64)); bootstrapImage=($prefix+'bootstrap@sha256:'+('e'*64)); bootstrapReview=$review; bootstrapReceipt=$bootstrapReceipt;
        kubeContext='arn:aws:eks:us-east-1:123456789012:cluster/oficina';migrationSecretName="oficina-migration-$Environment";migrationServiceAccount="oficina-migration-$Environment";
        migrationSqlSha256=(Get-AppMigrationDigest);rollback=@{image=($prefix+'oficina@sha256:'+('e'*64));databaseSchemaVersion='V8';contractVersion='phase3-v2';compatibilityEvidence='review/compatible-v8'}}
    $global:appRolloutFixture.Release=$script:release; $global:appRolloutFixture.Mode=$Mode; $global:appRolloutFixture.Environment=$Environment
    $global:appRolloutFixture.Calls.Clear(); $global:appRolloutFixture.Drained=$false; $global:appRolloutFixture.Failure=''
}
function Invoke-Fixture([switch]$Execute) {
    $script:release | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath "$temp/release.json"
    & "$repo/scripts/deploy-app.ps1" -ReleaseFile "$temp/release.json" -ExpectedReleaseSha256 (Get-AppFileHash "$temp/release.json") -PlatformInputsFile "$temp/platform.json" -OutputDirectory "$temp/rendered" -ExecuteReviewedPlan:$Execute -DrainTimeoutSeconds 1 | Out-Null
}
function Write-ProductionFixture {
    Write-Fixture -Mode Compatible -Environment production
    $global:appRolloutFixture.Locked=$false
    $global:appRolloutFixture.WindowFile="$temp/window.json"
    [IO.File]::WriteAllText("$temp/source.zip", 'synthetic offline source')
    @{environment='production'} | ConvertTo-Json | Set-Content "$temp/tfvars.json"
    @{windowStartUtc=[datetimeoffset]::UtcNow.AddMinutes(-5).ToString('o');windowEndUtc=[datetimeoffset]::UtcNow.AddMinutes(30).ToString('o');recordedAtUtc=[datetimeoffset]::UtcNow.ToString('o');accountEvidenceReference='offline-fixture';projectAllowanceUsd=10;reserveUsd=1;currentEstimatedSpendUsd=0} | ConvertTo-Json | Set-Content "$temp/window.json"
    $release.artifactSha256=Get-AppFileHash "$temp/source.zip"
    $release.runtimeArtifactDigest='sha256:'+('a'*64); $release.migrationVersion='V8'
    $release.promotedFromStaging=$true; $release.stagingArtifactSha256=$release.artifactSha256
    $release.deployerImageDigest='sha256:'+('d'*64)
    $release.terraformVariablesSha256=Get-AppFileHash "$temp/tfvars.json"
    $release.cloudWindowEvidenceSha256=Get-AppFileHash "$temp/window.json"
    $script:stagedRelease=$release.Clone(); $stagedRelease.environment='staging'
    $script:promotion=@{schemaVersion=1;environment='staging';sourceCommit=$release.sourceCommit;artifactSha256=$release.artifactSha256;sourceKey='releases/app/staging/bundle.zip';sourceVersionId='source-v1';releaseManifestKey="releases/app/staging/manifests/$($release.sourceCommit).json";releaseManifestVersionId='manifest-v1';releaseManifestSha256='';terraformVariablesKey="releases/app/staging/config/$($release.sourceCommit).tfvars.json";terraformVariablesVersionId='config-v1';terraformVariablesSha256=('d'*64);deployerImageDigest=$release.deployerImageDigest;codeBuildProjectName='oficina-phase3-oficina-app-staging-deploy';codeBuildBuildId='oficina-phase3-oficina-app-staging-deploy:00000000-0000-0000-0000-000000000001';buildStatus='SUCCEEDED';issuedAtUtc=[datetimeoffset]::UtcNow.AddMinutes(-1).ToString('o')}
    $script:productionArgs=@{Enabled='true';RuntimeEnabled='true';ProtectedEnvironment='production';RoleArn='arn:aws:iam::123456789012:role/oficina-app-production-launcher';SourceCommit=$release.sourceCommit;EventName='push';BranchRef='refs/heads/main';OutputDirectory="$temp/rendered";InputsFile="$temp/inputs.json"}
}
function Seal-ProductionFixture {
    $release | ConvertTo-Json -Depth 30 | Set-Content "$temp/release.json"
    $stagedRelease | ConvertTo-Json -Depth 30 | Set-Content "$temp/staged.json"
    $promotion.releaseManifestSha256=Get-AppFileHash "$temp/staged.json"
    $promotion | ConvertTo-Json -Depth 30 | Set-Content "$temp/promotion.json"
    $inputs=@{schemaVersion=1;environment='production';sourceCommit=$release.sourceCommit;accountId='123456789012';roleArn='arn:aws:iam::123456789012:role/oficina-app-production-launcher';projectName='oficina-phase3-oficina-app-production-deploy';sourcePrefix='releases/app/production';deployerImageDigest=$release.deployerImageDigest;stateBucket='offline-state-bucket'}
    foreach($entry in @{sourceArchive='source.zip';releaseManifest='release.json';platformInputs='platform.json';terraformVariables='tfvars.json';cloudWindowEvidence='window.json';stagingReleaseManifest='staged.json';stagingPromotion='promotion.json'}.GetEnumerator()) {
        $inputs[$entry.Key]=@{path=$entry.Value;sha256=(Get-AppFileHash "$temp/$($entry.Value)")}
    }
    $inputs.stagingPromotion.bucket='offline-artifact-bucket';$inputs.stagingPromotion.key="releases/app/staging/promotions/$($release.sourceCommit).json";$inputs.stagingPromotion.versionId='promotion-v1'
    $inputs | ConvertTo-Json -Depth 30 | Set-Content "$temp/inputs.json"
    $productionArgs.ExpectedInputsSha256=Get-AppFileHash "$temp/inputs.json"
}
try {
    $productionAdapter="$repo/scripts/deploy-production.ps1"
    foreach($runtime in @('','false','true')) {
        Write-ProductionFixture;Seal-ProductionFixture;$productionArgs.RuntimeEnabled=$runtime
        $result=& $productionAdapter @productionArgs
        Assert ($result -ceq 'PRODUCTION_RUNTIME_VALIDATED_DEPLOYMENT_DISABLED') 'Without explicit execution, even valid enabled inputs stay disabled.'
        Assert ($global:appRolloutFixture.Calls.Count -eq 0) 'Preflight cannot call AWS or Kubernetes.'
        if ($runtime -cne 'true') {
            $result=& $productionAdapter @productionArgs -ExecuteReviewedPlan
            Assert ($result -ceq 'PRODUCTION_RUNTIME_VALIDATED_DEPLOYMENT_DISABLED' -and $global:appRolloutFixture.Calls.Count -eq 0) 'Execution switch cannot enable an absent runtime gate.'
        }
    }
    foreach($change in @(
        {$productionArgs.Enabled=''}, {$productionArgs.Enabled='false'}, {$productionArgs.Enabled='TRUE'},
        {$productionArgs.RuntimeEnabled='TRUE'}, {$productionArgs.ProtectedEnvironment='staging'},
        {$productionArgs.BranchRef='refs/heads/develop'}, {$productionArgs.EventName='pull_request'},
        {$productionArgs.ExpectedInputsSha256='0'*64},
        {Remove-Item "$temp/promotion.json"}, {[IO.File]::AppendAllText("$temp/source.zip",'changed')}
    )) {
        Write-ProductionFixture;Seal-ProductionFixture;& $change
        Reject { & $productionAdapter @productionArgs -ExecuteReviewedPlan }
        Assert ($global:appRolloutFixture.Calls.Count -eq 0) 'Invalid gate/context/hash must fail before any executor call.'
    }
    foreach($change in @(
        {$promotion.buildStatus='FAILED'}, {$promotion.sourceCommit='e'*40}, {$promotion.artifactSha256='0'*64},
        {$stagedRelease.image=$stagedRelease.previousImage}, {$stagedRelease.bootstrapImage=$stagedRelease.migrationImage},
        {$stagedRelease.migrationImage=$stagedRelease.bootstrapImage}, {$stagedRelease.migrationSqlSha256='0'*64},
        {$release.mode='FirstWriter'}, {$release.mode='Rollback'}
    )) {
        Write-ProductionFixture;& $change;Seal-ProductionFixture
        Reject { & $productionAdapter @productionArgs -ExecuteReviewedPlan }
        Assert ($global:appRolloutFixture.Calls.Count -eq 0) 'Invalid staging promotion or mode cannot reach executor.'
    }
    Write-ProductionFixture;Seal-ProductionFixture
    & $productionAdapter @productionArgs -ExecuteReviewedPlan | Out-Null
    $calls=$global:appRolloutFixture.Calls -join "`n"
    Assert ($calls -match '(?s)aws s3api put-object.*get deployment.*migration-job.json.*wait job/.*logs .*rollout-patch.json.*rollout status.*aws s3api delete-object') 'Explicit production execution must lock, verify existing workload, migrate, verify receipt, roll out, unlock.'
    Assert (-not $calls.Contains('delete hpa') -and -not $calls.Contains('bootstrap-deployment.json')) 'Production cannot bootstrap a fresh workload or drain FirstWriter.'
    Assert (-not $global:appRolloutFixture.Locked) 'Production lock released after success.'
    Assert ((Get-Content "$temp/rendered/rollout-receipt.json" -Raw | ConvertFrom-Json).status -ceq 'ROLLOUT_COMPLETE') 'Explicit valid production path records verified completion.'
    foreach($failure in @('legacy','migration','receipt','incomplete','window')) {
        Write-ProductionFixture;Seal-ProductionFixture;$global:appRolloutFixture.Failure=$failure
        Reject { & $productionAdapter @productionArgs -ExecuteReviewedPlan }
        Assert (-not (($global:appRolloutFixture.Calls -join "`n").Contains('rollout-patch.json'))) 'Failed production validation/migration cannot roll out.'
        Assert (-not $global:appRolloutFixture.Locked) 'Production failure must release its owned lock.'
    }
    # Invoke the CodeBuild-compatible APP entry point against the real adapter.
    # Refuse to overwrite any pre-existing operator fixture at its trusted path.
    $trustedTfvars='/tmp/oficina/app_production.tfvars.json'
    if (Test-Path -LiteralPath $trustedTfvars) { throw 'Trusted production tfvars path already exists; isolate this test before running.' }
    New-Item -ItemType Directory -Path (Split-Path -Parent $trustedTfvars) -Force | Out-Null
    try {
        Write-ProductionFixture;Seal-ProductionFixture
        Copy-Item "$temp/tfvars.json" $trustedTfvars
        $executor=@{Environment='production';ReleaseManifest="$temp/release.json";ExpectedManifestSha256=(Get-AppFileHash "$temp/release.json");ExpectedSourceSha256=$release.artifactSha256;SourceCommit=$release.sourceCommit;ExpectedDeployerImageDigest=$release.deployerImageDigest;TerraformVariablesFile=$trustedTfvars;TerraformBackendBucket='offline-state-bucket';TerraformBackendKey='app/production.tfstate';TerraformBackendLockKey='app/production.tfstate.tflock';TerraformBackendRegion='us-east-1';StateBucket='offline-state-bucket';SourceKey='releases/app/production/bundle.zip';SourceArchiveFile="$temp/source.zip";ProductionEnabled='true';ProductionRuntimeEnabled='true';ProtectedEnvironment='production';ProductionInputsFile=$productionArgs.InputsFile;ExpectedProductionInputsSha256=$productionArgs.ExpectedInputsSha256;ProductionRoleArn=$productionArgs.RoleArn;EventName='push';BranchRef='refs/heads/main'}
        $result=& "$repo/scripts/deploy.ps1" @executor
        Assert ($result -ceq 'PRODUCTION_RUNTIME_VALIDATED_DEPLOYMENT_DISABLED' -and $global:appRolloutFixture.Calls.Count -eq 0) 'Executor remains disabled without explicit apply.'
        $result=& "$repo/scripts/deploy.ps1" @executor -DryRun -ApplyReviewedPlan
        Assert ($result -ceq 'PRODUCTION_RUNTIME_VALIDATED_DEPLOYMENT_DISABLED' -and $global:appRolloutFixture.Calls.Count -eq 0) 'Dry run cannot execute even with apply selected.'
        $executor.SourceKey='releases/app/staging/bundle.zip'
        Reject { & "$repo/scripts/deploy.ps1" @executor -ApplyReviewedPlan }
        Assert ($global:appRolloutFixture.Calls.Count -eq 0) 'Mismatched executor source cannot launch.'
        $executor.SourceKey='releases/app/production/bundle.zip'
        & "$repo/scripts/deploy.ps1" @executor -ApplyReviewedPlan | Out-Null
        Assert ((Get-Content "$temp/app-rollout/rollout-receipt.json" -Raw | ConvertFrom-Json).status -ceq 'ROLLOUT_COMPLETE') 'Executor invokes the real migration/rollout adapter.'
        Assert (-not $global:appRolloutFixture.Locked) 'Executor releases its production lock.'
    } finally { Remove-Item -LiteralPath $trustedTfvars -Force -ErrorAction SilentlyContinue }
    foreach ($environment in @('staging','production')) {
        Write-Fixture -Environment $environment
        Invoke-Fixture
        Assert ($global:appRolloutFixture.Calls.Count -eq 0) 'Default must only render.'
        $job=Get-Content "$temp/rendered/migration-job.json" -Raw | ConvertFrom-Json
        $container=$job.spec.template.spec.containers[0]
        Assert ($container.image -ceq $release.bootstrapImage -and $job.spec.backoffLimit -eq 0) 'Bootstrap digest/retry bound.'
        Assert ($job.spec.template.spec.serviceAccountName -ceq "oficina-migration-$environment" -and -not $job.spec.template.spec.automountServiceAccountToken) 'Distinct migration identity.'
        Assert ($job.spec.template.metadata.labels.'app.kubernetes.io/name' -cne 'oficina-app') 'Migration must never join the APP Service.'
        Assert (($container.args[1] -ceq (Get-BootstrapReviewSha256 (Get-BootstrapReviewJson $release.bootstrapReview))) -and $container.args[0] -ceq '/work/review.json') 'Bootstrap args must bind the exact review bytes.'
        Assert ((Get-Content "$temp/rendered/migration-job.json" -Raw) -notmatch '(?i)password|username|secretString|secretValue') 'Rendered bootstrap job must contain references only.'
        Assert ($container.env[0].name -ceq 'AWS_REGION' -and $container.env[0].value -ceq 'us-east-1') 'Bootstrap region must be explicit.'
        $patch=Get-Content "$temp/rendered/rollout-patch.json" -Raw | ConvertFrom-Json
        Assert ($patch.spec.strategy.type -ceq 'Recreate') 'FirstWriter requires Recreate.'
        Assert (($patch.spec.template.spec.containers[0].env | Where-Object name -CEQ 'SPRING_FLYWAY_ENABLED').value -ceq 'false') 'Cloud automatic migration disabled.'
        Assert (($patch.spec.template.spec.containers[0].env | Where-Object name -CEQ 'SPRING_JPA_HIBERNATE_DDL_AUTO').value -ceq 'validate') 'Hibernate must not mutate cloud schema.'
        if ($environment -ceq 'production') {
            Reject { Invoke-Fixture -Execute }
            Assert ($global:appRolloutFixture.Calls.Count -eq 0) 'Direct production execution must require reviewed gates and promotion inputs.'
            continue
        }
        Invoke-Fixture -Execute
        $calls=$global:appRolloutFixture.Calls -join "`n"
        Assert ($calls -match '(?s)delete hpa.*drain-patch.json.*get pods.*create .*bootstrap-review.json.*create .*migration-job.json.*wait job/.*get job.*logs .*rollout-patch.json.*rollout status.*apply .*hpa.json') 'Strict first-writer sequencing with bootstrap ConfigMap and receipt.'
        Assert ($calls -match 'patch deployment oficina-app --type=strategic') 'Retain platform env/resources/probes via strategic merge.'
    }
    foreach($failure in @('migration','incomplete','drain')) {
        Write-Fixture; $global:appRolloutFixture.Failure=$failure; Reject { Invoke-Fixture -Execute }
        $calls=$global:appRolloutFixture.Calls -join "`n"
        Assert (-not $calls.Contains('rollout-patch.json') -and -not $calls.Contains('apply -f')) 'Failed migration/drain cannot restore writers or HPA.'
        Assert ((Get-Content "$temp/rendered/rollout-receipt.json" -Raw | ConvertFrom-Json).status -cne 'ROLLOUT_COMPLETE') 'Failed attempt cannot retain a previous successful receipt.'
        if($failure -ceq 'drain'){Assert (-not $calls.Contains('migration-job.json')) 'No migration before old pods disappear.'}
    }
    Write-Fixture; $global:appRolloutFixture.Failure='rollout'; Reject { Invoke-Fixture -Execute }
    Assert (-not (($global:appRolloutFixture.Calls -join "`n") -match 'undo|apply -f')) 'Post-deploy failure must never automatically undo or restore HPA.'
    Write-Fixture -Mode Compatible; Invoke-Fixture -Execute
    $calls=$global:appRolloutFixture.Calls -join "`n"
    Assert ($calls -match '(?s)wait job/.*rollout-patch.json' -and -not $calls.Contains('delete hpa')) 'Compatible rollout still waits for migration.'
    Write-Fixture -Mode Compatible; $global:appRolloutFixture.Failure='legacy'; Reject { Invoke-Fixture -Execute }
    Assert (-not (($global:appRolloutFixture.Calls -join "`n").Contains('create -f'))) 'Legacy writer cannot claim compatibility.'
    Write-Fixture -Mode Rollback; Invoke-Fixture -Execute
    $patch=Get-Content "$temp/rendered/rollout-patch.json" -Raw | ConvertFrom-Json
    Assert ($patch.spec.template.spec.containers[0].image -ceq $release.rollback.image) 'Rollback pins approved compatible digest.'
    Assert (-not (($global:appRolloutFixture.Calls -join "`n") -match 'create -f|undo|delete hpa')) 'Rollback does not reverse migrations.'
    foreach($mutation in @(
        {$script:release.image='app:latest'}, {$script:release.environment='development'},
        {$script:release.migrationSecretName='oficina-app'}, {$script:release.migrationServiceAccount='oficina-app'},
        {$script:release.migrationSqlSha256=('0'*64)}, {$script:release.platformInputsSha256=('0'*64)},
        {$script:release.rollback.databaseSchemaVersion='V4'}, {$script:release.rollback.contractVersion='legacy'},
        {$script:release.rollback.image='app:old'}, {$script:release.migrationImage=$script:release.migrationImage.Replace('123456789012','999999999999')}
    )) { Write-Fixture -Mode Rollback; & $mutation; Reject { Invoke-Fixture -Execute }; Assert ($global:appRolloutFixture.Calls.Count -eq 0) 'Invalid review must fail before cluster access.' }
    Write-Fixture; Invoke-Fixture
    Reject { & "$repo/scripts/render-app-release.ps1" -ReleaseFile "$temp/release.json" -ExpectedReleaseSha256 ('0'*64) -PlatformInputsFile "$temp/platform.json" -OutputDirectory "$temp/rendered" }
    Write-Output "PASS: $global:appRolloutAssertions APP rollout assertions; kubectl mocked, no AWS calls."
} finally {
    Remove-Variable -Name appRolloutFixture -Scope Global
    Remove-Variable -Name appRolloutAssertions -Scope Global
    $resolved=[IO.Path]::GetFullPath($temp)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('oficina-app-rollout-')) { throw 'Unsafe test cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
