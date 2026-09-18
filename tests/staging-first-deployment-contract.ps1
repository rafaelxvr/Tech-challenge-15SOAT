[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
. "$repo/scripts/app-release-contract.ps1"
$temp = Join-Path ([IO.Path]::GetTempPath()) ('oficina-first-deployment-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
$global:firstDeploymentChecks = 0
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message }; $global:firstDeploymentChecks++ }
function Reject([scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true; Assert (-not $_.Exception.Message.Contains('secret-canary')) 'Raw command output escaped.' }
    Assert $rejected 'Expected fail-closed rejection.'
}
function Save([object]$Value, [string]$Name) { $Value | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath "$temp/$Name.json" -NoNewline }
function aws {
    $f=$global:firstDeploymentFixture; $a=@($args); $f.Calls.Add('aws '+($a -join ' ')); $global:LASTEXITCODE=0
    switch ($a[1]) {
        'put-object' {
            if($f.Failure -ceq 'lock') { $global:LASTEXITCODE=1; return }
            $payload=Get-Content -LiteralPath $a[[array]::IndexOf($a,'--body')+1] -Raw | ConvertFrom-Json
            $f.Owner=$payload.ownerToken; $f.Locked=$true; return '{}'
        }
        'head-object' { return (@{Metadata=@{owner=$f.Owner};ETag='"exact-owner-etag"'} | ConvertTo-Json) }
        'delete-object' { Assert ($a -contains '--if-match') 'Unlock must retain ETag guard.'; $f.Locked=$false; return '{}' }
        default { throw 'Unexpected AWS operation in offline test.' }
    }
}
function kubectl {
    $f=$global:firstDeploymentFixture; $a=@($args); $global:LASTEXITCODE=0; $f.Calls.Add('kubectl '+($a -join ' '))
    Assert $f.Locked 'Every cluster call must hold the shared lock.'
    Assert ($a[0] -ceq '--context' -and $a[1] -ceq $f.Release.kubeContext -and $a[2] -ceq '--namespace' -and $a[3] -ceq 'oficina-staging') 'Context/namespace must be explicit.'
    $verb=$a[4]; $kind=$a[5]
    if ($f.Failure -ceq 'read' -or ($f.Failure -ceq 'create' -and $verb -ceq 'create') -or
        ($f.Failure -ceq 'migration' -and $verb -ceq 'wait') -or ($f.Failure -ceq 'rollout' -and $verb -ceq 'rollout')) {
        $global:LASTEXITCODE=1; return 'secret-canary: restricted executor error'
    }
    if ($verb -ceq 'get') {
        if ($f.Responses.ContainsKey($kind)) { return $f.Responses[$kind] }
        if ($kind -ceq 'pods') {
            $podList=@{items=@()}
            if($f.Failure -ceq 'orphan') { $podList.items=@(@{metadata=@{name='orphan'}}) }
            return ($podList | ConvertTo-Json -Depth 10)
        }
        if ($kind -ceq 'job') { return (@{apiVersion='batch/v1';kind='Job';metadata=@{name=$a[6];namespace='oficina-staging'};status=@{conditions=@(@{type='Complete';status='True'})};spec=@{template=@{spec=@{containers=@(@{image=$f.Release.migrationImage})}}}} | ConvertTo-Json -Depth 12) }
        if ($f.Objects.ContainsKey($kind)) { return ($f.Objects[$kind] | ConvertTo-Json -Depth 50) }
        Assert ($a -contains '--ignore-not-found=true') 'Only successful ignore-not-found may prove absence.'
        return ''
    }
    if ($verb -ceq 'create') {
        $item=Get-Content -LiteralPath $a[-1] -Raw | ConvertFrom-Json
        if ($item.kind -ceq 'Deployment') {
            Assert ($item.spec.replicas -eq 0 -and $item.spec.strategy.type -ceq 'Recreate') 'Fresh Deployment must start with zero writers.'
            Assert ($item.spec.template.spec.containers[0].resources.requests.memory -ceq '768Mi') 'Platform resource requests must survive bootstrap.'
            $f.Objects.deployment=$item
        }
        if ($item.kind -ceq 'ServiceAccount') { $f.Objects.serviceaccount=$item }
    }
    if ($verb -ceq 'patch' -and $a[-1].EndsWith('drain-patch.json')) { $f.Objects.deployment.spec.replicas=0 }
    if ($verb -ceq 'wait' -and $f.Failure -ceq 'window-expires') {
        $f.Window.windowEndUtc=[DateTimeOffset]::UtcNow.AddMinutes(-1).ToString('o'); Save $f.Window window
    }
    return 'mock accepted'
}
function Fixture([switch]$Existing) {
    $prefix='123456789012.dkr.ecr.us-east-1.amazonaws.com/'
    $platform=@{Environment='staging';Image=($prefix+'oficina@sha256:'+('a'*64));DbHost='private.example.test';AppIrsaRoleArn='arn:aws:iam::123456789012:role/oficina-staging-app'}
    Save $platform platform
    [IO.File]::WriteAllText("$temp/source.zip",'reviewed fixture source bytes')
    $deployment=@{apiVersion='apps/v1';kind='Deployment';metadata=@{name='oficina-app';namespace='oficina-staging'};spec=@{replicas=1;strategy=@{type='RollingUpdate'};selector=@{matchLabels=@{'app.kubernetes.io/name'='oficina-app'}};template=@{metadata=@{labels=@{'app.kubernetes.io/name'='oficina-app'}};spec=@{serviceAccountName='oficina-app';containers=@(@{name='app';image=$platform.Image;env=@(@{name='SPRING_FLYWAY_ENABLED';value='false'},@{name='SPRING_JPA_HIBERNATE_DDL_AUTO';value='validate'});resources=@{requests=@{memory='768Mi'}}})}}}}
    $account=@{apiVersion='v1';kind='ServiceAccount';metadata=@{name='oficina-app';namespace='oficina-staging';annotations=@{'eks.amazonaws.com/role-arn'=$platform.AppIrsaRoleArn}}}
    $hpa=@{apiVersion='autoscaling/v2';kind='HorizontalPodAutoscaler';metadata=@{name='oficina-app';namespace='oficina-staging'};spec=@{minReplicas=1;maxReplicas=2;scaleTargetRef=@{apiVersion='apps/v1';kind='Deployment';name='oficina-app'};metrics=@(@{type='Resource';resource=@{name='cpu';target=@{type='Utilization';averageUtilization=60}}})}}
    $bundle=@{apiVersion='v1';kind='List';items=@($deployment,$account,$hpa)}; Save $bundle workload
    $release=@{schemaVersion=1;environment='staging';mode='FirstWriter';sourceCommit=('b'*40);contractVersion='phase3-v2';databaseSchemaVersion='V8';platformInputsSha256=(Get-AppFileHash "$temp/platform.json");image=$platform.Image;previousImage=$platform.Image;migrationImage=($prefix+'flyway@sha256:'+('d'*64));kubeContext='arn:aws:eks:us-east-1:123456789012:cluster/oficina';migrationSecretName='oficina-migration-staging';migrationServiceAccount='oficina-migration-staging';migrationSqlSha256=(Get-AppMigrationDigest);stagingWorkloadSha256=(Get-AppFileHash "$temp/workload.json");artifactSha256=(Get-AppFileHash "$temp/source.zip");deployerImageDigest=('sha256:'+('e'*64))}
    $window=@{windowStartUtc=[DateTimeOffset]::UtcNow.AddHours(-1).ToString('o');windowEndUtc=[DateTimeOffset]::UtcNow.AddHours(1).ToString('o');recordedAtUtc=[DateTimeOffset]::UtcNow.ToString('o');accountEvidenceReference='review/fixture';projectAllowanceUsd=100;reserveUsd=10;currentEstimatedSpendUsd=1}; Save $window window
    $global:firstDeploymentFixture=@{Calls=[Collections.Generic.List[string]]::new();Failure='';Locked=$false;Owner='';Objects=@{};Responses=@{};Release=$release;Bundle=$bundle;Window=$window;SourceKey='releases/app/staging/bundle.zip'}
    if ($Existing) { $global:firstDeploymentFixture.Objects=@{deployment=$deployment;serviceaccount=$account;hpa=$hpa} }
}
function Run([switch]$Execute) {
    $f=$global:firstDeploymentFixture; Save $f.Release release
    & "$repo/scripts/deploy-app.ps1" -ReleaseFile "$temp/release.json" -ExpectedReleaseSha256 (Get-AppFileHash "$temp/release.json") -PlatformInputsFile "$temp/platform.json" -OutputDirectory "$temp/rendered" -StagingWorkloadFile "$temp/workload.json" -CloudWindowEvidenceFile "$temp/window.json" -StateBucket 'fixture-state-bucket' -SourceArchiveFile "$temp/source.zip" -SourceKey $f.SourceKey -ExpectedDeployerImageDigest ('sha256:'+('e'*64)) -ExecuteReviewedPlan:$Execute | Out-Null
}
try {
    Fixture; Run
    Assert ($global:firstDeploymentFixture.Calls.Count -eq 0) 'Default render cannot contact AWS or Kubernetes.'
    Assert ((Get-Content "$temp/rendered/bootstrap-deployment.json" -Raw | ConvertFrom-Json).spec.replicas -eq 0) 'Reviewed bootstrap render is inert.'
    Run -Execute
    $calls=$global:firstDeploymentFixture.Calls -join "`n"
    Assert ($calls -match '(?s)put-object.*bootstrap-serviceaccount.json.*bootstrap-deployment.json.*migration-job.json.*wait job/.*rollout-patch.json.*rollout status.*apply .*hpa.json.*head-object.*delete-object') 'Fresh cluster must keep lock through zero-writer creation, migration, rollout and HPA.'
    Assert (-not $calls.Contains('delete hpa')) 'Absent HPA must not be deleted.'
    Assert (-not $global:firstDeploymentFixture.Locked) 'Success must release its own lock.'
    Fixture -Existing; Run -Execute
    $calls=$global:firstDeploymentFixture.Calls -join "`n"
    Assert ($calls.Contains('delete hpa') -and -not $calls.Contains('bootstrap-deployment.json')) 'Existing workload must use the validated drain path without bootstrap writes.'
    foreach ($kind in @('deployment','hpa','serviceaccount')) {
        foreach ($response in @('null','{}','[]','[{}]','true','"text"','0','{')) {
            Fixture; $global:firstDeploymentFixture.Responses[$kind]=$response
            Reject { Run -Execute }
            $calls=$global:firstDeploymentFixture.Calls -join "`n"
            Assert (-not ($calls -match 'create -f|patch deployment|delete hpa|apply -f')) 'Malformed nonempty response cannot imply absence or allow writes/migration.'
            Assert (-not $global:firstDeploymentFixture.Locked) 'Rejected cluster response must release its lock.'
        }
        foreach ($mutation in @(
            {param($item) $item.kind='Job'},
            {param($item) $item.metadata.name='other-app'},
            {param($item) $item.metadata.namespace='oficina-production'},
            {param($item) $item.apiVersion='wrong/v1'},
            {param($item) $item.kind=@($item.kind)},
            {param($item) $item.metadata.name=@($item.metadata.name)},
            {param($item) $item.metadata.namespace=$null},
            {param($item) $item.Remove('metadata')}
        )) {
            Fixture -Existing; & $mutation $global:firstDeploymentFixture.Objects[$kind]
            Reject { Run -Execute }
            $calls=$global:firstDeploymentFixture.Calls -join "`n"
            Assert (-not ($calls -match 'create -f|patch deployment|delete hpa|apply -f')) 'Wrong cluster object identity must fail before writes/migration.'
            Assert (-not $global:firstDeploymentFixture.Locked) 'Rejected identity must release its lock.'
        }
    }
    foreach ($failure in @('read','create','migration','rollout','orphan','window-expires','lock')) {
        Fixture; $global:firstDeploymentFixture.Failure=$failure; Reject { Run -Execute }
        $calls=$global:firstDeploymentFixture.Calls -join "`n"
        Assert (-not $calls.Contains('apply -f')) 'Failure cannot restore HPA.'
        Assert (-not $global:firstDeploymentFixture.Locked) 'Failure must release its own lock.'
        Assert ((Get-Content "$temp/rendered/rollout-receipt.json" -Raw | ConvertFrom-Json).status -cne 'ROLLOUT_COMPLETE') 'Failed run cannot retain success.'
        if($failure -cin @('read','create','orphan','lock')) { Assert (-not $calls.Contains('migration-job.json')) 'Preflight failure cannot migrate.' }
        if($failure -cin @('migration','window-expires')) { Assert (-not $calls.Contains('rollout-patch.json')) 'Migration/window failure cannot enable writers.' }
    }
    foreach ($missing in @('deployment','serviceaccount','hpa')) {
        Fixture -Existing; $global:firstDeploymentFixture.Objects.Remove($missing); Reject { Run -Execute }
        Assert (-not (($global:firstDeploymentFixture.Calls -join "`n") -match 'create -f|patch deployment|delete hpa')) 'Partial workload must be left intact.'
    }
    Fixture -Existing; $global:firstDeploymentFixture.Objects.Remove('deployment'); $global:firstDeploymentFixture.Objects.Remove('hpa'); Reject { Run -Execute }
    Assert (-not (($global:firstDeploymentFixture.Calls -join "`n").Contains('create -f'))) 'Two absent resources cannot trigger repair.'
    Fixture; $global:firstDeploymentFixture.Release.previousImage=$global:firstDeploymentFixture.Release.image.Replace(('a'*64),('c'*64)); Reject { Run -Execute }
    Assert (-not (($global:firstDeploymentFixture.Calls -join "`n").Contains('create -f'))) 'Fresh deployment requires an explicit no-previous-workload binding.'
    foreach ($mutation in @(
        {$global:firstDeploymentFixture.Objects.deployment.spec.template.spec.containers[0].image='unreviewed:latest'},
        {$global:firstDeploymentFixture.Objects.serviceaccount.metadata.annotations.'eks.amazonaws.com/role-arn'='wrong-role'},
        {$global:firstDeploymentFixture.Objects.hpa.spec.maxReplicas=10}
    )) {
        Fixture -Existing; & $mutation; Reject { Run -Execute }
        Assert (-not (($global:firstDeploymentFixture.Calls -join "`n") -match 'create -f|patch deployment|delete hpa')) 'Existing drift must fail before mutations.'
    }
    foreach ($mutation in @(
        {$global:firstDeploymentFixture.Release.stagingWorkloadSha256=('0'*64)},
        {$global:firstDeploymentFixture.Release.stagingWorkloadSha256=@('a'*64)},
        {$global:firstDeploymentFixture.Release.artifactSha256=('0'*64)},
        {$global:firstDeploymentFixture.Release.deployerImageDigest=('sha256:'+('0'*64))},
        {$global:firstDeploymentFixture.SourceKey='releases/application/staging/bundle.zip'},
        {$global:firstDeploymentFixture.Release.mode='Compatible'},
        {$global:firstDeploymentFixture.Release.environment='production'},
        {$global:firstDeploymentFixture.Window.windowEndUtc=[DateTimeOffset]::UtcNow.AddMinutes(-1).ToString('o'); Save $global:firstDeploymentFixture.Window window}
    )) { Fixture; & $mutation; Reject { Run -Execute }; Assert ($global:firstDeploymentFixture.Calls.Count -eq 0) 'Unreviewed inputs or closed window must fail before external calls.' }
    foreach ($invalid in @(@(), @('Deployment'), $null)) {
        Fixture; $global:firstDeploymentFixture.Bundle.items[0].kind=$invalid; Save $global:firstDeploymentFixture.Bundle workload
        $global:firstDeploymentFixture.Release.stagingWorkloadSha256=Get-AppFileHash "$temp/workload.json"
        Reject { Run -Execute }; Assert ($global:firstDeploymentFixture.Calls.Count -eq 0) 'Malformed bundle scalar must fail before external calls.'
    }
    Fixture
    $platform=Get-Content "$temp/platform.json" -Raw | ConvertFrom-Json
    $platform.Environment='production'; $platform.AppIrsaRoleArn='arn:aws:iam::123456789012:role/oficina-production-app'; Save $platform platform
    $global:firstDeploymentFixture.Release.environment='production'; $global:firstDeploymentFixture.Release.migrationSecretName='oficina-migration-production'; $global:firstDeploymentFixture.Release.migrationServiceAccount='oficina-migration-production'
    $global:firstDeploymentFixture.Release.platformInputsSha256=Get-AppFileHash "$temp/platform.json"
    Reject { Run -Execute }; Assert ($global:firstDeploymentFixture.Calls.Count -eq 0) 'Even a valid production release cannot initialize workloads.'
    Write-Output "PASS: $global:firstDeploymentChecks staging first-deployment assertions; AWS/kubectl mocked."
} finally {
    Remove-Variable -Name firstDeploymentFixture -Scope Global -ErrorAction SilentlyContinue
    Remove-Variable -Name firstDeploymentChecks -Scope Global -ErrorAction SilentlyContinue
    $resolved=[IO.Path]::GetFullPath($temp)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('oficina-first-deployment-')) { throw 'Unsafe cleanup target.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
