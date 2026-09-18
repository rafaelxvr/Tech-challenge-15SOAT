[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$validator=Join-Path $repo 'scripts/check-production-promotion.ps1'
$workflow=Get-Content (Join-Path $repo '.github/workflows/production-contract.yml') -Raw
$staging=Get-Content (Join-Path $repo '.github/workflows/staging-deploy.yml') -Raw
$gate="if: github.event_name == 'push' && github.ref == 'refs/heads/main' && vars.APP_PRODUCTION_DEPLOYMENT_ENABLED == 'true'"
if (-not $workflow.Contains($gate) -or $workflow -notmatch 'branches:\s+- main\s' -or
    $workflow -notmatch 'environment:\s+name: production' -or -not $workflow.Contains('cancel-in-progress: false')) { throw 'Production job must be main-only, explicitly gated and protected.' }
foreach($name in @('APP_PRODUCTION_DEPLOYMENT_ENABLED','APP_PRODUCTION_ROLE_ARN','APP_PRODUCTION_INPUTS_RUN_ID','APP_PRODUCTION_INPUTS_ARTIFACT_ID','APP_PRODUCTION_INPUTS_SHA256')) {
    if(-not $workflow.Contains($name + ': ${{ vars.' + $name + ' }}')) { throw "Missing reviewed workflow variable: $name" }
}
foreach($binding in @('uses: actions/download-artifact@v4','repository: ${{ github.repository }}','run-id: ${{ vars.APP_PRODUCTION_INPUTS_RUN_ID }}','artifact-ids: ${{ vars.APP_PRODUCTION_INPUTS_ARTIFACT_ID }}','app-production-review/production-inputs.json')) {
    if(-not $workflow.Contains($binding)) {throw 'Production inputs must come from the explicitly reviewed same-repository artifact.'}
}
if($workflow -match 'id-token:|configure-aws|start-deploy|deploy-app|secrets\.|workflow_dispatch|pull_request|schedule:|refs/heads/develop') { throw 'Production contract cannot obtain cloud credentials or invoke a launcher.' }
if(-not $staging.Contains("github.ref == 'refs/heads/develop'") -or $staging -match 'production|refs/heads/main') { throw 'develop must remain staging-only.' }
if(-not $workflow.Contains('./scripts/check-production-promotion.ps1')) { throw 'Workflow must execute the tested guard.' }
$script:externalCalls=0
function aws { $script:externalCalls++; throw 'Forbidden AWS call' }
function kubectl { $script:externalCalls++; throw 'Forbidden kubectl call' }
function terraform { $script:externalCalls++; throw 'Forbidden Terraform call' }
$script:count=0
$temp=Join-Path ([IO.Path]::GetTempPath()) ('oficina-production-contract-'+[guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
function Save($value,$name) { [IO.File]::WriteAllText((Join-Path $temp $name),($value|ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false)) }
function Hash($name) { (Get-FileHash (Join-Path $temp $name) -Algorithm SHA256).Hash.ToLowerInvariant() }
function Ref($name) { @{path=$name;sha256=(Hash $name)} }
function Reset-Fixture {
    $script:commit='a'*40
    $image='123456789012.dkr.ecr.us-east-1.amazonaws.com/oficina-phase3-app@sha256:'+('b'*64)
    $script:role='arn:aws:iam::123456789012:role/oficina-app-production-launcher'
    Save @{syntheticSource=$commit} 'source.zip'
    Save @{Environment='production';Image=$image} 'platform.json'
    Save @{environment='production'} 'tfvars.json'
    Save @{windowStartUtc=[datetimeoffset]::UtcNow.AddMinutes(-5).ToString('o');windowEndUtc=[datetimeoffset]::UtcNow.AddMinutes(30).ToString('o');recordedAtUtc=[datetimeoffset]::UtcNow.ToString('o');accountEvidenceReference='synthetic-offline-fixture';projectAllowanceUsd=10;reserveUsd=1;currentEstimatedSpendUsd=0} 'window.json'
    $script:stagingRelease=@{schemaVersion=1;environment='staging';sourceCommit=$commit;artifactSha256=(Hash 'source.zip');image=$image;runtimeArtifactDigest=('sha256:'+('b'*64));contractVersion='phase3-v2';migrationVersion='V8';databaseSchemaVersion='V8'}
    $script:release=$stagingRelease.Clone()
    $release.environment='production';$release.promotedFromStaging=$true;$release.stagingArtifactSha256=Hash 'source.zip'
    $release.deployerImageDigest='sha256:'+('c'*64)
    $release.platformInputsSha256=Hash 'platform.json';$release.terraformVariablesSha256=Hash 'tfvars.json';$release.cloudWindowEvidenceSha256=Hash 'window.json'
    Save $stagingRelease 'staging-release.json'
    $script:receipt=@{schemaVersion=1;environment='staging';sourceCommit=$commit;artifactSha256=(Hash 'source.zip');sourceKey='releases/app/staging/bundle.zip';sourceVersionId='source-v1';releaseManifestKey="releases/app/staging/manifests/$commit.json";releaseManifestVersionId='manifest-v1';releaseManifestSha256=(Hash 'staging-release.json');terraformVariablesKey="releases/app/staging/config/$commit.tfvars.json";terraformVariablesVersionId='config-v1';terraformVariablesSha256=('d'*64);deployerImageDigest=$release.deployerImageDigest;codeBuildProjectName='oficina-phase3-oficina-app-staging-deploy';codeBuildBuildId='oficina-phase3-oficina-app-staging-deploy:00000000-0000-0000-0000-000000000001';buildStatus='SUCCEEDED';issuedAtUtc=[datetimeoffset]::UtcNow.AddMinutes(-1).ToString('o')}
    Save $release 'release.json';Save $receipt 'receipt.json'
    $script:inputs=@{schemaVersion=1;environment='production';sourceCommit=$commit;accountId='123456789012';roleArn=$role;projectName='oficina-phase3-oficina-app-production-deploy';sourcePrefix='releases/app/production';deployerImageDigest=$release.deployerImageDigest;sourceArchive=(Ref 'source.zip');releaseManifest=(Ref 'release.json');platformInputs=(Ref 'platform.json');terraformVariables=(Ref 'tfvars.json');cloudWindowEvidence=(Ref 'window.json');stagingReleaseManifest=(Ref 'staging-release.json');stagingPromotion=(Ref 'receipt.json')}
    $inputs.stagingPromotion.bucket='reviewed-artifact-bucket';$inputs.stagingPromotion.key="releases/app/staging/promotions/$commit.json";$inputs.stagingPromotion.versionId='promotion-v1'
}
function Seal {
    Save $release 'release.json';Save $stagingRelease 'staging-release.json';Save $receipt 'receipt.json'
    $inputs.releaseManifest.sha256=Hash 'release.json';$inputs.stagingReleaseManifest.sha256=Hash 'staging-release.json';$inputs.stagingPromotion.sha256=Hash 'receipt.json'
    Save $inputs 'inputs.json'
    $script:arguments=@{Enabled='true';RoleArn=$role;InputsFile=(Join-Path $temp 'inputs.json');ExpectedInputsSha256=(Hash 'inputs.json');SourceCommit=$commit;EventName='push';BranchRef='refs/heads/main'}
}
function Reject([scriptblock]$Action,[string]$Message) {
    try { & $Action | Out-Null } catch {
        if($_.Exception.Message -notlike "*$Message*") { throw "Expected $Message, received $($_.Exception.Message)" }
        $script:count++;return
    }
    throw "Expected rejection: $Message"
}
try {
    Reset-Fixture;Seal
    $result=& $validator @arguments
    if($result -cne 'PRODUCTION_CONTRACT_VALIDATED_DEPLOYMENT_DISABLED') { throw 'Valid promotion must remain deployment-disabled.' };$script:count++
    foreach($disabled in @('','false','TRUE')) {
        Reset-Fixture;Seal;$arguments.Enabled=$disabled;$arguments.InputsFile='does-not-exist'
        Reject {& $validator @arguments} 'APP_PRODUCTION_GATE_DISABLED'
    }
    foreach($branch in @('refs/heads/develop','refs/heads/feature','refs/tags/main')) {
        Reset-Fixture;Seal;$arguments.BranchRef=$branch
        Reject {& $validator @arguments} 'Deployment context'
    }
    foreach($event in @('pull_request','workflow_dispatch','schedule')) {
        Reset-Fixture;Seal;$arguments.EventName=$event
        Reject {& $validator @arguments} 'Deployment context'
    }
    foreach($invalidRole in @('','arn:aws:iam::123456789012:role/oficina-app-staging-launcher','arn:aws:iam::999999999999:role/oficina-app-production-launcher')) {
        Reset-Fixture;Seal;$arguments.RoleArn=$invalidRole
        Reject {& $validator @arguments} 'APP_PRODUCTION_ROLE_MISMATCH'
    }
    Reset-Fixture;Seal;$arguments.ExpectedInputsSha256=''
    Reject {& $validator @arguments} 'APP_PRODUCTION_INPUT_HASH_MISMATCH'
    Reset-Fixture;Seal;$inputs.Remove('stagingPromotion');Save $inputs 'inputs.json';$arguments.ExpectedInputsSha256=Hash 'inputs.json'
    Reject {& $validator @arguments} 'APP_PRODUCTION_OBJECT_REQUIRED'
    Reset-Fixture;Seal;Remove-Item -LiteralPath (Join-Path $temp 'receipt.json')
    Reject {& $validator @arguments} 'APP_PRODUCTION_FILE_HASH_MISMATCH'
    Reset-Fixture;Seal;[IO.File]::AppendAllText((Join-Path $temp 'receipt.json'),' ')
    Reject {& $validator @arguments} 'APP_PRODUCTION_FILE_HASH_MISMATCH'
    foreach($field in @('environment','sourceCommit','artifactSha256','buildStatus','codeBuildProjectName','codeBuildBuildId','sourceKey','releaseManifestKey','terraformVariablesKey','releaseManifestSha256','deployerImageDigest')) {
        Reset-Fixture;$receipt[$field]='unreviewed';Seal
        Reject {& $validator @arguments} 'APP_STAGING'
    }
    foreach($field in @('sourceCommit','sourceVersionId','buildStatus')) {
        foreach($invalid in @(@(),@('value'),$null)) {
            Reset-Fixture;$receipt[$field]=$invalid;Seal
            Reject {& $validator @arguments} 'APP_PRODUCTION_STRING_REQUIRED'
        }
        Reset-Fixture;$receipt.Remove($field);Seal
        Reject {& $validator @arguments} 'APP_PRODUCTION_STRING_REQUIRED'
    }
    Reset-Fixture;$inputs.stagingPromotion.versionId='null';Seal
    Reject {& $validator @arguments} 'APP_PRODUCTION_STRING_REQUIRED'
    Reset-Fixture;$inputs.stagingPromotion.key='releases/app/production/promotions/unreviewed.json';Seal
    Reject {& $validator @arguments} 'APP_STAGING_RECEIPT_LOCATION_INVALID'
    Reset-Fixture;$receipt.issuedAtUtc=[datetimeoffset]::UtcNow.AddHours(1).ToString('o');Seal
    Reject {& $validator @arguments} 'APP_STAGING_PROMOTION_TIME_INVALID'
    Reset-Fixture;$release.sourceCommit='e'*40;Seal
    Reject {& $validator @arguments} 'APP_PRODUCTION_SOURCE_NOT_PROMOTED'
    Reset-Fixture;$release.promotedFromStaging='true';Seal
    Reject {& $validator @arguments} 'APP_PRODUCTION_PROMOTION_REQUIRED'
    Reset-Fixture;$release.image=$release.image.Replace(('b'*64),('e'*64));Seal
    Reject {& $validator @arguments} 'APP_PRODUCTION_RUNTIME_NOT_PROMOTED'
    Reset-Fixture;Seal;[IO.File]::AppendAllText((Join-Path $temp 'tfvars.json'),' ')
    Reject {& $validator @arguments} 'APP_PRODUCTION_FILE_HASH_MISMATCH'
    Reset-Fixture;$release.platformInputsSha256='f'*64;Seal
    Reject {& $validator @arguments} 'APP_PRODUCTION_BINDING_MISMATCH'
    Reset-Fixture;Save @{windowStartUtc=[datetimeoffset]::UtcNow.AddHours(-2).ToString('o');windowEndUtc=[datetimeoffset]::UtcNow.AddHours(-1).ToString('o');recordedAtUtc=[datetimeoffset]::UtcNow.AddHours(-2).ToString('o');accountEvidenceReference='fixture';projectAllowanceUsd=10;reserveUsd=1;currentEstimatedSpendUsd=0} 'window.json';$inputs.cloudWindowEvidence=Ref 'window.json';$release.cloudWindowEvidenceSha256=Hash 'window.json';Seal
    Reject {& $validator @arguments} 'window is closed'
    if($externalCalls -ne 0) {throw 'A production contract invoked an external command.'}
    $code=Get-Content $validator -Raw
    if($code -match 'start-deploy\.ps1|deploy-app\.ps1|Invoke-Expression|Start-Process|&\s+(aws|kubectl|terraform)') {throw 'Validator must not invoke a production launcher or cloud command.'}
    Write-Output "PASS: $count production promotion contracts; main gate defaults closed, develop stays staging-only, missing/altered receipt rejected, zero production launches."
}
finally {
    $resolved=[IO.Path]::GetFullPath($temp)
    if(-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('oficina-production-contract-')) {throw 'Unsafe cleanup target.'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
