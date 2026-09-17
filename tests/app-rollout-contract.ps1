[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
. "$repo/scripts/app-release-contract.ps1"
$temp = Join-Path ([IO.Path]::GetTempPath()) ('oficina-app-rollout-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
$global:appRolloutFixture = @{Calls=[Collections.Generic.List[string]]::new(); Failure=''; Drained=$false; Mode='FirstWriter'; Environment='staging'}
$assertions = 0
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message }; $script:assertions++ }
function Reject([scriptblock]$Action) { $rejected=$false; try { & $Action | Out-Null } catch { $rejected=$true }; Assert $rejected 'Expected contract rejection.' }
function aws { throw 'Offline test forbids AWS.' }
function kubectl {
    $f=$global:appRolloutFixture
    $a=@($args); $f.Calls.Add(($a -join ' ')); $global:LASTEXITCODE=0
    if ($a[0] -cne '--context' -or $a[1] -cne 'arn:aws:eks:us-east-1:123456789012:cluster/oficina' -or $a[2] -cne '--namespace' -or $a[3] -cne "oficina-$($f.Environment)") { throw 'Unpinned context/namespace.' }
    $verb=$a[4]; $kind=$a[5]
    if ($verb -ceq 'patch' -and $a[-1].EndsWith('drain-patch.json')) { $f.Drained=$true }
    if (($verb -ceq 'wait' -and $f.Failure -ceq 'migration') -or ($verb -ceq 'rollout' -and $f.Failure -ceq 'rollout')) { $global:LASTEXITCODE=1; return 'Mock failure' }
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
            job { @{status=@{conditions=@(@{type='Complete';status=$(if($f.Failure -ceq 'incomplete'){'False'}else{'True'})})};spec=@{template=@{spec=@{containers=@(@{image=$f.Release.migrationImage})}}}} }
            default { throw "Unexpected mock read $kind" }
        }
        return ($r | ConvertTo-Json -Depth 20)
    }
    return 'mock operation accepted'
}
function Write-Fixture([string]$Mode='FirstWriter', [string]$Environment='staging') {
    $prefix='123456789012.dkr.ecr.us-east-1.amazonaws.com/'
    $platform=@{Environment=$Environment; Image=($prefix+'oficina@sha256:'+('a'*64)); DbHost='private.example.test'; AppIrsaRoleArn="arn:aws:iam::123456789012:role/oficina-$Environment-app"}
    $platform | ConvertTo-Json | Set-Content -LiteralPath "$temp/platform.json"
    $script:release=@{schemaVersion=1; environment=$Environment; mode=$Mode; sourceCommit=('b'*40); contractVersion='phase3-v2';databaseSchemaVersion='V8';
        platformInputsSha256=(Get-AppFileHash "$temp/platform.json");image=$platform.Image;previousImage=($prefix+'oficina@sha256:'+('c'*64)); migrationImage=($prefix+'flyway@sha256:'+('d'*64));
        kubeContext='arn:aws:eks:us-east-1:123456789012:cluster/oficina';migrationSecretName="oficina-migration-$Environment";migrationServiceAccount="oficina-migration-$Environment";
        migrationSqlSha256=(Get-AppMigrationDigest);rollback=@{image=($prefix+'oficina@sha256:'+('e'*64));databaseSchemaVersion='V8';contractVersion='phase3-v2';compatibilityEvidence='review/compatible-v8'}}
    $global:appRolloutFixture.Release=$script:release; $global:appRolloutFixture.Mode=$Mode; $global:appRolloutFixture.Environment=$Environment
    $global:appRolloutFixture.Calls.Clear(); $global:appRolloutFixture.Drained=$false; $global:appRolloutFixture.Failure=''
}
function Invoke-Fixture([switch]$Execute) {
    $script:release | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath "$temp/release.json"
    & "$repo/scripts/deploy-app.ps1" -ReleaseFile "$temp/release.json" -ExpectedReleaseSha256 (Get-AppFileHash "$temp/release.json") -PlatformInputsFile "$temp/platform.json" -OutputDirectory "$temp/rendered" -ExecuteReviewedPlan:$Execute -DrainTimeoutSeconds 1 | Out-Null
}
try {
    foreach ($environment in @('staging','production')) {
        Write-Fixture -Environment $environment
        Invoke-Fixture
        Assert ($global:appRolloutFixture.Calls.Count -eq 0) 'Default must only render.'
        $job=Get-Content "$temp/rendered/migration-job.json" -Raw | ConvertFrom-Json
        $container=$job.spec.template.spec.containers[0]
        Assert ($container.image -ceq $release.migrationImage -and $job.spec.backoffLimit -eq 0) 'Migration digest/retry bound.'
        Assert ($job.spec.template.spec.serviceAccountName -ceq "oficina-migration-$environment" -and -not $job.spec.template.spec.automountServiceAccountToken) 'Distinct migration identity.'
        Assert ($job.spec.template.metadata.labels.'app.kubernetes.io/name' -cne 'oficina-app') 'Migration must never join the APP Service.'
        Assert (@($container.env | Where-Object {$_.name -in @('FLYWAY_USER','FLYWAY_PASSWORD')} | ForEach-Object {$_.valueFrom.secretKeyRef.name} | Select-Object -Unique)[0] -ceq "oficina-migration-$environment") 'Credentials must be Secret references.'
        Assert (($container.env | Where-Object name -CEQ 'FLYWAY_URL').value.Contains('sslmode=verify-full')) 'DB TLS required.'
        $patch=Get-Content "$temp/rendered/rollout-patch.json" -Raw | ConvertFrom-Json
        Assert ($patch.spec.strategy.type -ceq 'Recreate') 'FirstWriter requires Recreate.'
        Assert (($patch.spec.template.spec.containers[0].env | Where-Object name -CEQ 'SPRING_FLYWAY_ENABLED').value -ceq 'false') 'Cloud automatic migration disabled.'
        Assert (($patch.spec.template.spec.containers[0].env | Where-Object name -CEQ 'SPRING_JPA_HIBERNATE_DDL_AUTO').value -ceq 'validate') 'Hibernate must not mutate cloud schema.'
        Invoke-Fixture -Execute
        $calls=$global:appRolloutFixture.Calls -join "`n"
        Assert ($calls -match '(?s)delete hpa.*drain-patch.json.*get pods.*create .*migration-job.json.*wait job/.*get job.*rollout-patch.json.*rollout status.*apply .*hpa.json') 'Strict first-writer sequencing.'
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
    Write-Output "PASS: $assertions APP rollout assertions; kubectl mocked, no AWS calls."
} finally {
    Remove-Variable -Name appRolloutFixture -Scope Global
    $resolved=[IO.Path]::GetFullPath($temp)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('oficina-app-rollout-')) { throw 'Unsafe test cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
