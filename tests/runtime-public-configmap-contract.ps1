$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
$repo=Split-Path -Parent $PSScriptRoot
. "$repo/scripts/runtime-public-configmap-contract.ps1"
$temp=Join-Path ([IO.Path]::GetTempPath()) ('oficina-public-contract-'+[guid]::NewGuid());New-Item -ItemType Directory $temp|Out-Null
$checks=0
function Assert($ok,$message){if(-not$ok){throw $message};$script:checks++}
try {
 $ca="-----BEGIN CERTIFICATE-----`nfixture-public-certificate`n-----END CERTIFICATE-----`n"
 $data=@{'staff-issuer'='oficina-staging-staff';'staff-audience'='oficina-staging-api';'staff-key-id'='staff-test';'customer-issuer'='oficina-staging-customer';'customer-audience'='oficina-staging-api';'history-zone'='UTC';'notification-queue-url'='https://sqs.us-east-1.amazonaws.com/123456789012/oficina-phase3-staging-notifications.fifo';'customer-public-keys.yaml'="security:`n  jwt:`n    customer:`n      public-keys:`n        test: |`n          -----BEGIN PUBLIC KEY-----`n          fixture-public-key`n          -----END PUBLIC KEY-----`n";'rds-ca.pem'=$ca}
 $cm=@{apiVersion='v1';kind='ConfigMap';metadata=@{name='oficina-runtime-public-staging';namespace='oficina-staging';labels=@{'app.kubernetes.io/part-of'='oficina';'app.kubernetes.io/managed-by'='oficina-k8s-infra'}};data=$data}
 $release=@{environment='staging';mode='FirstWriter';sourceCommit=('a'*40);image=('123456789012.dkr.ecr.us-east-1.amazonaws.com/oficina@sha256:'+('b'*64));bootstrapReview=@{caSha256=([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($ca))).ToLowerInvariant())}}
 function Save-Case($value){$value|ConvertTo-Json -Depth 15|Set-Content "$temp/public.json" -NoNewline;$release.runtimePublicConfigMapSha256=(Get-FileHash "$temp/public.json").Hash.ToLowerInvariant();return ($release|ConvertTo-Json -Depth 15|ConvertFrom-Json)}
 $r=Save-Case $cm;$object=Read-StagingPublicConfigMap "$temp/public.json" $r
 Assert ($object.metadata.name -ceq 'oficina-runtime-public-staging') 'Source identity lost'
 foreach($case in @('missing','hash','production','kind','namespace','name','unknown-key','null','array','singleton','private','ca','queue')){
  $copy=$cm|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable
  switch($case){'kind'{$copy.kind='Secret'}'namespace'{$copy.metadata.namespace='oficina-production'}'name'{$copy.metadata.name='arbitrary'}'unknown-key'{$copy.data.password='forbidden'}'null'{$copy.data['staff-key-id']=$null}'array'{$copy.data['staff-key-id']=@()}'singleton'{$copy.data['staff-key-id']=@('staff-test')}'private'{$copy.data['customer-public-keys.yaml']='-----BEGIN PRIVATE KEY-----'}'ca'{$copy.data['rds-ca.pem']='altered'}'queue'{$copy.data['notification-queue-url']=$copy.data['notification-queue-url'].Replace('123456789012','999999999999')}}
  $r=Save-Case $copy;if($case -ceq 'production'){$r.environment='production'};if($case -ceq 'hash'){$r.runtimePublicConfigMapSha256='0'*64};if($case -ceq 'missing'){$r.PSObject.Properties.Remove('runtimePublicConfigMapSha256')}
  $rejected=$false;try{$null=Read-StagingPublicConfigMap "$temp/public.json" $r}catch{$rejected=$true};Assert $rejected "Accepted $case"
 }
 $r=Save-Case $cm
 $script:downloadCalls=0;$script:downloadMode='valid'
 function aws {
  $script:downloadCalls++;$global:LASTEXITCODE=0
  if($script:downloadMode -ceq 'failed'){$global:LASTEXITCODE=1;return 'restricted-output'}
  Copy-Item -LiteralPath "$temp/public.json" -Destination $args[-3]
  if($script:downloadMode -ceq 'altered'){Add-Content -LiteralPath $args[-3] -Value 'altered'}
  if($script:downloadMode -ceq 'version'){return '{"VersionId":"wrong-version"}'}
  return '{"VersionId":"immutable-version"}'
 }
 $key="releases/app/staging/inputs/$($r.sourceCommit)/runtime-public.json"
 $null=Receive-StagingPublicConfigMap $r 'fixture-bucket' $key 'immutable-version' $r.runtimePublicConfigMapSha256 "$temp/download.json"
 Assert ($script:downloadCalls -eq 1) 'Exact version download not invoked'
 foreach($bad in @(@{key=$key.Replace('/staging/','/production/');version='immutable-version';hash=$r.runtimePublicConfigMapSha256},@{key=$key;version='null';hash=$r.runtimePublicConfigMapSha256},@{key=$key;version='immutable-version';hash=('0'*64)})){
  $before=$script:downloadCalls;$rejected=$false;try{$null=Receive-StagingPublicConfigMap $r 'fixture-bucket' $bad.key $bad.version $bad.hash "$temp/download.json"}catch{$rejected=$true};Assert ($rejected -and $before -eq $script:downloadCalls) 'Invalid immutable binding reached download'
 }
 foreach($mode in @('failed','altered','version')){$script:downloadMode=$mode;$rejected=$false;try{$null=Receive-StagingPublicConfigMap $r 'fixture-bucket' $key 'immutable-version' $r.runtimePublicConfigMapSha256 "$temp/download.json"}catch{$rejected=$true};Assert $rejected "Accepted bad download: $mode"}
 Write-Output "PASS: $checks public ConfigMap validation assertions; no cloud calls."
}finally{Remove-Item -LiteralPath "$temp/public.json","$temp/download.json" -Force -ErrorAction SilentlyContinue;Remove-Item -LiteralPath $temp}
