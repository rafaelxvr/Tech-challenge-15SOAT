# Shared helpers for the Phase 4 local Kind scripts. Dot-source; do not execute.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# O nome do cluster é parametrizável (PHASE4_KIND_CLUSTER) para ensaiar sem tocar no cluster oficina-phase4 do runner.
$script:ClusterName = if ($env:PHASE4_KIND_CLUSTER) { $env:PHASE4_KIND_CLUSTER } else { 'oficina-phase4' }
if ($script:ClusterName -notmatch '^[a-z0-9][a-z0-9-]{0,30}$') { throw "PHASE4_KIND_CLUSTER inválido: '$($script:ClusterName)'" }
$script:KubeContext = "kind-$($script:ClusterName)"
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$script:KindDir = Join-Path $script:RepoRoot 'infra/phase4-kind'

# Fail closed: never let an inherited AWS profile/endpoint leak into any native tool we start.
foreach ($name in 'AWS_PROFILE', 'AWS_DEFAULT_PROFILE', 'AWS_SESSION_TOKEN', 'AWS_ENDPOINT_URL', 'AWS_ROLE_ARN', 'AWS_WEB_IDENTITY_TOKEN_FILE') {
    Remove-Item "Env:$name" -ErrorAction SilentlyContinue
}
$env:AWS_EC2_METADATA_DISABLED = 'true'

# Única função de sanitização dos artefatos do harness (logs, bdd-results.json, diagnostico.txt). Ordem importa:
# JWT solto -> Bearer/Basic -> Authorization -> chave=valor/JSON -> literais SQL. Idempotente e fail closed (superfície larga).
function Hide-Secret {
    param([string]$Text)
    if (-not $Text) { return $Text }
    # JWT solto: eyJ<header>.<payload>.<assinatura>; a assinatura pode ser vazia (alg=none).
    $Text = $Text -replace 'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*', '***'
    # Esquema de autorização: preserva só o prefixo (Bearer/Basic) e mascara a credencial.
    $Text = $Text -replace '(?i)\b(Bearer|Basic)\s+(?!\*\*\*)[^\s"'',;}]+', '$1 ***'
    # Authorization sem esquema reconhecido (ex.: token cru): mascara o valor inteiro.
    $Text = $Text -replace '(?i)(authorization["'']?\s*[:=]\s*["'']?)(?!(?:Bearer|Basic)\s)(?!\*\*\*)[^\s"'',;}]+', '$1***'
    # chave=valor, chave: valor e JSON ("password": "valor").
    $Text = $Text -replace '(?i)(PGPASSWORD|passwd|password|_pw|secret|license_key|token|api[_-]?key)(["'']?\s*[:=]\s*["'']?)(?!\*\*\*)[^\s"'',;}]+', '$1$2***'
    # SQL forms such as: ALTER ROLE x PASSWORD 'literal' / PASSWORD $$literal$$ / PASSWORD E'literal'
    $Text = $Text -replace "(?i)(password\s+)(E?'(?:[^']|'')*'|\$\$.*?\$\$)", '$1''***'''
    $Text
}

# Sanitiza todo campo de detalhe antes de serializar bdd-results.json (retornos normais dos handlers, não só exceções).
function Protect-Resultados {
    param([object[]]$Resultados)
    @($Resultados | ForEach-Object {
        [pscustomobject]@{ id = $_.id; status = $_.status; detalhe = (Hide-Secret ([string]$_.detalhe)) }
    })
}

# Escreve o diagnóstico sem nunca gravar conteúdo bruto no caminho final: acumula em memória, sanitiza e só então grava.
# $Secoes: lista de @{ Titulo = '...'; Coletar = { ...linhas... } }. Falha numa seção vira nota e a coleta segue.
# Se a própria sanitização falhar, publica só um marcador fixo (jamais o texto bruto).
function Write-SanitizedDiagnostico {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][object[]]$Secoes, [int]$MaxChars = 2000000)
    $sb = New-Object System.Text.StringBuilder
    foreach ($secao in $Secoes) {
        [void]$sb.AppendLine("== $($secao.Titulo) ==")
        try { foreach ($linha in @(& $secao.Coletar)) { [void]$sb.AppendLine("$linha") } }
        catch { [void]$sb.AppendLine("coleta interrompida: $($_.Exception.Message)") }
    }
    try {
        $texto = Hide-Secret $sb.ToString()
        if ($texto.Length -gt $MaxChars) { $texto = $texto.Substring(0, $MaxChars) + "`n== truncado em $MaxChars caracteres ==" }
    }
    catch { $texto = 'diagnóstico indisponível: a sanitização falhou e o conteúdo bruto foi descartado.' }
    Set-Content -Path $Path -Value $texto -Encoding utf8
}

function Invoke-Native {
    param([Parameter(Mandatory)][string]$File, [string[]]$Arguments = @(), [string]$StdIn, [switch]$AllowFailure)
    $out = if ($PSBoundParameters.ContainsKey('StdIn')) { $StdIn | & $File @Arguments 2>&1 } else { & $File @Arguments 2>&1 }
    $code = $LASTEXITCODE
    $text = ($out | ForEach-Object { "$_" }) -join "`n"
    if ($code -ne 0 -and -not $AllowFailure) {
        if ($PSBoundParameters.ContainsKey('StdIn')) {
            # Fail closed: stdin carries credentials (Secret manifests, ALTER ROLE ... PASSWORD) and the tool may echo it back
            # in any quoting (single, double, JSON, $$). Report only the command and exit code, never its output.
            throw "$File $($Arguments -join ' ') failed ($code); output withheld because stdin carries credentials."
        }
        # Defence in depth: never echo a credential-looking argument or output.
        throw (Hide-Secret "$File $($Arguments -join ' ') failed ($code): $text")
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

# Valida o arquivo local de credencial New Relic (fora do repositório) SEM guardar o valor nem a linha em variável: só conta linhas por regex.
# Contrato (menor privilégio): o arquivo tem SOMENTE `NEW_RELIC_LICENSE_KEY=valor` (valor sem espaços, aspas ou `export`; comentários e linhas em branco são aceitos).
# `kubectl create secret --from-env-file` copiaria qualquer outra chave do arquivo para os três namespaces; por isso outras chaves são recusadas.
# As mensagens citam só o caminho e o nome da chave, nunca o conteúdo do arquivo.
function Assert-NewRelicEnvFile {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "arquivo de credencial New Relic ausente: $Path" }
    $count = { param($pattern) (Select-String -LiteralPath $Path -Pattern $pattern | Measure-Object).Count }
    $declared = & $count '^\s*(?:export\s+)?NEW_RELIC_LICENSE_KEY\s*='
    if (-not $declared) { throw "NEW_RELIC_LICENSE_KEY ausente em $Path" }
    if ($declared -gt 1) { throw "NEW_RELIC_LICENSE_KEY duplicada em $Path" }
    if (& $count '^NEW_RELIC_LICENSE_KEY=["'']') { throw "NEW_RELIC_LICENSE_KEY com aspas em $Path; o kubectl --from-env-file não as remove" }
    if (-not (& $count '^NEW_RELIC_LICENSE_KEY=\S+$')) { throw "NEW_RELIC_LICENSE_KEY vazia ou fora do formato KEY=valor (valor inteiro sem espaços, aspas ou export) em $Path" }
    if (& $count '^(?!\s*$)(?!\s*#)(?!NEW_RELIC_LICENSE_KEY=)') { throw "arquivo $Path deve conter somente NEW_RELIC_LICENSE_KEY (outras chaves seriam copiadas para os Secrets dos três namespaces); remova-as" }
}

# Cria/atualiza o Secret `newrelic-license` direto do arquivo (kubectl --from-env-file), sem passar o valor por variável PowerShell.
# O manifesto trafega por pipe entre dois kubectl e é aplicado server-side: não grava a licença em last-applied-configuration.
function Set-NewRelicSecret {
    param([Parameter(Mandatory)][string]$Namespace, [Parameter(Mandatory)][string]$EnvFile)
    # `apply --server-side` não remove a anotação de um Secret criado por apply client-side em cluster reaproveitado: apaga antes para nunca restar last-applied-configuration com a licença.
    kubectl --context $script:KubeContext delete secret newrelic-license -n $Namespace --ignore-not-found 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "falha ao limpar o Secret newrelic-license em $Namespace (saída omitida)" }
    kubectl --context $script:KubeContext create secret generic newrelic-license -n $Namespace "--from-env-file=$EnvFile" --dry-run=client -o yaml 2>$null |
        kubectl --context $script:KubeContext apply --server-side --force-conflicts -f - 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "falha ao aplicar o Secret newrelic-license em $Namespace (saída omitida: pode conter credencial)" }
}

function Test-LoopbackAddress {
    param([string]$Address)
    $Address -in @('127.0.0.1', '::1', 'localhost')
}
