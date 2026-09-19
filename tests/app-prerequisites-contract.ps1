Set-StrictMode -Version Latest;$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
. "$repo/scripts/app-prerequisites-contract.ps1"
. "$PSScriptRoot/runtime-public-fixture.ps1"
. "$PSScriptRoot/migration-identity-fixture.ps1"
$temp=Join-Path ([IO.Path]::GetTempPath()) ('app-prerequisite-test-'+[guid]::NewGuid());New-Item -ItemType Directory $temp|Out-Null
$script:checks=0
function Reject([scriptblock]$action){try{& $action|Out-Null}catch{$script:checks++;return};throw 'Expected rejection'}
function Fixture {
 '{}'|Set-Content "$temp/platform.json"
 $r=@{environment='staging';mode='FirstWriter';sourceCommit=('b'*40)};Add-RuntimePublicFixture $r "$temp/public.json";Add-MigrationIdentityFixture $r "$temp/platform.json"
 $script:release=$r|ConvertTo-Json -Depth 70|ConvertFrom-Json;$script:platform=Get-Content "$temp/platform.json" -Raw|ConvertFrom-Json
}
try{
 Fixture;$null=Read-AppPrerequisites $platform $release;$script:checks++
 foreach($field in @('StagingPrerequisitesJson','StagingPrerequisitesSha256','PlatformPrerequisitesReceiptJson','PlatformPrerequisitesReceiptSha256','PlatformPrerequisitesReceiptBucket','PlatformPrerequisitesReceiptKey','PlatformPrerequisitesReceiptVersionId')){
   foreach($value in @('', $null, @(), @('value'))){Fixture;$platform.$field=$value;Reject {Read-AppPrerequisites $platform $release}}
   Fixture;$platform.PSObject.Properties.Remove($field);Reject {Read-AppPrerequisites $platform $release}
 }
 foreach($field in @('status','environment','appSourceCommit','k8sSourceCommit','bundleSha256')){
   foreach($value in @('wrong',$null,@(),@('staging'))){Fixture;$receipt=$platform.PlatformPrerequisitesReceiptJson|ConvertFrom-Json;$receipt.$field=$value;$platform.PlatformPrerequisitesReceiptJson=$receipt|ConvertTo-Json -Depth 70 -Compress;$platform.PlatformPrerequisitesReceiptSha256=Get-PrerequisiteHash $platform.PlatformPrerequisitesReceiptJson;Reject {Read-AppPrerequisites $platform $release}}
 }
 Fixture;$platform.StagingPrerequisitesJson+=' ';Reject {Read-AppPrerequisites $platform $release}
 Fixture;$receipt=$platform.PlatformPrerequisitesReceiptJson|ConvertFrom-Json;$receipt.objects=@($receipt.objects|Select-Object -Skip 1);$platform.PlatformPrerequisitesReceiptJson=$receipt|ConvertTo-Json -Depth 70 -Compress;$platform.PlatformPrerequisitesReceiptSha256=Get-PrerequisiteHash $platform.PlatformPrerequisitesReceiptJson;Reject {Read-AppPrerequisites $platform $release}
 Fixture;$release.environment='production';Reject {Read-AppPrerequisites $platform $release}
 Fixture;$bundle=$platform.StagingPrerequisitesJson|ConvertFrom-Json;$bundle.targetGroupArn=$bundle.targetGroupArn.Replace('staging','production');$platform.StagingPrerequisitesJson=$bundle|ConvertTo-Json -Depth 70 -Compress;$platform.StagingPrerequisitesSha256=Get-PrerequisiteHash $platform.StagingPrerequisitesJson;Reject {Read-AppPrerequisites $platform $release}
 Write-Output "PASS: $script:checks APP prerequisite artifact/receipt rejection checks; no external calls."
}finally{if(-not[IO.Path]::GetFullPath($temp).StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe cleanup'};Remove-Item -LiteralPath $temp -Recurse -Force}
