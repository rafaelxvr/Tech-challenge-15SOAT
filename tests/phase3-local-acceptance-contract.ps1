[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$harness=Join-Path $repo 'scripts/phase3-local-acceptance.ps1'
$guard=Join-Path $repo 'scripts/phase3-local-command-guard.ps1'
$command=Get-Command $harness
$count=0
foreach($name in @('AppRepository','FunctionsRepository','DatabaseRepository','KubernetesRepository','ReceiptFile')){
    if(-not @($command.Parameters[$name].Attributes|Where-Object {$_ -is [Management.Automation.ParameterAttribute] -and $_.Mandatory}).Count){throw "Required parameter missing: $name"};$count++
}
function Reject([scriptblock]$Action){try{& $Action|Out-Null}catch{if($_.Exception.Message -notlike '*LOCAL_ACCEPTANCE_COMMAND_REJECTED*'){throw};$script:count++;return};throw 'Cloud command was not rejected.'}
Reject {& $guard -Tool aws sts get-caller-identity}
Reject {& $guard -Tool aws s3api put-object}
Reject {& $guard -Tool kubectl apply -f synthetic.yaml}
Reject {& $guard -Tool terraform apply -auto-approve}
Reject {& $guard -Tool terraform plan}
Reject {& $guard -Tool terraform destroy}
$source=Get-Content $harness -Raw
foreach($required in @('tests/pipeline-contract.ps1','app-focused-java','tests/verify-infrastructure.ps1','tests/verify.ps1','tests/application-rollout-tests.ps1','tests/platform-manifests-tests.ps1','tests/newrelic-chart-tests.ps1','tests/workload-capacity-tests.ps1','tests/staging-app-workload-tests.ps1','tests/runtime-public-configmap-tests.ps1','AWS_SHARED_CREDENTIALS_FILE','AWS_CONFIG_FILE','AWS_EC2_METADATA_DISABLED','KUBECONFIG','--no-hardlinks','skippedCloudChecks','logSha256','PASS_LOCAL_ONLY')){
    if(-not $source.Contains($required)){throw "Harness contract missing: $required"};$count++
}
if($source -match '\b(?:git\s+.*(?:fetch|push|reset)|aws\s+(?:sts|s3api)|kubectl\s+apply|terraform\s+apply)'){throw 'Harness contains a prohibited direct operation.'}
Write-Output "PASS: $count local acceptance contracts; all four repository paths required; cloud commands rejected before tool execution."
