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
# Execute the harness's actual receipt assignments for empty/singleton skip
# lists. Conditional pipeline output otherwise unwraps arrays under StrictMode.
$ast=[Management.Automation.Language.Parser]::ParseInput($source,[ref]$null,[ref]$null)
$skipAssignment=$ast.Find({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -ceq '$receipt[''skippedLocalChecks'']'},$true)
$statusAssignment=$ast.Find({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -ceq '$receipt.status' -and $node.Right.Extent.Text.StartsWith('if(@(')},$true)
if($null -eq $skipAssignment -or $null -eq $statusAssignment){throw 'Receipt aggregation statements missing.'}
foreach($helmAvailable in @($true,$false)){
    foreach($failed in @($false,$true)){
        $receipt=@{suites=@(@{status=$(if($failed){'FAIL'}else{'PASS'})});status='RUNNING'}
        & ([scriptblock]::Create($skipAssignment.Extent.Text))
        & ([scriptblock]::Create($statusAssignment.Extent.Text))
        $expected=if($failed){'FAIL'}elseif($helmAvailable){'PASS_LOCAL_ONLY'}else{'PASS_LOCAL_WITH_SKIPS'}
        if($receipt.skippedLocalChecks -isnot [array] -or $receipt.status -cne $expected){throw 'Receipt skip-array/status contract failed.'}
        $count++
    }
}
# Exercise the actual native process boundary. In-process guard calls cannot
# detect pwsh -File splitting the colon in a Windows -chdir argument.
$entry=Join-Path $repo 'scripts/phase3-local-native-entry.ps1'
if(-not $source.Contains("phase3-local-native-entry.ps1")){throw 'Harness must use the tested native entry point.'}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('phase3-native-contract-'+[guid]::NewGuid())
$snapshot=Join-Path $temp 'snapshots/repository with spaces'
$scratch=Join-Path $temp 'scratch/decoder with spaces'
foreach($path in @($snapshot,$scratch)){New-Item -ItemType Directory -Path $path -Force|Out-Null}
$saved=@{}
foreach($name in @('PHASE3_REAL_TERRAFORM','PHASE3_SNAPSHOT_ROOT','PHASE3_SCRATCH_ROOT','PHASE3_TEST_ARGV','TF_CLI_CONFIG_FILE','TF_CLI_ARGS','TF_CLI_ARGS_console','TF_CLI_ARGS_output')){
    $saved[$name]=[Environment]::GetEnvironmentVariable($name)
}
try {
    $terraform=(Get-Command terraform -CommandType Application -ErrorAction Stop|Select-Object -First 1).Source
    $env:PHASE3_SNAPSHOT_ROOT=Join-Path $temp 'snapshots'
    $env:PHASE3_SCRATCH_ROOT=Join-Path $temp 'scratch'
    $env:PHASE3_TEST_ARGV=Join-Path $temp 'argv.json'
    $empty=Join-Path $temp 'empty-config';[IO.File]::WriteAllText($empty,'')
    $env:TF_CLI_CONFIG_FILE=$empty
    $env:TF_CLI_ARGS=$null;$env:TF_CLI_ARGS_console=$null;$env:TF_CLI_ARGS_output=$null
    $spy=Join-Path $temp 'terraform-spy.ps1'
    [IO.File]::WriteAllText($spy,'[IO.File]::WriteAllText($env:PHASE3_TEST_ARGV,(ConvertTo-Json -InputObject $args -Compress)); exit 17')
    $env:PHASE3_REAL_TERRAFORM=$spy
    $pwsh=Join-Path $PSHOME $(if($IsWindows){'pwsh.exe'}else{'pwsh'})
    # Same fixed invocation as the PATH shim, including CMD on Windows.
    if($IsWindows){
        $shim=Join-Path $temp 'terraform.cmd'
        [IO.File]::WriteAllText($shim,"@echo off`r`n`"$pwsh`" -NoProfile -NonInteractive -File `"$entry`" terraform %*`r`nexit /b %errorlevel%`r`n")
    } else {
        $shim=Join-Path $temp 'terraform'
        [IO.File]::WriteAllText($shim,"#!/bin/sh`nexec '$pwsh' -NoProfile -NonInteractive -File '$entry' terraform `"`$@`"`n")
        & chmod +x $shim
    }
    foreach($flags in @(@('fmt','-check'),@('validate','-no-color'),@('init','-backend=false','-input=false','-lockfile=readonly','-no-color'))){
        $expected=@("-chdir=$snapshot")+$flags
        & $shim @expected
        if($LASTEXITCODE -ne 17){throw 'Native shim must preserve the tool exit code.'}
        $actual=Get-Content $env:PHASE3_TEST_ARGV -Raw|ConvertFrom-Json
        if((ConvertTo-Json -InputObject $actual -Compress) -cne (ConvertTo-Json -InputObject $expected -Compress)){throw 'Native shim changed the exact Terraform argument vector.'}
        $count++
    }
    foreach($blocked in @(@('apply','-auto-approve'),@('plan'),@('destroy'),@('init','-backend=true'))){
        Remove-Item -LiteralPath $env:PHASE3_TEST_ARGV
        $result=& $shim "-chdir=$snapshot" @blocked 2>&1
        if($LASTEXITCODE -eq 0 -or ($result|Out-String) -notmatch 'LOCAL_ACCEPTANCE_COMMAND_REJECTED' -or (Test-Path $env:PHASE3_TEST_ARGV)){throw 'Rejected native command reached Terraform.'}
        # Recreate the marker so the next iteration can remove it.
        [IO.File]::WriteAllText($env:PHASE3_TEST_ARGV,'not called');$count++
    }
    # Real provider-free operations catch stdin/output corruption in the K8S
    # YAML decoder and DB synthetic-state reader, without initializing Terraform.
    $env:PHASE3_REAL_TERRAFORM=$terraform
    [IO.File]::WriteAllText((Join-Path $scratch 'platform.yaml'),"apiVersion: v1`nkind: ConfigMap`nmetadata:`n  name: fixture`ndata:`n  account: `"8521907`"`n")
    $expression='jsonencode([for doc in split("\n---\n", file("platform.yaml")) : yamldecode(doc) if trimspace(doc) != ""])'
    $encoded=$expression|& $shim "-chdir=$scratch" console -no-color
    if($LASTEXITCODE -ne 0){throw 'Native provider-free YAML decode failed.'}
    $decoded=($encoded|ConvertFrom-Json)|ConvertFrom-Json
    if($decoded[0].kind -cne 'ConfigMap' -or $decoded[0].data.account -isnot [string]){throw 'Native decoder altered the YAML contract.'};$count++
    @{version=4;serial=1;lineage=[guid]::NewGuid().ToString();outputs=@{fixture=@{value='local';type='string';sensitive=$false}};resources=@()}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $scratch 'terraform.tfstate')
    $outputs=& $shim "-chdir=$scratch" output -json
    if($LASTEXITCODE -ne 0 -or ($outputs|ConvertFrom-Json).fixture.value -cne 'local'){throw 'Native synthetic-state read failed.'};$count++
    Push-Location (Split-Path -Parent $scratch)
    try {
        $outputs=& $shim '-chdir=./decoder with spaces' output -json
        if($LASTEXITCODE -ne 0 -or ($outputs|ConvertFrom-Json).fixture.value -cne 'local'){throw 'Relative synthetic-state directory failed.'};$count++
    } finally {Pop-Location}
} finally {
    foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name])}
    $resolved=[IO.Path]::GetFullPath($temp)
    if(-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('phase3-native-contract-')){throw 'Unsafe contract cleanup target.'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
Write-Output "PASS: $count local acceptance contracts; all four repository paths required; cloud commands rejected before tool execution."
