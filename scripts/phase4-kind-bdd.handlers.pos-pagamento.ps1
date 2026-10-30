# Handlers BDD-03b, 03d, 04d e 04e (dot-source de phase4-kind-bdd.ps1, depois de phase4-kind-bdd.handlers.ps1).
# Só dependem dos três serviços reais: nenhuma injeção de mensagem no LocalStack. Dados sintéticos apenas.

# Leva a OS até a execution aceitar o reparo. A execution responde ReparoIniciado ~0,5 s depois de IniciarReparo,
# então AGUARDANDO_REPARO dura menos que o intervalo de polling: o ponto estável é EM_EXECUCAO (execução NA_FILA, vaga ocupada).
function Step-AteReparoAceito {
    param($Ctx, $Os)
    $pag = Step-AteAguardandoPagamento $Ctx $Os
    Send-SimulacaoPagamento $Ctx $pag 'approved'
    Wait-Saga $Ctx $Os.Id 'EM_EXECUCAO' | Out-Null
}

# A API omite `prazo` quando nulo; ausente e nulo são o mesmo observável.
function Get-Prazo {
    param($Saga)
    $p = $Saga.PSObject.Properties["prazo"]
    if ($p) { "$($p.Value)" } else { "" }
}

# Vagas de reparo ocupadas na filial padrão (item CAPACIDADE#MATRIZ no DynamoDB da execution); 0 quando o item não existe.
function Get-CapacidadeAtiva {
    $r = Invoke-Kube @('-n', 'infra', 'exec', 'deploy/localstack', '--', 'awslocal', 'dynamodb', 'scan', '--table-name', 'oficina-phase4-execucoes',
        '--query', "Items[?execucaoId.S=='CAPACIDADE#MATRIZ'].reparosAtivos.N | [0]", '--output', 'text')
    $v = $r.Output.Trim()
    if ($v -match '^\d+$') { [int]$v } else { 0 }
}

function Get-ExecucoesDaOs {
    param($Ctx, [string]$OsId, [string]$Tipo)
    @((Invoke-Api execution GET "/execucao/execucoes?osId=$OsId" -Token $Ctx.Staff).Json | Where-Object tipo -eq $Tipo)
}

$Handlers['BDD-03b'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 10
    $ocupadas = Get-CapacidadeAtiva
    if ($ocupadas -ne 0) { return @{ Status = 'FAIL'; Detalhe = "pré-condição: $ocupadas vaga(s) de reparo ocupada(s) por cenário anterior; use cluster limpo" } }
    # Capacidade 1 só neste cenário: a OS A ocupa a única vaga e a OS B é recusada por CAPACIDADE_ESGOTADA.
    Set-DeploymentEnv oficina-execution oficina-execution oficina-execution EXECUCAO_CAPACIDADE_REPAROS '1'
    try {
        $osA = New-OsSintetica $ctx -QtdPeca 2
        Step-AteReparoAceito $ctx $osA
        $ctxB = $ctx.PSObject.Copy()
        $ctxB.Placa = Add-Veiculo $ctx.ClienteId
        $estoqueAntes = [int](Get-OsSql "select quantidade_estoque from pecas where id='$($ctx.PecaId)'")
        $osB = New-OsSintetica $ctxB -QtdPeca 2
        $pag = Step-AteAguardandoPagamento $ctxB $osB
        Send-SimulacaoPagamento $ctxB $pag 'approved'
        $final = Wait-Saga $ctxB $osB.Id 'CANCELADA' -TimeoutSec 90
        Compare-EstadosEComandos $Cenario $final $falhas -Sufixo
        Compare-Valor 'motivo da falha' $Cenario.motivoFalha $final.motivoFalha $falhas
        Compare-Lista 'pilha de compensação final' @($Cenario.pilhaCompensacaoFinal) @($final.compensacoesPendentes) $falhas
        Compare-Valor 'status final da OS' $Cenario.statusOsFinal (Get-OsSql "select status from ordens_servico where id='$($osB.Id)'") $falhas
        $recusadas = @(Get-ExecucoesDaOs $ctx $osB.Id 'REPARO' | Where-Object status -eq 'RECUSADA')
        Compare-Valor 'execuções REPARO RECUSADA' $Cenario.contagens.execucoes.RECUSADA $recusadas.Count $falhas
        Compare-Valor 'estoque decrementado pela OS recusada' $Cenario.contagens.estoqueDecrementadoPorPeca ($estoqueAntes - [int](Get-OsSql "select quantidade_estoque from pecas where id='$($ctx.PecaId)'")) $falhas
        Compare-Valor 'pagamento ESTORNADO' 1 (Get-BillingSql "select count(*) from pagamento where os_id='$($osB.Id)' and estado='ESTORNADO'") $falhas
        Compare-Valor 'orçamento CANCELADO' 1 (Get-BillingSql "select count(*) from orcamento where os_id='$($osB.Id)' and estado='CANCELADO'") $falhas
        # Conclui o reparo da OS A: libera a vaga e prova que a recusa da B não a tocou.
        Complete-Execucao $ctx (Get-Execucao $ctx $osA.Id 'REPARO')
        Wait-Saga $ctx $osA.Id 'CONCLUIDA' | Out-Null
    }
    finally { Set-DeploymentEnv oficina-execution oficina-execution oficina-execution EXECUCAO_CAPACIDADE_REPAROS $null }
    New-Resultado $falhas "capacidade esgotada compensa a pilha inteira (OS $($osB.Numero)); OS $($osA.Numero) concluída"
}

$Handlers['BDD-03d'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    $os = New-OsSintetica $ctx -QtdPeca 2
    Step-AteReparoAceito $ctx $os
    $s = Get-Saga $ctx $os.Id
    Compare-Lista 'estado da saga' $Cenario.estadosSaga @((Get-Observado $s).Estados | Select-Object -Last @($Cenario.estadosSaga).Count) $falhas
    Compare-Lista 'pilha de compensação' @($Cenario.pilhaCompensacaoFinal) @($s.compensacoesPendentes) $falhas
    Compare-Valor 'prazo' '' (Get-Prazo $s) $falhas
    # Um ciclo inteiro do poller de prazos (15 s) não pode mexer na saga pivô.
    Start-Sleep -Seconds 20
    $depois = Get-Saga $ctx $os.Id
    Compare-Valor 'estado após o poller de prazos' 'EM_EXECUCAO' $depois.estado $falhas
    Compare-Valor 'prazo após o poller de prazos' '' (Get-Prazo $depois) $falhas
    Compare-Valor 'passos COMPENSANDO' 0 @((Get-Observado $depois).Estados | Where-Object { $_ -eq 'COMPENSANDO' }).Count $falhas
    Compare-Valor 'status da OS' 'EM_EXECUCAO' (Get-OsSql "select status from ordens_servico where id='$($os.Id)'") $falhas
    Complete-Execucao $ctx (Get-Execucao $ctx $os.Id 'REPARO')   # libera a vaga de capacidade
    New-Resultado $falhas "ReparoIniciado esvazia a pilha e o poller não toca a saga (OS $($os.Numero))"
}

$Handlers['BDD-04d'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    $os = New-OsSintetica $ctx -QtdPeca 2
    $pag = Step-AteAguardandoPagamento $ctx $os
    Send-SimulacaoPagamento $ctx $pag 'approved'
    $segunda = Invoke-Api billing POST "/billing/simulacoes/pagamentos/$pag" -Token $ctx.Staff -Body @{ status = 'approved' }
    Wait-Saga $ctx $os.Id 'EM_EXECUCAO' | Out-Null
    Start-Sleep -Seconds 5   # margem para uma eventual segunda reply chegar e ser descartada
    $ids = @((Get-BillingSql "select message_id from outbox where os_id='$($os.Id)' and tipo='PagamentoConfirmado'") -split "`n" | Where-Object { $_ })
    Compare-Valor 'PagamentoConfirmado emitida pelo billing' $Cenario.contagens.PagamentoConfirmado_na_fila_de_respostas $ids.Count $falhas
    if ($ids.Count -ge 1) {
        $lista = ($ids | ForEach-Object { "'$_'" }) -join ','
        Compare-Valor 'PagamentoConfirmado processada no inbox do os-service' 1 (Get-OsSql "select count(*) from saga_inbox where message_id in ($lista)") $falhas
    }
    Compare-Valor 'pagamento CONFIRMADO' $Cenario.contagens.pagamentos.CONFIRMADO (Get-BillingSql "select count(*) from pagamento where os_id='$($os.Id)' and estado='CONFIRMADO'") $falhas
    Compare-Valor 'IniciarReparo enviado uma vez' 1 (Get-OsSql "select count(*) from saga_outbox where os_id='$($os.Id)' and tipo='IniciarReparo'") $falhas
    Compare-Valor 'estoque decrementado uma vez (5 - 2)' 3 (Get-OsSql "select quantidade_estoque from pecas where id='$($ctx.PecaId)'") $falhas
    Complete-Execucao $ctx (Get-Execucao $ctx $os.Id 'REPARO')   # libera a vaga de capacidade
    New-Resultado $falhas "segunda simulação (HTTP $($segunda.Status)) não gera nova reply (OS $($os.Numero))"
}

$Handlers['BDD-04e'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    # Execution fora do ar: IniciarDiagnostico fica retido na fila FIFO, onde a deduplicação por messageId é observável.
    Set-DeploymentReplicas oficina-execution oficina-execution oficina-execution 0
    try {
        $os = New-OsSintetica $ctx -QtdPeca 2
        $cmd = Wait-Until -What 'IniciarDiagnostico PUBLISHED na outbox' -Condition { Get-OsSql "select message_id || '|' || queue_message_id from saga_outbox where os_id='$($os.Id)' and tipo='IniciarDiagnostico' and estado='PUBLISHED'" }
        $messageId, $primeiroQueueId = $cmd -split '\|'
        $totalAntes = Get-QueueTotal 'execution-commands.fifo'
        # Reentrega at-least-once: devolve a linha a PENDING com o mesmo messageId e deixa o relay publicar de novo.
        Get-OsSql "update saga_outbox set estado='PENDING', publicado_em=null, disponivel_em=now() where message_id='$messageId'" | Out-Null
        Wait-Until -What 'republicação da mesma mensagem' -Condition { Get-OsSql "select 1 from saga_outbox where message_id='$messageId' and estado='PUBLISHED'" } | Out-Null
        Start-Sleep -Seconds 3
        $linha = Get-OsSql "select count(*) || '|' || max(queue_message_id) from saga_outbox where message_id='$messageId'"
        Compare-Valor 'linhas da outbox para o messageId' 1 ($linha -split '\|')[0] $falhas
        Compare-Valor 'MessageId devolvido pelo SQS na reentrega (deduplicado)' $primeiroQueueId ($linha -split '\|')[1] $falhas
        Compare-Valor 'mensagens na fila antes da reentrega' $Cenario.contagens.comandos_distintos_por_messageId_na_fila $totalAntes $falhas
        Compare-Valor 'mensagens retidas na fila depois da reentrega' $Cenario.contagens.comandos_distintos_por_messageId_na_fila (Get-QueueTotal 'execution-commands.fifo') $falhas
    }
    finally { Set-DeploymentReplicas oficina-execution oficina-execution oficina-execution 1 }
    Get-Execucao $ctx $os.Id 'DIAGNOSTICO' | Out-Null
    Compare-Valor 'execuções DIAGNOSTICO criadas' 1 (Get-ExecucoesDaOs $ctx $os.Id 'DIAGNOSTICO').Count $falhas
    New-Resultado $falhas "reentrega da outbox não duplica mensagem nem efeito (OS $($os.Numero))"
}
