Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
. "$repo/scripts/bootstrap-release-contract.ps1"
. "$repo/scripts/migration-identity-contract.ps1"
. "$PSScriptRoot/runtime-public-fixture.ps1"
. "$PSScriptRoot/migration-identity-fixture.ps1"
$temp=Join-Path ([IO.Path]::GetTempPath()) ('migration-identity-'+[guid]::NewGuid())
New-Item -ItemType Directory $temp|Out-Null
$script:checks=0
function Reject([scriptblock]$Action){try{& $Action|Out-Null}catch{$script:checks++;return};throw 'Expected migration identity rejection'}
function Fixture {
    '{}'|Set-Content "$temp/platform.json"
    $r=@{environment='staging';mode='FirstWriter';sourceCommit=('b'*40)}
    Add-RuntimePublicFixture $r "$temp/public.json"
    Add-MigrationIdentityFixture $r "$temp/platform.json"
    $script:release=$r|ConvertTo-Json -Depth 30|ConvertFrom-Json
    $script:platform=Get-Content "$temp/platform.json" -Raw|ConvertFrom-Json
    $script:identity=$platform.MigrationIdentityJson|ConvertFrom-Json
}
function Rebind {
    $platform.MigrationIdentityJson=$identity|ConvertTo-Json -Depth 30 -Compress
    $platform.MigrationIdentitySha256=Get-BootstrapReviewSha256 $platform.MigrationIdentityJson
}
try{
    Fixture;$null=Read-StagingMigrationIdentity $platform $release;$script:checks++
    $sa=New-MigrationServiceAccountFixture|ConvertTo-Json -Depth 10|ConvertFrom-Json
    Assert-MigrationServiceAccount $sa $identity;$script:checks++
    foreach($field in @('MigrationIdentityJson','MigrationIdentitySha256','MigrationNetworkPolicySha256')){
        foreach($value in @('', $null, @(), @('value'))){Fixture;$platform.$field=$value;Reject {Read-StagingMigrationIdentity $platform $release}}
        Fixture;$platform.PSObject.Properties.Remove($field);Reject {Read-StagingMigrationIdentity $platform $release}
    }
    foreach($field in @('environment','sourceCommit','namespace','serviceAccountName','roleArn','oidcIssuer','oidcProviderArn','bootstrapReviewSha256')){
        foreach($value in @('production', $null, @(), @('staging'))){Fixture;$identity.$field=$value;Rebind;Reject {Read-StagingMigrationIdentity $platform $release}}
    }
    Fixture;$release.environment='production';Reject {Read-StagingMigrationIdentity $platform $release}
    Fixture;$platform.MigrationIdentityJson+=' ';Reject {Read-StagingMigrationIdentity $platform $release}
    foreach($slot in @('master','migration','app','auth','notification')){
        Fixture;$identity.secretReferences.$slot.versionId='9'*32;Rebind;Reject {Read-StagingMigrationIdentity $platform $release}
        Fixture;$identity.secretReferences.$slot.kmsKeyManager='CUSTOMER';Rebind;Reject {Read-StagingMigrationIdentity $platform $release}
        Fixture;$identity.secretReferences.$slot.metadataSha256='';Rebind;Reject {Read-StagingMigrationIdentity $platform $release}
    }
    Fixture;$identity.secretReferences.app.kmsKeyManager='CUSTOMER';$identity.secretReferences.app.kmsKeyArn='arn:aws:kms:us-east-1:123456789012:key/11111111-1111-1111-1111-111111111111';Rebind
    $null=Read-StagingMigrationIdentity $platform $release;$script:checks++
    foreach($value in @($null,@(),[pscustomobject]@{})){Reject {Assert-MigrationServiceAccount $value $identity}}
    $sa.metadata.annotations.'eks.amazonaws.com/role-arn'='arn:aws:iam::123456789012:role/oficina-phase3-staging-app';Reject {Assert-MigrationServiceAccount $sa $identity}
    Write-Output "PASS: $script:checks migration identity/hash/secret/KMS/SA assertions; no external commands."
}finally{if(-not [IO.Path]::GetFullPath($temp).StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe cleanup'};Remove-Item -LiteralPath $temp -Recurse -Force}
