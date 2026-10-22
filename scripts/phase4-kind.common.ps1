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

function Hide-Secret {
    param([string]$Text)
    $Text = $Text -replace "(?i)(PGPASSWORD|password|_pw|secret)\s*=\s*\S+", '$1=***'
    # SQL forms such as: ALTER ROLE x PASSWORD 'literal' / PASSWORD $$literal$$ / PASSWORD E'literal'
    $Text = $Text -replace "(?i)(password\s+)(E?'(?:[^']|'')*'|\$\$.*?\$\$)", '$1''***'''
    $Text
}

function Invoke-Native {
    param([Parameter(Mandatory)][string]$File, [string[]]$Arguments = @(), [string]$StdIn, [switch]$AllowFailure)
    $out = if ($PSBoundParameters.ContainsKey('StdIn')) { $StdIn | & $File @Arguments 2>&1 } else { & $File @Arguments 2>&1 }
    $code = $LASTEXITCODE
    $text = ($out | ForEach-Object { "$_" }) -join "`n"
    if ($code -ne 0 -and -not $AllowFailure) {
        # Defence in depth: secrets travel over stdin, but never echo a credential-looking argument or output.
        $shown = Hide-Secret "$File $($Arguments -join ' ') failed ($code): $text"
        # Input sent over stdin carries credentials (ALTER ROLE ... PASSWORD): drop every quoted literal an error may echo back.
        if ($PSBoundParameters.ContainsKey('StdIn')) { $shown = $shown -replace "'[^']*'", "'***'" }
        throw $shown
    }
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

# Creates/updates a generic Secret from a manifest on stdin, so values never appear in process arguments or errors.
function Set-KubeSecret {
    param([string]$Namespace, [string]$Name, [Parameter(Mandatory)][hashtable]$Values)
    $manifest = [ordered]@{ apiVersion = 'v1'; kind = 'Secret'; type = 'Opaque'; metadata = @{ name = $Name; namespace = $Namespace }; stringData = $Values }
    Invoke-Kube @('apply', '-f', '-') -StdIn ($manifest | ConvertTo-Json -Depth 4) | Out-Null
}

function Get-KubeSecretValue {
    param([string]$Namespace, [string]$Name, [string]$Key)
    $b64 = (Invoke-Kube @('get', 'secret', $Name, '-n', $Namespace, '-o', "jsonpath={.data.$Key}")).Output
    [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64))
}

# Runs SQL in a Postgres pod. Every login is password-authenticated (scram, including the pod's own unix socket
# and loopback; see POSTGRES_INITDB_ARGS in the manifests), so a port-forward cannot reach a passwordless superuser.
# Without -Password: superuser using the admin Secret. With -User/-Password: that role, optionally against the other instance.
# The password and the \set variables travel on stdin (first line is the password), never as process arguments.
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
    $exec = @('exec', '-i', '-n', 'data', "deploy/postgres-$Instance", '--', 'sh', '-c', 'read -r PGPASSWORD; export PGPASSWORD; exec psql "$@"', 'sh')
    # Service DNS name for role logins: a non-loopback source proves password authentication is enforced end to end.
    if ($Password) { $exec += @('-h', "postgres-$ConnectInstance.data.svc.cluster.local") }
    else { $Password = Get-KubeSecretValue data "postgres-$Instance-admin" password }
    $exec += $psql
    Invoke-Kube $exec -StdIn "$Password`n$sets`n$Sql`n" -AllowFailure:$AllowFailure
}

function Test-LoopbackAddress {
    param([string]$Address)
    $Address -in @('127.0.0.1', '::1', 'localhost')
}
