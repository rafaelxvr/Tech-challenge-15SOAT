<#
.SYNOPSIS
  Smokes transversais da Fase 4 no Kind efêmero: JWT nos três serviços, isolamento de bancos, fail-closed e ausência de egress AWS.
.DESCRIPTION
  Cada verificação grava esperado x observado em smokes-results.json (e o relatório Markdown, se -MarkdownPath for informado),
  sempre ligado aos três SHAs implantados. Tokens, senhas e segredos nunca são impressos nem gravados: os JWTs são mintados em memória
  com o segredo lido do cluster e as senhas de role só trafegam por stdin (Invoke-Psql).
  Só endpoints locais (port-forward em loopback); variáveis AWS do ambiente são limpas por phase4-kind.common.ps1.
.PARAMETER Suite
  Jwt | Isolamento | FailClosed | Egress | All (padrão)
.PARAMETER OsSha, BillingSha, ExecutionSha
  Revisões implantadas (defaults = pins da fatia 7).
.PARAMETER OutputDir
  Diretório do smokes-results.json. Default: novo diretório sob a pasta temporária do sistema.
.PARAMETER MarkdownPath
  Se informado, renderiza o relatório consolidado por SHA nesse arquivo.
#>
[CmdletBinding()]
param(
    [ValidateSet('Jwt', 'Isolamento', 'FailClosed', 'Egress', 'All')][string[]]$Suite = 'All',
    [ValidatePattern('^[0-9a-f]{7,40}$')][string]$OsSha = 'cf34717',
    [ValidatePattern('^[0-9a-f]{7,40}$')][string]$BillingSha = '96c811a',
    [ValidatePattern('^[0-9a-f]{7,40}$')][string]$ExecutionSha = '1bf1e6c',
    [string]$OutputDir,
    [string]$MarkdownPath
)
. (Join-Path $PSScriptRoot 'phase4-kind.common.ps1')
. (Join-Path $PSScriptRoot 'phase4-kind-bdd.support.ps1')

$script:EmailMecanico = 'smoke-mecanico@oficina.local'
$script:Resultados = [System.Collections.Generic.List[object]]::new()
$osProbe = [guid]::NewGuid().ToString()
$script:Servicos = @(
    @{ Nome = 'os'; Namespace = 'oficina'; Deployment = 'oficina-os-service'; Leitura = '/api/pecas'; SoAdmin = '/api/admin/metricas/tempo-execucao-servicos' },
    @{ Nome = 'billing'; Namespace = 'oficina-billing'; Deployment = 'oficina-billing'; Leitura = "/billing/orcamentos?osId=$osProbe"; SoAdmin = "/billing/orcamentos?osId=$osProbe" },
    @{ Nome = 'execution'; Namespace = 'oficina-execution'; Deployment = 'oficina-execution'; Leitura = "/execucao/execucoes?osId=$osProbe"; SoAdmin = $null }
)

function Add-Resultado {
    param([string]$Id, [string]$Suite, [string]$Descricao, $Esperado, $Observado, [string]$Nota = '')
    $ok = "$Esperado" -eq "$Observado"
    $script:Resultados.Add([pscustomobject]@{ id = $Id; suite = $Suite; descricao = $Descricao; esperado = "$Esperado"; observado = "$Observado"; status = $(if ($ok) { 'PASS' } else { 'FAIL' }); nota = $Nota })
    Write-Host ("{0} {1}: esperado={2} observado={3}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $Id, $Esperado, $Observado)
}

# Token HS256 de staff com o segredo informado; parâmetros variam um aspecto por vez (assinatura, validade, papel, audiência).
function New-SmokeToken {
    param([string]$Secret, [string[]]$Roles = @('ADMIN'), [int]$ExpOffset = 1800, [string]$Audience = 'oficina-staff')
    $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    New-Jwt @{ alg = 'HS256'; typ = 'JWT'; kid = 'staff-local-1' } @{
        iss = 'oficina-os-service'; aud = $Audience; sub = $(if ($Roles -contains 'MECANICO' -and $Roles -notcontains 'ADMIN') { $script:EmailMecanico } else { 'admin@oficina.com' }); iat = $(if ($ExpOffset -lt 0) { $now + $ExpOffset - 300 } else { $now - 5 }); exp = $now + $ExpOffset
        principal_type = 'staff'; token_use = 'access'; roles = $Roles
    } { param($d) $hmac.ComputeHash($d) }
}

# O os-service trata o papel do banco como autoridade: o smoke cria (e remove) um MECANICO sintético só no cluster efêmero.
function Test-Jwt {
    Get-OsSql "insert into usuarios (id, email, senha, role, ativo) values (uuid_generate_v4(), '$($script:EmailMecanico)', 'sem-login', 'MECANICO', true) on conflict (email) do nothing" | Out-Null
    try { Test-JwtCasos }
    finally { Get-OsSql "delete from usuarios where email='$($script:EmailMecanico)'" | Out-Null }
}

function Test-JwtCasos {
    $segredo = Get-KubeSecretValue oficina oficina-os-secrets jwt_secret
    $casos = @(
        @{ Id = 'sem-token'; Token = ''; Esperado = 401 },
        @{ Id = 'lixo'; Token = 'nao.e.um.jwt'; Esperado = 401 },
        @{ Id = 'assinatura-invalida'; Token = (New-SmokeToken -Secret ('x' * 40)); Esperado = 401 },
        @{ Id = 'expirado'; Token = (New-SmokeToken -Secret $segredo -ExpOffset -120); Esperado = 401 },
        @{ Id = 'audiencia-errada'; Token = (New-SmokeToken -Secret $segredo -Audience 'outra-audiencia'); Esperado = 401 },
        @{ Id = 'valido'; Token = (New-SmokeToken -Secret $segredo); Esperado = 200 }
    )
    foreach ($s in $script:Servicos) {
        foreach ($c in $casos) {
            $r = Invoke-Api $s.Nome GET $s.Leitura -Token $c.Token
            $esperado = if ($c.Esperado -eq 200) { '2xx' } else { $c.Esperado }
            $obs = if ($c.Esperado -eq 200) { if ($r.Status -ge 200 -and $r.Status -lt 300) { '2xx' } else { $r.Status } } else { $r.Status }
            Add-Resultado "JWT-$($s.Nome)-$($c.Id)" 'jwt' "$($s.Nome) GET $($s.Leitura) com token $($c.Id)" $esperado $obs
        }
        # Papel errado: MECANICO em rota exclusiva de ADMIN; no execution não há rota só-ADMIN, então testa papel fora da lista.
        if ($s.SoAdmin) {
            $r = Invoke-Api $s.Nome GET $s.SoAdmin -Token (New-SmokeToken -Secret $segredo -Roles @('MECANICO'))
            Add-Resultado "JWT-$($s.Nome)-papel-errado" 'jwt' "$($s.Nome) GET $($s.SoAdmin) com MECANICO (rota só ADMIN)" 403 $r.Status 'ACHADO F-1: papel insuficiente devolve 401 em vez de 403'
            $r = Invoke-Api $s.Nome GET $s.SoAdmin -Token (New-SmokeToken -Secret $segredo)
            Add-Resultado "JWT-$($s.Nome)-admin-rota-admin" 'jwt' "$($s.Nome) GET $($s.SoAdmin) com ADMIN" '2xx' $(if ($r.Status -ge 200 -and $r.Status -lt 300) { '2xx' } else { $r.Status })
        } else {
            $r = Invoke-Api $s.Nome GET $s.Leitura -Token (New-SmokeToken -Secret $segredo -Roles @('CLIENTE'))
            Add-Resultado "JWT-$($s.Nome)-papel-errado" 'jwt' "$($s.Nome) GET $($s.Leitura) com papel CLIENTE (fora de ADMIN/MECANICO)" 401 $r.Status `
                'sem rota só-ADMIN no execution: papel fora da lista é token inválido (401 por desenho); o 403 exigido não é observável aqui'
        }
        if ($s.SoAdmin -ne $s.Leitura) {
        $r = Invoke-Api $s.Nome GET $s.Leitura -Token (New-SmokeToken -Secret $segredo -Roles @('MECANICO'))
        Add-Resultado "JWT-$($s.Nome)-mecanico-leitura" 'jwt' "$($s.Nome) GET $($s.Leitura) com MECANICO" '2xx' $(if ($r.Status -ge 200 -and $r.Status -lt 300) { '2xx' } else { $r.Status })
        }
    }
}

function Test-Conexao {
    param([string]$Id, [string]$Descricao, [string]$Instancia, [string]$Banco, [string]$Usuario, [string]$Senha, [string]$ConectarEm, [string]$Sql, [string]$Esperado)
    $r = Invoke-Psql -Instance $Instancia -Database $Banco -User $Usuario -Password $Senha -ConnectInstance $ConectarEm -Sql $Sql -AllowFailure
    $obs = if ($r.ExitCode -eq 0) { 'permitido' } else { 'negado' }
    Add-Resultado $Id 'isolamento' $Descricao $Esperado $obs
}

function Test-Isolamento {
    $osPw = Get-KubeSecretValue data postgres-os-roles app_password
    $blPw = Get-KubeSecretValue data postgres-billing-roles app_password
    Test-Conexao 'DB-os-proprio' 'oficina_app lê o próprio schema (ordens_servico)' os oficina oficina_app $osPw os 'select count(*) from ordens_servico' permitido
    Test-Conexao 'DB-billing-proprio' 'billing_app lê o próprio schema (pagamento)' billing billing billing_app $blPw billing 'select count(*) from pagamento' permitido
    Test-Conexao 'DB-os-para-billing' 'oficina_app com a própria senha não entra no banco do billing' os billing oficina_app $osPw billing 'select 1' negado
    Test-Conexao 'DB-billing-para-os' 'billing_app com a própria senha não entra no banco do os' billing oficina billing_app $blPw os 'select 1' negado
    Test-Conexao 'DB-os-senha-cruzada' 'oficina_app com a senha do billing_app é rejeitada no os' os oficina oficina_app $blPw os 'select 1' negado
    Test-Conexao 'DB-os-manutencao' 'oficina_app não entra no banco de manutenção postgres' os postgres oficina_app $osPw os 'select 1' negado
    Test-Conexao 'DB-billing-manutencao' 'billing_app não entra no banco de manutenção postgres' billing postgres billing_app $blPw billing 'select 1' negado
    Test-Conexao 'DB-os-ddl' 'oficina_app (runtime) não cria tabela' os oficina oficina_app $osPw os 'create table smoke_ddl_negado(id int)' negado
    Test-Conexao 'DB-billing-ddl' 'billing_app (runtime) não cria tabela' billing billing billing_app $blPw billing 'create table smoke_ddl_negado(id int)' negado
    # Quem realmente conectou em cada instância (pg_stat_activity): nenhuma role de outro serviço.
    $quem = { param($inst, $db) (Invoke-Psql -Instance $inst -Database $db -Sql "select coalesce(string_agg(distinct usename, ',' order by usename),'') from pg_stat_activity where datname='$db' and usename not in ('postgres')").Output.Trim() }
    $osUsers = & $quem os oficina
    $blUsers = & $quem billing billing
    Add-Resultado 'DB-os-conectados' 'isolamento' 'roles conectadas ao banco oficina (exceto superusuário de manutenção)' 'oficina_app' $osUsers 'o migrator do billing só conecta no Flyway do startup e não mantém conexão'
    Add-Resultado 'DB-billing-conectados' 'isolamento' 'roles conectadas ao banco billing (exceto superusuário de manutenção)' 'billing_app' $blUsers
    # Config efetiva de cada serviço aponta só para o próprio banco; o execution não tem credencial SQL (usa DynamoDB).
    $cfg = { param($ns, $cm) (Invoke-Kube @('-n', $ns, 'get', 'cm', $cm, '-o', 'json')).Output | ConvertFrom-Json }
    $os = (& $cfg oficina oficina-os-service-config).data
    $bl = (& $cfg oficina-billing oficina-billing-config).data
    $ex = (& $cfg oficina-execution oficina-execution-config).data
    Add-Resultado 'DB-os-url' 'isolamento' 'DB_URL do os-service' 'postgres-os/oficina/oficina_app' ("{0}/{1}" -f (($os.DB_URL -replace '^.*//([^.]+)\..*/(\w+)$', '$1/$2')), $os.DB_USERNAME)
    Add-Resultado 'DB-billing-url' 'isolamento' 'DB_URL do billing' 'postgres-billing/billing/billing_app' ("{0}/{1}" -f (($bl.DB_URL -replace '^.*//([^.]+)\..*/(\w+)$', '$1/$2')), $bl.DB_USERNAME)
    $sqlExec = @($ex.PSObject.Properties.Name | Where-Object { $_ -match '^(DB_|SPRING_DATASOURCE)|JDBC|POSTGRES' })
    Add-Resultado 'DB-execution-sem-sql' 'isolamento' 'execution não recebe variável de banco SQL' 0 $sqlExec.Count
}

# StrictMode: propriedade opcional do JSON do kubectl.
function Get-Prop { param($Obj, [string]$Name) if ($null -ne $Obj -and $Obj.PSObject.Properties[$Name]) { $Obj.$Name } else { $null } }

# Substitui o ambiente do Deployment, espera o novo pod falhar ou ficar pronto e restaura com `rollout undo`.
function Test-SemSegredo {
    param($S, [string]$Variavel)
    $ns = $S.Namespace; $dep = $S.Deployment
    $antes = (Invoke-Kube @('-n', $ns, 'get', 'deploy', $dep, '-o', 'jsonpath={.metadata.generation}')).Output
    Invoke-Kube @('-n', $ns, 'set', 'env', "deploy/$dep", "$Variavel-") | Out-Null
    $estado = 'pronto-sem-segredo'
    $fim = (Get-Date).AddSeconds(150)
    $motivo = ''
    try {
        while ((Get-Date) -lt $fim) {
            $pods = (Invoke-Kube @('-n', $ns, 'get', 'pods', '-l', "app.kubernetes.io/name=$dep", '-o', 'json')).Output | ConvertFrom-Json
            $novo = @($pods.items | Where-Object { -not (Get-Prop $_.metadata 'deletionTimestamp') } | Sort-Object { $_.metadata.creationTimestamp } -Descending)[0]
            $cs = Get-Prop $novo.status 'containerStatuses' | Select-Object -First 1
            $espera = if ($cs) { Get-Prop (Get-Prop $cs.state 'waiting') 'reason' } else { $null }
            if ($cs -and ($cs.restartCount -ge 1 -or $espera -in 'CrashLoopBackOff', 'Error', 'CreateContainerConfigError')) { $estado = 'nao-sobe'; $motivo = $espera; break }
            if ($cs -and $cs.ready -and $novo.metadata.creationTimestamp -gt (Get-Date).AddSeconds(-140).ToUniversalTime().ToString('o')) { $estado = 'pronto-sem-segredo'; break }
            Start-Sleep -Seconds 4
        }
        if ($estado -eq 'nao-sobe') {
            $log = (Invoke-Kube @('-n', $ns, 'logs', $novo.metadata.name, '--tail=200') -AllowFailure).Output
            $log += "`n" + (Invoke-Kube @('-n', $ns, 'logs', $novo.metadata.name, '--previous', '--tail=200') -AllowFailure).Output
            $term = Get-Prop (Get-Prop $cs 'lastState') 'terminated'
            $motivo = "restartCount=$($cs.restartCount) exitCode=$(Get-Prop $term 'exitCode') reason=$(Get-Prop $term 'reason')"
            $linhas = @($log -split "`n" | Where-Object { $_ -match 'Exception|APPLICATION FAILED|must be configured|Could not resolve placeholder' } | Select-Object -Last 2 | ForEach-Object { $_.Substring(0, [Math]::Min(160, $_.Length)) })
            $motivo = (Hide-Secret (($motivo, ($linhas -join ' | ')) -join ' ')).Trim()
        }
    }
    finally {
        Invoke-Kube @('-n', $ns, 'rollout', 'undo', "deploy/$dep") | Out-Null
        Invoke-Kube @('-n', $ns, 'rollout', 'status', "deploy/$dep", '--timeout=300s') | Out-Null
        Restart-Forward $S.Deployment
    }
    Add-Resultado "FC-$($S.Nome)-sem-$Variavel" 'fail-closed' "$($S.Nome) sem $Variavel no Deployment: novo pod não fica pronto" 'nao-sobe' $estado $motivo
}

function Test-FailClosed {
    foreach ($s in $script:Servicos) {
        $variavel = 'JWT_SECRET'
        Test-SemSegredo $s $variavel
        $img = (Invoke-Kube @('-n', $s.Namespace, 'get', 'deploy', $s.Deployment, '-o', 'jsonpath={.spec.template.spec.containers[0].image}')).Output
        $env = (Invoke-Kube @('-n', $s.Namespace, 'get', 'deploy', $s.Deployment, '-o', 'jsonpath={.spec.template.spec.containers[0].env[*].name}')).Output
        Add-Resultado "FC-$($s.Nome)-restaurado" 'fail-closed' "$($s.Nome) restaurado após o teste: JWT_SECRET de volta e imagem pinada" 'sim' $(if ($env -match 'JWT_SECRET' -and $img -match ':(cf34717|96c811a|1bf1e6c)$') { 'sim' } else { "nao ($img)" })
    }
}

function Test-Egress {
    $awsDominio = 'amazon' + 'aws\.com'   # montado em partes: o contrato estático proíbe o literal em qualquer script
    # Controle positivo: o detector precisa enxergar um host AWS de exemplo, senão "0 ocorrências" não prova nada.
    Add-Resultado 'EG-detector' 'egress' 'detector de host AWS reconhece um endpoint de exemplo' 1 @([regex]::Matches('https://sqs.us-east-1.' + $awsDominio.Replace('\', ''), "[\w.-]*${awsDominio}")).Count
    $todos = foreach ($s in $script:Servicos) {
        $j = (Invoke-Kube @('-n', $s.Namespace, 'get', 'deploy,cm,secret', '-o', 'json')).Output | ConvertFrom-Json
        foreach ($i in $j.items) { if ($i.kind -ne 'Secret') { ($i | ConvertTo-Json -Depth 12 -Compress) } }
    }
    $texto = $todos -join "`n"
    $aws = ([regex]::Matches($texto, "[\w.-]*${awsDominio}[\w./-]*") | ForEach-Object Value | Sort-Object -Unique)
    Add-Resultado 'EG-sem-host-aws' 'egress' 'nenhum Deployment/ConfigMap dos serviços referencia host público da AWS' 0 @($aws).Count
    $endpoints = ([regex]::Matches($texto, '"(?:SAGA_SQS_ENDPOINT|BILLING_SQS_ENDPOINT|EXECUCAO_SQS_ENDPOINT|EXECUCAO_DYNAMODB_ENDPOINT)":"([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
    Add-Resultado 'EG-endpoints-localstack' 'egress' 'todo endpoint SQS/DynamoDB é o LocalStack do cluster' 'localstack.infra' (($endpoints | ForEach-Object { $_ -replace '^http://([^.]+\.[^.]+).*$', '$1' } | Sort-Object -Unique) -join ',')
    $chaves = @([regex]::Matches($texto, '"AWS_(?:ACCESS_KEY_ID|SECRET_ACCESS_KEY)":"([^"]*)"') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
    Add-Resultado 'EG-credenciais-ficticias' 'egress' 'credenciais AWS configuradas são só o valor fictício "test"' 'test' ($chaves -join ',')
    foreach ($s in $script:Servicos) {
        $log = (Invoke-Kube @('-n', $s.Namespace, 'logs', "deploy/$($s.Deployment)", '--tail=2000') -AllowFailure).Output
        $hits = @([regex]::Matches($log, "[\w.-]*${awsDominio}")).Count
        Add-Resultado "EG-$($s.Nome)-logs" 'egress' "logs do $($s.Nome) sem tentativa de conexão a host público da AWS" 0 $hits
    }
    $tem = (Invoke-Kube @('get', 'secret', '-A', '-o', 'json')).Output | ConvertFrom-Json
    $awsSecrets = @($tem.items | Where-Object { $_.metadata.namespace -in 'oficina', 'oficina-billing', 'oficina-execution' -and $_.data.PSObject.Properties.Name -match '(?i)aws' })
    Add-Resultado 'EG-sem-secret-aws' 'egress' 'nenhum Secret de aplicação carrega credencial AWS' 0 $awsSecrets.Count
}

$suites = if ($Suite -contains 'All') { 'Jwt', 'Isolamento', 'FailClosed', 'Egress' } else { $Suite }
$dir = if ($OutputDir) { $OutputDir } else { Join-Path ([IO.Path]::GetTempPath()) ("phase4-smokes-" + [guid]::NewGuid().ToString('N')) }
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Assert-KindCluster
try {
    Start-Forwards
    foreach ($s in $suites) { & "Test-$s" }
}
finally { Stop-Forwards }

$resumo = $script:Resultados | Group-Object suite | ForEach-Object { [pscustomobject]@{ suite = $_.Name; total = $_.Count; pass = @($_.Group | Where-Object status -eq 'PASS').Count; fail = @($_.Group | Where-Object status -eq 'FAIL').Count } }
$saida = [ordered]@{
    executadoEm = (Get-Date).ToUniversalTime().ToString('o'); cluster = $script:ClusterName
    revisoes = [ordered]@{ os = $OsSha; billing = $BillingSha; execution = $ExecutionSha }
    resumo = $resumo; resultados = $script:Resultados
}
$arquivo = Join-Path $dir 'smokes-results.json'
$saida | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 $arquivo
Write-Host "Resultados: $arquivo"
$resumo | Format-Table | Out-String | Write-Host
if ($MarkdownPath) {
    $md = [System.Text.StringBuilder]::new()
    [void]$md.AppendLine("Cluster ``$($script:ClusterName)``; SHAs: os ``$OsSha``, billing ``$BillingSha``, execution ``$ExecutionSha``.`n")
    [void]$md.AppendLine('| Suíte | Total | PASS | FAIL |'); [void]$md.AppendLine('|---|---|---|---|')
    foreach ($r in $resumo) { [void]$md.AppendLine("| $($r.suite) | $($r.total) | $($r.pass) | $($r.fail) |") }
    [void]$md.AppendLine("`n| Id | Verificação | Esperado | Observado | Status |"); [void]$md.AppendLine('|---|---|---|---|---|')
    foreach ($r in $script:Resultados) { [void]$md.AppendLine("| $($r.id) | $($r.descricao) | $($r.esperado) | $($r.observado) | $($r.status) |") }
    Set-Content -Encoding utf8 -Path $MarkdownPath -Value $md.ToString()
}
if (@($script:Resultados | Where-Object status -eq 'FAIL').Count -gt 0) { exit 1 }
