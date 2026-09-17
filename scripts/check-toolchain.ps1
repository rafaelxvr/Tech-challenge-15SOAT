[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$javaVersion = (& java -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "17\.') {
    throw 'Project build requires Java 17'
}

$wrapperCmd = Join-Path $PSScriptRoot '..\mvnw.cmd'
if (-not (Test-Path -LiteralPath $wrapperCmd -PathType Leaf)) {
    throw 'Maven wrapper missing'
}

$wrapper = if ($env:OS -eq 'Windows_NT') {
    $wrapperCmd
} else {
    Join-Path $PSScriptRoot '..\mvnw'
}

if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    throw 'Maven wrapper missing for this operating system'
}

$lockPath = Join-Path $PSScriptRoot '..\toolchain.lock.json'
if (-not (Test-Path -LiteralPath $lockPath -PathType Leaf)) {
    throw 'Toolchain lock missing'
}

$lock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json
if ($lock.javaMajor -ne 17) {
    throw 'Toolchain lock must require Java 17'
}

& $wrapper -version
if ($LASTEXITCODE -ne 0) {
    throw 'Maven wrapper failed'
}
