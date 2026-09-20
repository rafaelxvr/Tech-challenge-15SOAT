Set-StrictMode -Version Latest;$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
. "$repo/scripts/app-prerequisites-contract.ps1"
. "$PSScriptRoot/runtime-public-fixture.ps1"
. "$PSScriptRoot/migration-identity-fixture.ps1"
$temp=Join-Path ([IO.Path]::GetTempPath()) ('app-prerequisite-test-'+[guid]::NewGuid());New-Item -ItemType Directory $temp|Out-Null
$script:checks=0
function Reject([scriptblock]$action){try{& $action|Out-Null}catch{$script:checks++;return};throw 'Expected rejection'}
function Save-Receipt($receipt){$platform.PlatformPrerequisitesReceiptJson=$receipt|ConvertTo-Json -Depth 70 -Compress;$platform.PlatformPrerequisitesReceiptSha256=Get-PrerequisiteHash $platform.PlatformPrerequisitesReceiptJson}
function Fixture {
 '{}'|Set-Content "$temp/platform.json"
 $r=@{environment='staging';mode='FirstWriter';sourceCommit=('b'*40)};Add-RuntimePublicFixture $r "$temp/public.json";Add-MigrationIdentityFixture $r "$temp/platform.json"
 $script:release=$r|ConvertTo-Json -Depth 70|ConvertFrom-Json;$script:platform=Get-Content "$temp/platform.json" -Raw|ConvertFrom-Json
}
try{
 Fixture;$null=Read-AppPrerequisites $platform $release;$script:checks++
 $before=$platform.PlatformPrerequisitesReceiptJson
 $reviewed=Read-AppPrerequisites $platform $release
 if($reviewed.reviewedReadback.Count -ne 8 -or $platform.PlatformPrerequisitesReceiptJson -cne $before){throw 'Full eight-object readback and source receipt bytes must be retained'};$script:checks++
 foreach($mutation in @(
   {param($o)$o.spec.ipAddressType='ipv6'},
   {param($o)$o.spec.ipAddressType=@('ipv4')},
   {param($o)$o.spec.vpcID=$null},
   {param($o)$o.spec.vpcID=@('vpc-0123456789abcdef0')},
   {param($o)$o.spec.vpcID='invalid'},
   {param($o)$o.spec|Add-Member unreviewedField 'value'},
   {param($o)$o.spec.targetGroupARN+='changed'},
   {param($o)$o.spec.targetType='instance'},
   {param($o)$o.spec.serviceRef.name='other'},
   {param($o)$o.spec.serviceRef.port=80}
 )){
   Fixture;$receipt=$platform.PlatformPrerequisitesReceiptJson|ConvertFrom-Json
   $binding=@($receipt.objects|Where-Object kind -CEQ 'TargetGroupBinding')[0]
   & $mutation $binding;Save-Receipt $receipt;Reject {Read-AppPrerequisites $platform $release}
 }
 foreach($field in @('vpcID','ipAddressType')){
   Fixture;$bundle=$platform.StagingPrerequisitesJson|ConvertFrom-Json
   $expected=@($bundle.objects|Where-Object kind -CEQ 'TargetGroupBinding')[0]
   $expected.spec|Add-Member $field $(if($field -ceq 'vpcID'){'vpc-0123456789abcdef0'}else{'ipv4'})
   $platform.StagingPrerequisitesJson=$bundle|ConvertTo-Json -Depth 70 -Compress
   $platform.StagingPrerequisitesSha256=Get-PrerequisiteHash $platform.StagingPrerequisitesJson
   $receipt=$platform.PlatformPrerequisitesReceiptJson|ConvertFrom-Json;$receipt.bundleSha256=$platform.StagingPrerequisitesSha256;Save-Receipt $receipt
   $null=Read-AppPrerequisites $platform $release;$script:checks++
   $binding=@($receipt.objects|Where-Object kind -CEQ 'TargetGroupBinding')[0]
   $binding.spec.$field=if($field -ceq 'vpcID'){'vpc-fedcba98765432100'}else{'ipv6'}
   Save-Receipt $receipt;Reject {Read-AppPrerequisites $platform $release}
 }
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
