# Handlers dos cenários BDD da Fase 4 (dot-source de phase4-kind-bdd.ps1, depois de phase4-kind-bdd.support.ps1).
# Cada handler devolve @{ Status = 'PASS'|'FAIL'; Detalhe = '...' } comparando o observado com oraculos.json.
# Só dados sintéticos; nenhum valor sensível é impresso.

$script:Seq = 0

function New-Placa {
    # Placa sintética única por cenário: T + 2 letras + dígito + letra + 2 dígitos (ver fixtures/sinteticos.json).
    $script:Seq++
    $n = (Get-Random -Minimum 0 -Maximum 17575) + $script:Seq * 0
    $l = [char[]](65..90)
    "T$($l[[int]($n / 676) % 26])$($l[[int]($n / 26) % 26])$([int]($n % 10))$($l[$n % 26])$('{0:D2}' -f ($script:Seq % 100))"
}

# Garante cliente sintético, peça com estoque definido e devolve o contexto base do cenário.
function Initialize-Cenario {
    param([int]$Estoque = 5)
    Get-OsSql "insert into clientes(nome,tipo_documento,documento,email,telefone) values('Cliente Sintetico','CPF','39053344705','sintetico@example.invalid','11999990000') on conflict (documento) do nothing" | Out-Null
    $cliente = Get-OsSql "select id || '|' || versao_identidade from clientes where documento='39053344705'"
    $clienteId, $versao = $cliente -split '\|'
    $peca = Get-OsSql "select id from pecas where codigo='OL-5W30-1L'"
    Get-OsSql "update pecas set quantidade_estoque=$Estoque where id='$peca'" | Out-Null
    $servico = Get-OsSql "select id from servicos order by nome limit 1"
    $placa = New-Placa
    # Contorno de UNR-279: a rota de placa nova devolve 500 (rollback-only), então o veículo é pré-cadastrado.
    Get-OsSql "insert into veiculos(placa,marca,modelo,ano,cliente_id) values('$placa','Teste','Teste',2025,'$clienteId')" | Out-Null
    [pscustomobject]@{ ClienteId = $clienteId; Versao = [long]$versao; PecaId = $peca; ServicoId = $servico; Placa = $placa; Staff = (New-StaffToken) }
}

function New-OsSintetica {
    param($Ctx, [int]$QtdPeca = 2)
    $b = @{ documentoCliente = '39053344705'; placaVeiculo = $Ctx.Placa; servicos = @(@{ servicoId = $Ctx.ServicoId; quantidade = 1 }); pecas = @(@{ pecaId = $Ctx.PecaId; quantidade = $QtdPeca }) }
    $r = Invoke-Api os POST '/api/ordens-servico' -Token $Ctx.Staff -Body $b
    if ($r.Status -ne 201) { throw "criação da OS devolveu $($r.Status)" }
    [pscustomobject]@{ Id = $r.Json.data.id; Numero = $r.Json.data.numero }
}

function Get-Saga {
    param($Ctx, [string]$OsId)
    $r = Invoke-Api os GET "/api/ordens-servico/$OsId/saga" -Token $Ctx.Staff
    if ($r.Status -eq 200) { $r.Json.data }
}

function Wait-Saga {
    param($Ctx, [string]$OsId, [string]$Estado, [int]$TimeoutSec = 60)
    Wait-Until -What "saga $OsId em $Estado" -TimeoutSec $TimeoutSec -Condition { $s = Get-Saga $Ctx $OsId; if ($s -and $s.estado -eq $Estado) { $s } }
}

function Get-Execucao {
    param($Ctx, [string]$OsId, [string]$Tipo, [int]$TimeoutSec = 60)
    Wait-Until -What "execução $Tipo da OS" -TimeoutSec $TimeoutSec -Condition {
        $r = Invoke-Api execution GET "/execucao/execucoes?osId=$OsId" -Token $Ctx.Staff
        $r.Json | Where-Object tipo -eq $Tipo | Select-Object -First 1
    }
}

function Complete-Execucao {
    param($Ctx, $Execucao)
    foreach ($passo in @(@{ P = "/execucao/$($Execucao.id)/iniciar"; B = $null }, @{ P = "/execucao/$($Execucao.id)/tarefas"; B = @{ descricao = 'Tarefa sintética' } }, @{ P = "/execucao/$($Execucao.id)/concluir"; B = $null })) {
        $r = Invoke-Api execution POST $passo.P -Token $Ctx.Staff -Body $passo.B
        if ($r.Status -ge 300) { throw "execução $($passo.P) devolveu $($r.Status)" }
    }
}

# Estados e comandos observados na saga, na ordem, a partir dos passos expostos pela API.
function Get-Observado {
    param($Saga)
    $passos = @($Saga.passos | Sort-Object ordem)
    $estados = @()
    foreach ($p in $passos) { if ($p.evento -like 'ESTADO:*') { $e = $p.evento.Substring(7); if ($estados.Count -eq 0 -or $estados[-1] -ne $e) { $estados += $e } } }
    [pscustomobject]@{ Estados = $estados; Comandos = @($passos | Where-Object { $_.evento -like 'COMANDO:*' } | ForEach-Object { $_.evento.Substring(8) }) }
}

function Compare-Lista {
    param([string]$Nome, $Esperado, $Observado, [System.Collections.Generic.List[string]]$Falhas)
    if (($Esperado -join ',') -ne ($Observado -join ',')) { $Falhas.Add("$Nome esperado [$($Esperado -join ', ')] observado [$($Observado -join ', ')]") }
}

function Compare-Valor {
    param([string]$Nome, $Esperado, $Observado, [System.Collections.Generic.List[string]]$Falhas)
    if ("$Esperado" -ne "$Observado") { $Falhas.Add("$Nome esperado $Esperado observado $Observado") }
}

function New-Resultado {
    param([System.Collections.Generic.List[string]]$Falhas, [string]$Ok)
    if ($Falhas.Count -eq 0) { @{ Status = 'PASS'; Detalhe = $Ok } } else { @{ Status = 'FAIL'; Detalhe = ($Falhas -join '; ') } }
}

$Handlers['BDD-01a'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    $inbox0 = [int](Get-OsSql 'select count(*) from saga_inbox')
    $os = New-OsSintetica $ctx -QtdPeca 2
    Wait-Saga $ctx $os.Id 'AGUARDANDO_DIAGNOSTICO' | Out-Null
    Complete-Execucao $ctx (Get-Execucao $ctx $os.Id 'DIAGNOSTICO')
    Wait-Saga $ctx $os.Id 'AGUARDANDO_DECISAO' | Out-Null
    $cliente = New-CustomerToken -ClienteId $ctx.ClienteId -IdentityVersion $ctx.Versao
    $d = Invoke-Api os POST "/api/ordens-servico/$($os.Numero)/orcamento/decisao" -Token $cliente -Body @{ decisao = 'APROVADO' }
    if ($d.Status -ne 200) { throw "decisão do cliente devolveu $($d.Status)" }
    Wait-Saga $ctx $os.Id 'AGUARDANDO_PAGAMENTO' | Out-Null
    $pag = Wait-Until -What 'pagamento PENDENTE no billing' -Condition { Get-BillingSql "select id from pagamento where os_id='$($os.Id)' and estado='PENDENTE'" }
    $s = Invoke-Api billing POST "/billing/simulacoes/pagamentos/$pag" -Token $ctx.Staff -Body @{ status = 'approved' }
    if ($s.Status -ne 200) { throw "simulação de pagamento devolveu $($s.Status)" }
    Wait-Saga $ctx $os.Id 'AGUARDANDO_REPARO' | Out-Null
    Complete-Execucao $ctx (Get-Execucao $ctx $os.Id 'REPARO')
    $final = Wait-Saga $ctx $os.Id 'CONCLUIDA'
    $o = Get-Observado $final
    $or = $Cenario
    Compare-Lista 'estados da saga' $or.estadosSaga ($o.Estados | Where-Object { $_ -notin $or.estadosTransitorios }) $falhas
    Compare-Lista 'comandos' $or.comandos $o.Comandos $falhas
    Compare-Lista 'pilha de compensação final' @($or.pilhaCompensacaoFinal) @($final.compensacoesPendentes) $falhas
    $osLinha = Get-OsSql "select status from ordens_servico where id='$($os.Id)'"
    Compare-Valor 'status final da OS' $or.statusOsFinal $osLinha $falhas
    Compare-Valor 'saga_outbox' $or.contagens.comandos_saga_outbox (Get-OsSql "select count(*) from saga_outbox where os_id='$($os.Id)'") $falhas
    Compare-Valor 'saga_inbox (delta do cenário)' $or.contagens.saga_inbox ([int](Get-OsSql 'select count(*) from saga_inbox') - $inbox0) $falhas
    foreach ($t in $or.contagens.execucoes.PSObject.Properties) {
        $n = (Invoke-Api execution GET "/execucao/execucoes?osId=$($os.Id)" -Token $ctx.Staff).Json | Where-Object tipo -eq $t.Name | Measure-Object | Select-Object -ExpandProperty Count
        Compare-Valor "execuções $($t.Name)" $t.Value $n $falhas
    }
    Compare-Valor 'orçamento APROVADO' $or.contagens.orcamentos.APROVADO (Get-BillingSql "select count(*) from orcamento where os_id='$($os.Id)' and estado='APROVADO'") $falhas
    Compare-Valor 'pagamento CONFIRMADO' $or.contagens.pagamentos.CONFIRMADO (Get-BillingSql "select count(*) from pagamento where os_id='$($os.Id)' and estado='CONFIRMADO'") $falhas
    Compare-Valor 'estoque da peça (5 - 2)' 3 (Get-OsSql "select quantidade_estoque from pecas where id='$($ctx.PecaId)'") $falhas
    New-Resultado $falhas "estados e contagens conferem (OS $($os.Numero))"
}
