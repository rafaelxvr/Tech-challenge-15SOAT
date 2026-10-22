# Shared helpers for the Phase 4 local Kind scripts. Dot-source; do not execute.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:ClusterName = 'oficina-phase4'
$script:KubeContext = "kind-$($script:ClusterName)"
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$script:KindDir = Join-Path $script:RepoRoot 'infra/phase4-kind'

# Fail closed: never let an inherited AWS profile/endpoint leak into any native tool we start.
foreach ($name in 'AWS_PROFILE', 'AWS_DEFAULT_PROFILE', 'AWS_SESSION_TOKEN', 'AWS_ENDPOINT_URL', 'AWS_ROLE_ARN', 'AWS_WEB_IDENTITY_TOKEN_FILE') {
    Remove-Item "Env:$name" -ErrorAction SilentlyContinue
}
$env:AWS_EC2_METADATA_DISABLED = 'true'

function Invoke-Native {
    param([Parameter(Mandatory)][string]$File, [string[]]$Arguments = @(), [string]$StdIn, [switch]$AllowFailure)
    $out = if ($PSBoundParameters.ContainsKey('StdIn')) { $StdIn | & $File @Arguments 2>&1 } else { & $File @Arguments 2>&1 }
    $code = $LASTEXITCODE
    $text = ($out | ForEach-Object { "$_" }) -join "`n"
    if ($code -ne 0 -and -not $AllowFailure) { throw "$File $($Arguments -join ' ') failed ($code): $text" }
    [pscustomobject]@{ ExitCode = $code; Output = $text }
}

function Invoke-Kube {
    param([Parameter(Mandatory)][string[]]$Arguments, [string]$StdIn, [switch]$AllowFailure)
    $a = @('--context', $script:KubeContext) + $Arguments
    if ($PSBoundParameters.ContainsKey('StdIn')) { Invoke-Native kubectl $a -StdIn $StdIn -AllowFailure:$AllowFailure }
    else { Invoke-Native kubectl $a -AllowFailure:$AllowFailure }
}

function Assert-KindCluster {
    $clusters = (Invoke-Native kind @('get', 'clusters') -AllowFailure).Output -split "`n"
    if ($clusters -notcontains $script:ClusterName) { throw "Kind cluster '$($script:ClusterName)' not found. Run: pwsh -NoProfile -File scripts/phase4-kind.ps1 -Action Up" }
}

function New-RandomSecret {
    param([int]$Bytes = 24)
    $buf = [byte[]]::new($Bytes)
    [Security.Cryptography.RandomNumberGenerator]::Fill($buf)
    ([Convert]::ToBase64String($buf) -replace '[^A-Za-z0-9]', 'x')
}

function Get-KubeSecretValue {
    param([string]$Namespace, [string]$Name, [string]$Key)
    $b64 = (Invoke-Kube @('get', 'secret', $Name, '-n', $Namespace, '-o', "jsonpath={.data.$Key}")).Output
    [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64))
}

# Runs SQL in a Postgres pod. Without -Password: superuser over the pod's unix socket (no secret needed).
# With -User/-Password: a real TCP login (scram) as that role, optionally against the other instance.
# Variables are passed as \set lines on stdin (never as process arguments).
function Invoke-Psql {
    param(
        [Parameter(Mandatory)][ValidateSet('os', 'billing')][string]$Instance,
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$Sql,
        [string]$User = 'postgres', [string]$Password, [hashtable]$Variables = @{}, [switch]$AllowFailure,
        [ValidateSet('os', 'billing')][string]$ConnectInstance = $Instance
    )
    $sets = ($Variables.GetEnumerator() | ForEach-Object { "\set $($_.Key) '$($_.Value -replace "'", "''")'" }) -join "`n"
    $psql = @('-X', '-q', '-t', '-A', '-v', 'ON_ERROR_STOP=1', '-U', $User, '-d', $Database)
    $exec = @('exec', '-i', '-n', 'data', "deploy/postgres-$Instance", '--')
    # Service DNS name, not 127.0.0.1: the image's pg_hba trusts loopback, so only a non-loopback source
    # proves that password authentication is really enforced.
    if ($Password) { $exec += @('env', "PGPASSWORD=$Password", 'psql', '-h', "postgres-$ConnectInstance.data.svc.cluster.local") + $psql }
    else { $exec += @('psql') + $psql }
    Invoke-Kube $exec -StdIn "$sets`n$Sql`n" -AllowFailure:$AllowFailure
}

function Test-LoopbackAddress {
    param([string]$Address)
    $Address -in @('127.0.0.1', '::1', 'localhost')
}
