[CmdletBinding()]
param([Parameter(Mandatory)][string]$Tool,[Parameter(ValueFromRemainingArguments)][string[]]$Arguments)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if($Tool -cne 'terraform'){throw "LOCAL_ACCEPTANCE_COMMAND_REJECTED: $Tool"}
$root=(Get-Location).ProviderPath
$remaining=@($Arguments)
if($remaining.Count -gt 0 -and $remaining[0].StartsWith('-chdir=')){
    $root=[IO.Path]::GetFullPath($remaining[0].Substring(7));$remaining=@($remaining|Select-Object -Skip 1)
}
if($remaining.Count -eq 0){throw 'LOCAL_ACCEPTANCE_COMMAND_REJECTED: terraform requires an allowed operation'}
$operation=$remaining[0]
if($operation -cnotin @('fmt','init','validate','test')){throw 'LOCAL_ACCEPTANCE_COMMAND_REJECTED: Terraform operation'}
$sandbox=[IO.Path]::GetFullPath($env:PHASE3_SNAPSHOT_ROOT).TrimEnd([IO.Path]::DirectorySeparatorChar)
if(-not $root.StartsWith($sandbox+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {throw 'LOCAL_ACCEPTANCE_COMMAND_REJECTED: Terraform outside snapshots'}
if($operation -ceq 'fmt' -and $remaining -cnotcontains '-check'){throw 'LOCAL_ACCEPTANCE_COMMAND_REJECTED: fmt must be read-only'}
if($operation -ceq 'init'){
    foreach($flag in @('-backend=false','-input=false','-lockfile=readonly')){if($remaining -cnotcontains $flag){throw 'LOCAL_ACCEPTANCE_COMMAND_REJECTED: unsafe Terraform init'}}
    if(@($remaining|Where-Object {$_ -match '^-backend(=true|-config)|^-upgrade'}).Count){throw 'LOCAL_ACCEPTANCE_COMMAND_REJECTED: backend/upgrade'}
}
if($operation -ceq 'test'){
    if(@($remaining|Where-Object {$_ -match '^-test-directory|^-var'}).Count){throw 'LOCAL_ACCEPTANCE_COMMAND_REJECTED: unreviewed test inputs'}
    $files=@(Get-ChildItem -LiteralPath (Join-Path $root 'tests') -Filter '*.tftest.hcl' -File)
    if($files.Count -eq 0){throw 'LOCAL_ACCEPTANCE_COMMAND_REJECTED: mock tests missing'}
    foreach($file in $files){
        $text=Get-Content $file.FullName -Raw
        if($text -notmatch '(?m)^mock_provider\s+"aws"\s*\{' -or $text -match '(?m)^\s*provider\s+"aws"'){throw 'LOCAL_ACCEPTANCE_COMMAND_REJECTED: real AWS test provider'}
    }
}
if([string]::IsNullOrWhiteSpace($env:PHASE3_REAL_TERRAFORM)){throw 'LOCAL_ACCEPTANCE_TOOL_MISSING: terraform'}
& $env:PHASE3_REAL_TERRAFORM @Arguments
exit $LASTEXITCODE
