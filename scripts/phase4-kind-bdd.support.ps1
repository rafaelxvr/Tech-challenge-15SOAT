# Suporte do runner BDD da Fase 4 (dot-source de phase4-kind-bdd.ps1; requer phase4-kind.common.ps1 já carregado).
# Acesso ao cluster: port-forward só em loopback, tokens JWT sintéticos mintados em memória, nada é impresso nem versionado.

$script:Forwards = @()
$script:ForwardMap = @{}
$script:Ports = @{ os = 18080; billing = 18081; execution = 18082 }
$script:CustomerKey = $null

function Start-Forward {
    param([string]$Namespace, [string]$Service, [int]$LocalPort)
    $log = Join-Path $script:WorkDir "pf-$Service.log"
    # -WindowStyle só existe no Windows; no runner Linux do CI o parâmetro é omitido.
    $hidden = if ($IsWindows) { @{ WindowStyle = 'Hidden' } } else { @{} }
    $p = Start-Process kubectl -PassThru @hidden -RedirectStandardOutput $log -RedirectStandardError "$log.err" `
        -ArgumentList @('--context', $script:KubeContext, '-n', $Namespace, 'port-forward', '--address', '127.0.0.1', "svc/$Service", "${LocalPort}:8080")
    $script:Forwards += $p
    $script:ForwardMap[$Service] = @{ Namespace = $Namespace; Port = $LocalPort; Process = $p }
    foreach ($i in 1..30) {
        if ($p.HasExited) { throw "port-forward de $Service terminou: $(Get-Content -Raw "$log.err")" }
        try { $c = [Net.Sockets.TcpClient]::new('127.0.0.1', $LocalPort); $c.Dispose(); return } catch { Start-Sleep -Milliseconds 500 }
    }
    throw "port-forward de $Service não abriu a porta $LocalPort"
}

function Start-Forwards {
    $script:WorkDir = Join-Path ([IO.Path]::GetTempPath()) ("phase4-bdd-work-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $script:WorkDir | Out-Null
    Start-Forward oficina oficina-os-service $script:Ports.os
    Start-Forward oficina-billing oficina-billing $script:Ports.billing
    Start-Forward oficina-execution oficina-execution $script:Ports.execution
}

# Reabre o port-forward de um serviço depois de o pod ser trocado (o forward de svc fica preso ao pod antigo).
function Restart-Forward {
    param([string]$Service)
    $f = $script:ForwardMap[$Service]
    if ($f.Process -and -not $f.Process.HasExited) { Stop-Process -Id $f.Process.Id -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 1
    Start-Forward $f.Namespace $Service $f.Port
}

function Stop-Forwards {
    foreach ($p in $script:Forwards) { if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } }
    $script:Forwards = @()
    if ($script:WorkDir -and (Test-Path $script:WorkDir)) { Remove-Item $script:WorkDir -Recurse -Force -ErrorAction SilentlyContinue }
}

function ConvertTo-B64Url { param([byte[]]$Bytes) [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_') }

function New-Jwt {
    param([hashtable]$Header, [hashtable]$Claims, [scriptblock]$Sign)
    $h = ConvertTo-B64Url ([Text.Encoding]::UTF8.GetBytes(($Header | ConvertTo-Json -Compress)))
    $c = ConvertTo-B64Url ([Text.Encoding]::UTF8.GetBytes(($Claims | ConvertTo-Json -Compress -Depth 5)))
    $sig = ConvertTo-B64Url (& $Sign ([Text.Encoding]::UTF8.GetBytes("$h.$c")))
    "$h.$c.$sig"
}

function New-StaffToken {
    param([string]$Email = 'admin@oficina.com', [string]$Role = 'ADMIN')
    $secret = Get-KubeSecretValue oficina oficina-os-secrets jwt_secret
    $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($secret))
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    New-Jwt @{ alg = 'HS256'; typ = 'JWT'; kid = 'staff-local-1' } @{
        iss = 'oficina-os-service'; aud = 'oficina-staff'; sub = $Email; iat = $now - 5; exp = $now + 1800
        principal_type = 'staff'; token_use = 'access'; roles = @($Role)
    } { param($d) $hmac.ComputeHash($d) }
}

# Troca a chave pública de cliente do os-service por um par gerado pelo runner (a privada fica só na memória do processo).
function Set-HarnessCustomerKey {
    $script:CustomerKey = [Security.Cryptography.RSA]::Create(2048)
    $pem = $script:CustomerKey.ExportSubjectPublicKeyInfoPem()
    $json = @{ security = @{ jwt = @{ customer = @{ 'public-keys' = @{ 'customer-kind-1' = $pem } } } } } | ConvertTo-Json -Depth 6 -Compress
    $cm = [ordered]@{ apiVersion = 'v1'; kind = 'ConfigMap'; metadata = @{ name = 'oficina-os-customer-trust'; namespace = 'oficina' }; data = @{ SPRING_APPLICATION_JSON = $json } }
    Invoke-Kube @('apply', '-f', '-') -StdIn ($cm | ConvertTo-Json -Depth 6) | Out-Null
    Invoke-Kube @('-n', 'oficina', 'rollout', 'restart', 'deploy/oficina-os-service') | Out-Null
    Invoke-Kube @('-n', 'oficina', 'rollout', 'status', 'deploy/oficina-os-service', '--timeout=240s') | Out-Null
}

function New-CustomerToken {
    param([Parameter(Mandatory)][string]$ClienteId, [Parameter(Mandatory)][long]$IdentityVersion)
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    New-Jwt @{ alg = 'RS256'; typ = 'JWT'; kid = 'customer-kind-1' } @{
        iss = 'oficina-customer-kind'; aud = 'oficina-customer-kind'; sub = $ClienteId; iat = $now - 5; exp = $now + 1800
        principal_type = 'customer'; token_use = 'access'; identity_version = $IdentityVersion
        scopes = @('orders:read:self', 'orders:decide:self')
    } { param($d) $script:CustomerKey.SignData($d, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1) }
}

function Invoke-Api {
    param([ValidateSet('os', 'billing', 'execution')][string]$Service, [string]$Method = 'GET', [string]$Path, [string]$Token, $Body)
    $args = @{ Uri = "http://127.0.0.1:$($script:Ports[$Service])$Path"; Method = $Method; SkipHttpErrorCheck = $true; Headers = @{ Authorization = "Bearer $Token" }; TimeoutSec = 30 }
    if ($null -ne $Body) { $args.Body = ($Body | ConvertTo-Json -Depth 8); $args.ContentType = 'application/json' }
    $r = Invoke-WebRequest @args
    [pscustomobject]@{ Status = [int]$r.StatusCode; Json = $(if ($r.Content) { try { $r.Content | ConvertFrom-Json } catch { $null } }); Raw = $r.Content }
}

function Wait-Until {
    param([scriptblock]$Condition, [int]$TimeoutSec = 60, [string]$What = 'condição')
    $fim = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $fim) { $v = & $Condition; if ($v) { return $v }; Start-Sleep -Seconds 1 }
    throw "Timeout aguardando $What"
}

function Get-OsSql { param([string]$Sql) (Invoke-Psql -Instance os -Database oficina -Sql $Sql).Output.Trim() }
function Get-BillingSql { param([string]$Sql) (Invoke-Psql -Instance billing -Database billing -Sql $Sql).Output.Trim() }

# Altera (ou remove, com $null) uma variável do Deployment, espera o rollout e reabre o port-forward do serviço.
function Set-DeploymentEnv {
    param([string]$Namespace, [string]$Deployment, [string]$Service, [string]$Name, $Value)
    $arg = if ($null -eq $Value) { "$Name-" } else { "$Name=$Value" }
    Invoke-Kube @('-n', $Namespace, 'set', 'env', "deploy/$Deployment", $arg) | Out-Null
    Invoke-Kube @('-n', $Namespace, 'rollout', 'status', "deploy/$Deployment", '--timeout=240s') | Out-Null
    Restart-Forward $Service
}

function Set-DeploymentReplicas {
    param([string]$Namespace, [string]$Deployment, [string]$Service, [int]$Replicas)
    Invoke-Kube @('-n', $Namespace, 'scale', "deploy/$Deployment", "--replicas=$Replicas") | Out-Null
    if ($Replicas -gt 0) {
        Invoke-Kube @('-n', $Namespace, 'rollout', 'status', "deploy/$Deployment", '--timeout=240s') | Out-Null
        Restart-Forward $Service
    } else {
        Invoke-Kube @('-n', $Namespace, 'wait', '--for=delete', 'pod', '-l', "app.kubernetes.io/name=$Deployment", '--timeout=120s') -AllowFailure | Out-Null
    }
}

# Mensagens (visíveis + em voo + atrasadas) de uma fila FIFO do LocalStack, lidas sem consumir.
function Get-QueueTotal {
    param([string]$Queue)
    $r = Invoke-Kube @('-n', 'infra', 'exec', 'deploy/localstack', '--', 'awslocal', 'sqs', 'get-queue-attributes', '--queue-url', "http://localhost:4566/000000000000/$Queue", '--attribute-names', 'All', '--output', 'json')
    $a = ($r.Output | ConvertFrom-Json).Attributes
    [int]$a.ApproximateNumberOfMessages + [int]$a.ApproximateNumberOfMessagesNotVisible + [int]$a.ApproximateNumberOfMessagesDelayed
}
