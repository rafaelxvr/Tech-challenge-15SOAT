# Native PATH shims enter here without binding tool flags as PowerShell parameters.
# pwsh -File interprets a colon in -chdir=C:\path as a parameter/value separator;
# the OS argument vector still contains the original argument, including spaces.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$native=[Environment]::GetCommandLineArgs()
$fileIndex=[Array]::IndexOf($native,'-File')
if($fileIndex -lt 0 -or $native.Count -lt ($fileIndex+4) -or
    [IO.Path]::GetFullPath($native[$fileIndex+1]) -cne [IO.Path]::GetFullPath($PSCommandPath)){
    throw 'LOCAL_ACCEPTANCE_COMMAND_REJECTED: native entry requires the fixed -File invocation'
}
$tool=$native[$fileIndex+2]
$toolArguments=[string[]]$native[($fileIndex+3)..($native.Count-1)]
& (Join-Path $PSScriptRoot 'phase3-local-command-guard.ps1') -Tool $tool -Arguments $toolArguments
exit $LASTEXITCODE
