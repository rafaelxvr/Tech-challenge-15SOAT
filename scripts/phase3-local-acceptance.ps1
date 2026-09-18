[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$AppRepository,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$FunctionsRepository,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$DatabaseRepository,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$KubernetesRepository,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$ReceiptFile
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$receiptPath=[IO.Path]::GetFullPath($ReceiptFile)
if(Test-Path -LiteralPath $receiptPath){throw 'Acceptance receipt already exists; choose a new output path.'}
$repositories=[ordered]@{APP=$AppRepository;FUN=$FunctionsRepository;DB=$DatabaseRepository;K8S=$KubernetesRepository}
$sources=[ordered]@{}
foreach($name in $repositories.Keys){
    $path=[IO.Path]::GetFullPath($repositories[$name])
    if(-not (Test-Path -LiteralPath $path -PathType Container)){throw "Required repository missing: $name"}
    $root=& git -C $path rev-parse --show-toplevel 2>$null
    if($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($root) -ine $path.TrimEnd('/','\')){throw "Expected a Git repository root: $name"}
    $commit=(& git -C $path rev-parse HEAD).Trim()
    if($LASTEXITCODE -ne 0 -or $commit -cnotmatch '\A[a-f0-9]{40}\z'){throw "Unable to resolve commit: $name"}
    $dirty=@(& git -C $path status --porcelain).Count -gt 0
    $sources[$name]=[ordered]@{path=$path;commit=$commit;workingTreeDirty=$dirty;scope='committed HEAD only; uncommitted/untracked files excluded'}
}
if(@($sources.Values.path|Sort-Object -Unique).Count -ne 4){throw 'All four repository paths must be distinct.'}
$workspace=Join-Path ([IO.Path]::GetTempPath()) ('phase3-local-acceptance-'+[guid]::NewGuid())
$shim=Join-Path $workspace 'bin'
$snapshots=Join-Path $workspace 'snapshots'
$logs=Join-Path $workspace 'logs'
foreach($path in @($shim,$snapshots,$logs,(Split-Path -Parent $receiptPath))){New-Item -ItemType Directory -Path $path -Force|Out-Null}
$empty=Join-Path $workspace 'empty-config'
[IO.File]::WriteAllText($empty,'')
$settings=Join-Path $workspace 'maven-settings.xml'
[IO.File]::WriteAllText($settings,'<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>')
$pwsh=Join-Path $PSHOME $(if($IsWindows){'pwsh.exe'}else{'pwsh'})
$guard=Join-Path $PSScriptRoot 'phase3-local-native-entry.ps1'
$runner=Join-Path $PSScriptRoot 'phase3-local-suite-runner.ps1'
$terraform=(Get-Command terraform -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$kubectl=(Get-Command kubectl -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$helmAvailable=$null -ne (Get-Command helm -CommandType Application -ErrorAction SilentlyContinue)
$java=(Get-Command java -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
if((& $java -version 2>&1|Out-String) -notmatch 'version "17\.'){throw 'Java 17 must be the java executable on PATH.'}
$javaHome=Split-Path -Parent (Split-Path -Parent $java)
foreach($tool in @('aws','kubectl','terraform')){
    if($IsWindows){
        [IO.File]::WriteAllText((Join-Path $shim "$tool.cmd"),"@echo off`r`n`"$pwsh`" -NoProfile -NonInteractive -File `"$guard`" $tool %*`r`nexit /b %errorlevel%`r`n")
    } else {
        $script="#!/bin/sh`nexec '$pwsh' -NoProfile -NonInteractive -File '$guard' $tool `"`$@`"`n"
        $file=Join-Path $shim $tool;[IO.File]::WriteAllText($file,$script);& chmod +x $file
    }
}
$plan=@(
    @{repo='APP';suite='tests/pipeline-contract.ps1'},@{repo='APP';suite='app-focused-java'},
    @{repo='FUN';suite='tests/verify-infrastructure.ps1'},@{repo='DB';suite='tests/verify.ps1'},
    @{repo='K8S';suite='tests/application-rollout-tests.ps1'},@{repo='K8S';suite='tests/platform-manifests-tests.ps1'},
    @{repo='K8S';suite='tests/newrelic-chart-tests.ps1'},@{repo='K8S';suite='tests/workload-capacity-tests.ps1'},
    @{repo='K8S';suite='tests/staging-app-workload-tests.ps1'},@{repo='K8S';suite='tests/runtime-public-configmap-tests.ps1'}
)
$receipt=[ordered]@{schemaVersion=1;status='RUNNING';startedAtUtc=[datetimeoffset]::UtcNow.ToString('o');finishedAtUtc=$null;sourceScope='isolated LF snapshots of committed HEAD';repositories=$sources;workspace=$workspace;suites=@();skippedCloudChecks=@('AWS identity/API access and deployment','Kubernetes cluster access/apply and private runtime health','Terraform real plan/apply and remote state','RDS migrations, grants and live cross-repository integration','Production/staging promotion receipts and R4 cloud acceptance')}
$receipt['skippedLocalChecks']=if($helmAvailable){@()}else{@('New Relic dynamic Helm schema/render accounting: Helm unavailable; existing suite runs static assertions only.')}
function Save-Receipt {[IO.File]::WriteAllText($receiptPath,($receipt|ConvertTo-Json -Depth 15),[Text.UTF8Encoding]::new($false))}
Save-Receipt
try {
    foreach($name in $sources.Keys){
        $snapshot=Join-Path $snapshots $name
        & git -c core.autocrlf=false clone --local --no-hardlinks --no-checkout --quiet $sources[$name].path $snapshot
        if($LASTEXITCODE -ne 0){throw "Snapshot clone failed: $name"}
        & git -c core.autocrlf=false -c core.hooksPath=$shim -C $snapshot checkout --quiet --detach $sources[$name].commit
        if($LASTEXITCODE -ne 0){throw "Snapshot checkout failed: $name"}
    }
    foreach($entry in $plan){
        $start=[datetimeoffset]::UtcNow.ToString('o')
        $snapshot=Join-Path $snapshots $entry.repo
        $scratch=Join-Path $workspace ('scratch/'+$entry.repo+'-'+[IO.Path]::GetFileNameWithoutExtension($entry.suite))
        New-Item -ItemType Directory -Path $scratch -Force|Out-Null
        $log=Join-Path $logs ($entry.repo+'-'+[IO.Path]::GetFileNameWithoutExtension($entry.suite)+'.log')
        $psi=[Diagnostics.ProcessStartInfo]::new($pwsh)
        $psi.UseShellExecute=$false;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true;$psi.CreateNoWindow=$true;$psi.WorkingDirectory=$snapshot
        foreach($arg in @('-NoProfile','-NonInteractive','-File',$runner,'-Repository',$snapshot,'-Suite',$entry.suite,'-MavenSettings',$settings)){$psi.ArgumentList.Add($arg)}
        foreach($key in @($psi.Environment.Keys)){
            if($key -match '^(AWS_|TF_|KUBE|AZURE_|ARM_|GOOGLE_|MAVEN_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$)|TOKEN|SECRET|PASSWORD|ACCESS_KEY|PRIVATE_KEY'){$null=$psi.Environment.Remove($key)}
        }
        $psi.Environment['PATH']=$shim+[IO.Path]::PathSeparator+$env:PATH
        $psi.Environment['AWS_CONFIG_FILE']=$empty;$psi.Environment['AWS_SHARED_CREDENTIALS_FILE']=$empty;$psi.Environment['AWS_EC2_METADATA_DISABLED']='true'
        $psi.Environment['KUBECONFIG']=$empty;$psi.Environment['TF_CLI_CONFIG_FILE']=$empty
        $psi.Environment['PHASE3_REAL_TERRAFORM']=$terraform;$psi.Environment['PHASE3_SNAPSHOT_ROOT']=$snapshots
        $psi.Environment['PHASE3_REAL_KUBECTL']=$kubectl;$psi.Environment['PHASE3_SCRATCH_ROOT']=$scratch
        $psi.Environment['TEMP']=$scratch;$psi.Environment['TMP']=$scratch;$psi.Environment['TMPDIR']=$scratch
        $psi.Environment['JAVA_HOME']=$javaHome
        $psi.Environment['GIT_CONFIG_COUNT']='1';$psi.Environment['GIT_CONFIG_KEY_0']='core.autocrlf';$psi.Environment['GIT_CONFIG_VALUE_0']='false'
        Write-Output ("Running local suite: {0} {1}" -f $entry.repo,$entry.suite)
        $process=[Diagnostics.Process]::Start($psi)
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        [IO.File]::WriteAllText($log,$stdout.GetAwaiter().GetResult()+$stderr.GetAwaiter().GetResult(),[Text.UTF8Encoding]::new($false))
        $status=if($process.ExitCode -eq 0){'PASS'}else{'FAIL'}
        if($status -eq 'PASS' -and $entry.suite -eq 'tests/newrelic-chart-tests.ps1' -and -not $helmAvailable){$status='PASS_STATIC_ONLY'}
        $command=if($entry.suite -ceq 'app-focused-java'){'mvnw -B -s <empty-settings> -gs <empty-settings> -Dtest=Phase3ContractTest,TokenTrustTest,ClienteIdentityTest,HealthGroupsTest test'}else{'pwsh -NoProfile -NonInteractive -File '+$entry.suite}
        $receipt.suites+= [ordered]@{repository=$entry.repo;command=$command;status=$status;exitCode=$process.ExitCode;startedAtUtc=$start;finishedAtUtc=[datetimeoffset]::UtcNow.ToString('o');log=$log;logSha256=(Get-FileHash $log -Algorithm SHA256).Hash.ToLowerInvariant()}
        Save-Receipt
    }
    $receipt.status=if(@($receipt.suites|Where-Object status -eq 'FAIL').Count){'FAIL'}elseif($receipt.skippedLocalChecks.Count){'PASS_LOCAL_WITH_SKIPS'}else{'PASS_LOCAL_ONLY'}
} catch {$receipt.status='FAIL';$receipt['error']=$_.Exception.Message}
finally {$receipt.finishedAtUtc=[datetimeoffset]::UtcNow.ToString('o');Save-Receipt}
Write-Output "Local acceptance receipt: $receiptPath ($($receipt.status))"
if($receipt.status -eq 'FAIL'){throw 'Local acceptance failed; inspect receipt and suite logs.'}
