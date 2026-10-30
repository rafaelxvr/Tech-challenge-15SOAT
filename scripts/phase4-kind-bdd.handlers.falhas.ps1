# Handlers BDD-07a, 07b, 07d (LocalStack fora) e 09a-09d (capacidade de reparos)
# (dot-source de phase4-kind-bdd.ps1, depois de phase4-kind-bdd.handlers.scheduler.ps1).
# Só dependem dos três serviços reais. Dados sintéticos apenas.
#
# "LocalStack fora": o Deployment `infra/localstack` vai a zero réplicas (trocar só o seletor do Service não derruba as
# conexões já abertas dos clientes, que continuam publicando). O LocalStack não persiste estado: na volta, o init.sh
# idempotente recria filas e tabelas (mensagens em voo e o item de capacidade se perdem; as outboxes ficam no Postgres).

function Set-LocalStackOffline {
    param([bool]$Offline)
    if ($Offline) {
        Invoke-Kube @('-n', 'infra', 'scale', 'deploy/localstack', '--replicas=0') | Out-Null
        Invoke-Kube @('-n', 'infra', 'wait', '--for=delete', 'pod', '-l', 'app=localstack', '--timeout=120s') -AllowFailure | Out-Null
        return
    }
    Invoke-Kube @('-n', 'infra', 'scale', 'deploy/localstack', '--replicas=1') | Out-Null
    Invoke-Kube @('-n', 'infra', 'rollout', 'status', 'deploy/localstack', '--timeout=240s') | Out-Null
    $initSh = (Get-Content -Raw (Join-Path $script:KindDir 'localstack/init.sh')) -replace "`r", ''
    Wait-Until -What 'init do LocalStack' -TimeoutSec 120 -Condition { (Invoke-Kube @('exec', '-i', '-n', 'infra', 'deploy/localstack', '--', 'sh', '-s') -StdIn $initSh -AllowFailure).ExitCode -eq 0 } | Out-Null
}

# Várias variáveis do Deployment em um único rollout; valor $null remove a variável.
function Set-DeploymentEnvs {
    param([string]$Namespace, [string]$Deployment, [string]$Service, [hashtable]$Vars)
    $kargs = @('-n', $Namespace, 'set', 'env', "deploy/$Deployment") + @($Vars.GetEnumerator() | ForEach-Object { if ($null -eq $_.Value) { "$($_.Key)-" } else { "$($_.Key)=$($_.Value)" } })
    Invoke-Kube $kargs | Out-Null
    Invoke-Kube @('-n', $Namespace, 'rollout', 'status', "deploy/$Deployment", '--timeout=240s') | Out-Null
    Restart-Forward $Service
}

function Get-OutboxOs {
    param([string]$OsId, [string]$Tipo)
    Get-OsSql "select estado || '|' || tentativas from saga_outbox where os_id='$OsId' and tipo='$Tipo' order by sequencia desc limit 1"
}

# Conclui o reparo (execução REPARO) de uma OS aceita e espera a saga CONCLUIDA; devolve a vaga de capacidade.
function Complete-Reparo {
    param($Ctx, [string]$OsId)
    Complete-Execucao $Ctx (Get-Execucao $Ctx $OsId 'REPARO')
    Wait-Saga $Ctx $OsId 'CONCLUIDA' | Out-Null
}

$Handlers['BDD-07a'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    $os = New-OsSintetica $ctx -QtdPeca 2
    Step-AteDecisao $ctx $os
    Set-LocalStackOffline $true
    try {
        Send-DecisaoCliente $ctx $os 'APROVADO'   # lança se o HTTP não for 200: a decisão é confirmada com a fila fora
        $t1 = [int](Wait-Until -What 'RegistrarDecisao PENDING com tentativa' -TimeoutSec 60 -Condition {
            $l = Get-OutboxOs $os.Id 'RegistrarDecisao'
            if ($l -match '^PENDING\|(\d+)$' -and [int]$Matches[1] -ge 1) { $Matches[1] }
        })
        $t2 = [int](Wait-Until -What 'tentativas crescentes' -TimeoutSec 90 -Condition {
            $l = Get-OutboxOs $os.Id 'RegistrarDecisao'
            if ($l -match '^PENDING\|(\d+)$' -and [int]$Matches[1] -gt $t1) { $Matches[1] }
        })
        Compare-Valor 'tentativas crescentes' $Cenario.contagens.tentativas_crescentes ($t2 -gt $t1) $falhas
        Compare-Valor 'comandos PENDING durante a falha' $Cenario.contagens.saga_outbox_pendente_durante_falha (Get-OsSql "select count(*) from saga_outbox where os_id='$($os.Id)' and tipo='RegistrarDecisao' and estado='PENDING'") $falhas
        Compare-Lista 'estado da saga durante a falha' $Cenario.estadosSaga @((Get-Saga $ctx $os.Id).estado) $falhas
    }
    finally { Set-LocalStackOffline $false }
    $final = Wait-Saga $ctx $os.Id 'AGUARDANDO_PAGAMENTO' -TimeoutSec 240
    Compare-Valor 'RegistrarDecisao PUBLISHED' 1 (Get-OsSql "select count(*) from saga_outbox where os_id='$($os.Id)' and tipo='RegistrarDecisao' and estado='PUBLISHED'") $falhas
    Compare-Valor 'RegistrarDecisao total na outbox' $Cenario.contagens.RegistrarDecisao_na_fila_apos_recuperar (Get-OsSql "select count(*) from saga_outbox where os_id='$($os.Id)' and tipo='RegistrarDecisao'") $falhas
    Compare-Valor 'orçamento APROVADO (entrega única)' 1 (Get-BillingSql "select count(*) from orcamento where os_id='$($os.Id)' and estado='APROVADO'") $falhas
    Compare-Valor 'pagamento PENDENTE (entrega única)' 1 (Get-BillingSql "select count(*) from pagamento where os_id='$($os.Id)' and estado='PENDENTE'") $falhas
    New-Resultado $falhas "decisão confirmada sem LocalStack (tentativas $t1 -> $t2), entregue uma vez na volta, saga em $($final.estado) (OS $($os.Numero))"
}

$Handlers['BDD-07b'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 10
    $ctxs = @()
    $pags = @()
    foreach ($i in 1, 2) {
        $c = $ctx.PSObject.Copy(); $c.Placa = Add-Veiculo $ctx.ClienteId
        $o = New-OsSintetica $c -QtdPeca 1
        $pags += Step-AteAguardandoPagamento $c $o
        $ctxs += [pscustomobject]@{ Ctx = $c; Os = $o }
    }
    Set-LocalStackOffline $true
    try {
        foreach ($i in 0, 1) { Send-SimulacaoPagamento $ctx $pags[$i] 'approved' }   # o billing responde com a fila fora
        foreach ($x in $ctxs) {
            Wait-Until -What "PagamentoConfirmado PENDING na outbox do billing (OS $($x.Os.Numero))" -TimeoutSec 60 -Condition {
                Get-BillingSql "select id from (select message_id::text as id from outbox where os_id='$($x.Os.Id)' and tipo='PagamentoConfirmado' and estado='PENDING' and tentativas>=1) t"
            } | Out-Null
        }
        Start-Sleep -Seconds 5
        foreach ($x in $ctxs) { Compare-Lista "saga retida em AGUARDANDO_PAGAMENTO (OS $($x.Os.Numero))" @('AGUARDANDO_PAGAMENTO') @((Get-Saga $x.Ctx $x.Os.Id).estado) $falhas }
    }
    finally { Set-LocalStackOffline $false }
    $ids = @()
    foreach ($x in $ctxs) {
        $final = Wait-Saga $x.Ctx $x.Os.Id 'EM_EXECUCAO' -TimeoutSec 240
        $e = @((Get-Observado $final).Estados)
        $ordemOk = ($e.IndexOf('AGUARDANDO_PAGAMENTO') -ge 0) -and ($e.IndexOf('EM_EXECUCAO') -gt $e.IndexOf('AGUARDANDO_PAGAMENTO')) -and ('COMPENSANDO' -notin $e)
        Compare-Valor "ordem dos estados da OS $($x.Os.Numero) ($($e -join '>'))" $true $ordemOk $falhas
        Compare-Valor "PagamentoConfirmado PUBLISHED na OS $($x.Os.Numero)" 1 (Get-BillingSql "select count(*) from outbox where os_id='$($x.Os.Id)' and tipo='PagamentoConfirmado' and estado='PUBLISHED'") $falhas
        Compare-Valor "PagamentoConfirmado total na OS $($x.Os.Numero)" 1 (Get-BillingSql "select count(*) from outbox where os_id='$($x.Os.Id)' and tipo='PagamentoConfirmado'") $falhas
        $ids += (Get-BillingSql "select message_id from outbox where os_id='$($x.Os.Id)' and tipo='PagamentoConfirmado'")
    }
    $lista = ($ids | ForEach-Object { "'$_'" }) -join ','
    Compare-Valor 'entregas únicas no saga_inbox' 2 (Get-OsSql "select count(*) from saga_inbox where message_id in ($lista)") $falhas
    Compare-Valor 'duplicatas na DLQ de respostas' $Cenario.contagens.replies_duplicadas_na_fila (Get-QueueTotal 'os-saga-replies-dlq.fifo') $falhas
    Compare-Valor 'fila de respostas drenada' 0 (Get-QueueTotal 'os-saga-replies.fifo') $falhas
    foreach ($x in $ctxs) { Complete-Reparo $x.Ctx $x.Os.Id }
    New-Resultado $falhas "2 replies do billing acumuladas e entregues em ordem por OS, sem duplicatas ($(($ctxs | ForEach-Object { $_.Os.Numero }) -join ', ')); replies da execution não cobertas (a execution depende do DynamoDB, também fora)"
}

$Handlers['BDD-07d'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    $prazos = @{ SAGA_PRAZO_PAGAMENTO = '30s'; SAGA_PRAZO_COMPENSACAO = '30s'; SAGA_MAX_TENTATIVAS_COMPENSACAO = '3' }
    Set-DeploymentEnvs oficina oficina-os-service oficina-os-service $prazos
    try {
        $os = New-OsSintetica $ctx -QtdPeca 2
        Step-AteAguardandoPagamento $ctx $os | Out-Null
        Set-LocalStackOffline $true   # fora antes de o prazo de pagamento (30 s) estourar: a compensação nasce sem fila
        try {
            $final = Wait-Saga $ctx $os.Id 'FALHA_COMPENSACAO' -TimeoutSec 300
            $e = @((Get-Observado $final).Estados | Select-Object -Last 2)
            Compare-Lista 'estados finais da saga' $Cenario.estadosSaga $e $falhas
            Compare-Valor 'pilha de compensação preservada (não vazia)' $true (@($final.compensacoesPendentes).Count -gt 0) $falhas
            Compare-Valor 'CancelarOrcamento nunca publicado' 'PENDING' ((Get-OutboxOs $os.Id 'CancelarOrcamento') -replace '\|.*$', '') $falhas
            $pilha = @($final.compensacoesPendentes) -join ','
            $prontoOrcamento = Get-BillingSql "select estado from orcamento where os_id='$($os.Id)'"
            Compare-Valor 'orçamento ainda não cancelado no billing' 'APROVADO' $prontoOrcamento $falhas
        }
        finally { Set-LocalStackOffline $false }
        Start-Sleep -Seconds 20
        $depois = Get-Saga $ctx $os.Id
        Compare-Valor 'saga continua FALHA_COMPENSACAO após a volta' 'FALHA_COMPENSACAO' $depois.estado $falhas
        Compare-Valor 'pilha preservada após a volta' $pilha (@($depois.compensacoesPendentes) -join ',') $falhas
    }
    finally { Set-DeploymentEnvs oficina oficina-os-service oficina-os-service @{ SAGA_PRAZO_PAGAMENTO = $null; SAGA_PRAZO_COMPENSACAO = $null; SAGA_MAX_TENTATIVAS_COMPENSACAO = $null } }
    New-Resultado $falhas "compensação esgotada com LocalStack fora: FALHA_COMPENSACAO, pilha [$pilha] preservada (OS $($os.Numero))"
}

# ---- Capacidade de reparos (EXECUCAO_CAPACIDADE_REPAROS=2, filial MATRIZ) ----

function Invoke-ComCapacidade {
    param([int]$Capacidade, [scriptblock]$Corpo)
    $ocupadas = Get-CapacidadeAtiva
    if ($ocupadas -ne 0) { throw "pré-condição: $ocupadas vaga(s) de reparo ocupada(s) por cenário anterior; use cluster limpo" }
    Set-DeploymentEnv oficina-execution oficina-execution oficina-execution EXECUCAO_CAPACIDADE_REPAROS "$Capacidade"
    try { & $Corpo }
    finally { Set-DeploymentEnv oficina-execution oficina-execution oficina-execution EXECUCAO_CAPACIDADE_REPAROS $null }
}

# OS nova até AGUARDANDO_PAGAMENTO (veículo próprio); devolve contexto, OS e pagamento.
function New-OsAteAguardandoPagamento {
    param($Base)
    $c = $Base.PSObject.Copy(); $c.Placa = Add-Veiculo $Base.ClienteId
    $o = New-OsSintetica $c -QtdPeca 1
    $p = Step-AteAguardandoPagamento $c $o
    [pscustomobject]@{ Ctx = $c; Os = $o; Pag = $p }
}

# Espera a decisão da execution sobre o reparo: EM_EXECUCAO (aceito) ou CANCELADA (recusado).
function Wait-DesfechoReparo {
    param($Item)
    Wait-Until -What "desfecho do reparo da OS $($Item.Os.Numero)" -TimeoutSec 150 -Condition {
        $s = Get-Saga $Item.Ctx $Item.Os.Id
        if ($s -and $s.estado -in 'EM_EXECUCAO', 'CANCELADA') { $s }
    }
}

function Get-StatusReparos {
    param($Itens)
    $todas = @($Itens | ForEach-Object { Get-ExecucoesDaOs $_.Ctx $_.Os.Id 'REPARO' })
    [pscustomobject]@{
        NA_FILA = @($todas | Where-Object status -eq 'NA_FILA').Count
        RECUSADA = @($todas | Where-Object status -eq 'RECUSADA').Count
        Ativas = @($todas | Where-Object { $_.status -in 'NA_FILA', 'EM_ANDAMENTO' }).Count
    }
}

# Devolve o resumo observável: aceitos, recusados, execuções por status e motivos de recusa.
function Get-Resumo {
    param($Itens, $Desfechos)
    $st = Get-StatusReparos $Itens
    $aceitos = @($Desfechos | Where-Object estado -eq 'EM_EXECUCAO').Count
    $recusados = @($Desfechos | Where-Object { $_.estado -eq 'CANCELADA' -and $_.motivoFalha -eq 'REPARO_RECUSADO' }).Count
    [pscustomobject]@{ Aceitos = $aceitos; Recusados = $recusados; NA_FILA = $st.NA_FILA; RECUSADA = $st.RECUSADA; Ativas = $st.Ativas }
}

function Invoke-Capacidade-A {
    param($Base)
    $itens = @(); $des = @()
    foreach ($i in 1..3) {
        $it = New-OsAteAguardandoPagamento $Base
        Send-SimulacaoPagamento $it.Ctx $it.Pag 'approved'
        $des += Wait-DesfechoReparo $it
        $itens += $it
    }
    $r = Get-Resumo $itens $des
    $aceitosItens = @(for ($i = 0; $i -lt 3; $i++) { if ($des[$i].estado -eq 'EM_EXECUCAO') { $itens[$i] } })
    [pscustomobject]@{ Resumo = $r; Itens = $itens; Aceitos = $aceitosItens; Respostas = @($des | ForEach-Object { if ($_.estado -eq 'EM_EXECUCAO') { 'ReparoIniciado' } else { 'ReparoRecusado' } }) }
}

function Invoke-Capacidade-B {
    param($Base)
    $itens = @(1..6 | ForEach-Object { New-OsAteAguardandoPagamento $Base })
    $port = $script:Ports.billing; $tok = $Base.Staff
    $codigos = $itens | ForEach-Object -ThrottleLimit 6 -Parallel {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$($using:port)/billing/simulacoes/pagamentos/$($_.Pag)" -Method POST -Body '{"status":"approved"}' -ContentType 'application/json' -Headers @{ Authorization = "Bearer $($using:tok)" } -SkipHttpErrorCheck -TimeoutSec 30
        [int]$r.StatusCode
    }
    $des = @($itens | ForEach-Object { Wait-DesfechoReparo $_ })
    $ativas = Get-CapacidadeAtiva
    $aceitosItens = @(for ($i = 0; $i -lt 6; $i++) { if ($des[$i].estado -eq 'EM_EXECUCAO') { $itens[$i] } })
    [pscustomobject]@{ Resumo = (Get-Resumo $itens $des); Itens = $itens; Aceitos = $aceitosItens; Codigos = @($codigos); CapacidadeAtiva = $ativas }
}

function Invoke-Capacidade-C {
    param($Base)
    $itens = @(); $des = @()
    foreach ($i in 1..2) {
        $it = New-OsAteAguardandoPagamento $Base
        Send-SimulacaoPagamento $it.Ctx $it.Pag 'approved'
        $des += Wait-DesfechoReparo $it; $itens += $it
    }
    $esgotada = Get-CapacidadeAtiva
    Complete-Reparo $itens[0].Ctx $itens[0].Os.Id   # libera uma vaga
    $liberada = Wait-Until -What 'vaga liberada (reparosAtivos=1)' -TimeoutSec 60 -Condition { if ((Get-CapacidadeAtiva) -eq 1) { 'ok' } }
    $terceiro = New-OsAteAguardandoPagamento $Base
    Send-SimulacaoPagamento $terceiro.Ctx $terceiro.Pag 'approved'
    $d3 = Wait-DesfechoReparo $terceiro
    $itens += $terceiro; $des += $d3
    [pscustomobject]@{ Itens = $itens; Aceitos = @($itens[1], $terceiro); Esgotada = $esgotada; AceitosAposLiberar = $(if ($d3.estado -eq 'EM_EXECUCAO') { 1 } else { 0 }); Liberada = $liberada }
}

function Complete-Aceitos {
    param($Aceitos)
    foreach ($a in $Aceitos) { Complete-Reparo $a.Ctx $a.Os.Id }
}

$Handlers['BDD-09a'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 40
    $ciclo = $null
    Invoke-ComCapacidade 2 {
        $script:ciclo = Invoke-Capacidade-A $ctx
        $c = $script:ciclo
        Compare-Lista 'respostas da execution' $Cenario.respostas @($c.Respostas | Sort-Object { $_ -ne 'ReparoIniciado' }) $falhas
        Compare-Valor 'execuções REPARO NA_FILA' $Cenario.contagens.execucoes.NA_FILA $c.Resumo.NA_FILA $falhas
        Compare-Valor 'execuções REPARO RECUSADA' $Cenario.contagens.execucoes.RECUSADA $c.Resumo.RECUSADA $falhas
        Compare-Valor 'terceira OS recusada' 'ReparoRecusado' $c.Respostas[2] $falhas
        Complete-Aceitos $c.Aceitos
    }
    New-Resultado $falhas "capacidade 2: 2 aceitos, 1 recusado, NA_FILA=$($script:ciclo.Resumo.NA_FILA) RECUSADA=$($script:ciclo.Resumo.RECUSADA)"
}

$Handlers['BDD-09b'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 40
    Invoke-ComCapacidade 2 {
        $script:ciclo = Invoke-Capacidade-B $ctx
        $c = $script:ciclo
        Compare-Valor 'simulações HTTP 200' 6 @($c.Codigos | Where-Object { $_ -eq 200 }).Count $falhas
        Compare-Valor 'reparos aceitos' $Cenario.contagens.aceitos $c.Resumo.Aceitos $falhas
        Compare-Valor 'reparos recusados' $Cenario.contagens.recusados $c.Resumo.Recusados $falhas
        Compare-Valor 'vagas ativas acima da capacidade' $Cenario.contagens.ativas_acima_da_capacidade ([Math]::Max(0, $c.CapacidadeAtiva - 2)) $falhas
        Compare-Valor 'vagas ativas' 2 $c.CapacidadeAtiva $falhas
        Complete-Aceitos $c.Aceitos
    }
    New-Resultado $falhas "6 pagamentos aprovados em paralelo: $($script:ciclo.Resumo.Aceitos) aceitos, $($script:ciclo.Resumo.Recusados) recusados, vagas ativas $($script:ciclo.CapacidadeAtiva)"
}

$Handlers['BDD-09c'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 40
    Invoke-ComCapacidade 2 {
        $script:ciclo = Invoke-Capacidade-C $ctx
        $c = $script:ciclo
        Compare-Valor 'vagas ocupadas com capacidade esgotada' 2 $c.Esgotada $falhas
        Compare-Valor 'reparos aceitos após liberar' $Cenario.contagens.aceitos_apos_liberar $c.AceitosAposLiberar $falhas
        Complete-Aceitos $c.Aceitos
    }
    New-Resultado $falhas "concluir um reparo liberou a vaga (esgotada=$($script:ciclo.Esgotada)); o próximo IniciarReparo foi aceito"
}

$Handlers['BDD-09d'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 80
    $script:rodadas = @()
    Invoke-ComCapacidade 2 {
        foreach ($n in 1, 2) {
            $a = Invoke-Capacidade-A $ctx; Complete-Aceitos $a.Aceitos
            $b = Invoke-Capacidade-B $ctx; Complete-Aceitos $b.Aceitos
            $c = Invoke-Capacidade-C $ctx; Complete-Aceitos $c.Aceitos
            $todos = @($a.Itens) + @($b.Itens) + @($c.Itens)
            $ativas = (Get-StatusReparos $todos).Ativas
            $script:rodadas += [pscustomobject]@{
                Resumo = "A[$($a.Resumo.Aceitos)/$($a.Resumo.Recusados)] B[$($b.Resumo.Aceitos)/$($b.Resumo.Recusados)] C[$($c.AceitosAposLiberar)]"
                AtivasAposLimpeza = $ativas; CapacidadeAtiva = (Get-CapacidadeAtiva)
            }
        }
    }
    $r = $script:rodadas
    Compare-Valor 'resultados iguais nas duas rodadas' $true ($r[0].Resumo -eq $r[1].Resumo) $falhas
    foreach ($i in 0, 1) {
        Compare-Valor "execuções ativas após a limpeza (rodada $($i + 1))" $Cenario.contagens.execucoes_ativas_apos_limpeza $r[$i].AtivasAposLimpeza $falhas
        Compare-Valor "vagas de capacidade ocupadas (rodada $($i + 1))" 0 $r[$i].CapacidadeAtiva $falhas
    }
    New-Resultado $falhas "duas rodadas de 09a+09b+09c: $($r[0].Resumo) = $($r[1].Resumo); nenhuma execução ativa nem vaga ocupada"
}
