# Handlers BDD-07e, 07f e 08f: exercício do runbook D19-R (docs/runbooks/saga-recuperacao-d19r.md).
# (dot-source de phase4-kind-bdd.ps1, depois de phase4-kind-bdd.handlers.dlq.ps1.)
# As consultas de detecção e a instrução de re-arme abaixo são as MESMAS do runbook; se uma mudar, mude a outra.
# Dados sintéticos apenas. Nenhum segredo é lido ou impresso.

# Linhas da saga_outbox em PENDING/BLOCKED (o runbook pede essa contagem como detecção).
function Get-OutboxAbertas {
    param([string]$OsId, [string]$Estado)
    [int](Get-OsSql "select count(*) from saga_outbox where os_id='$OsId' and estado='$Estado'")
}

# Re-arme idempotente de UMA linha BLOCKED: só age se ainda estiver BLOCKED; devolve as linhas afetadas.
function Invoke-RearmeOutbox {
    param([string]$MessageId)
    [int](Get-OsSql "with r as (update saga_outbox set estado='PENDING', tentativas=0, disponivel_em=now(), ultimo_erro_codigo=NULL where message_id='$MessageId' and estado='BLOCKED' returning 1) select count(*) from r")
}

$Handlers['BDD-07e'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    $os = New-OsSintetica $ctx -QtdPeca 2
    Step-AteDecisao $ctx $os
    Set-LocalStackOffline $true
    try {
        Send-DecisaoCliente $ctx $os 'APROVADO'
        Wait-Until -What 'RegistrarDecisao PENDING com tentativa' -TimeoutSec 60 -Condition {
            if ((Get-OutboxOs $os.Id 'RegistrarDecisao') -match '^PENDING\|([1-9]\d*)$') { $Matches[1] }
        } | Out-Null
        # Fixture sintético: coloca a linha a uma falha do limite (11 de 12) e a torna elegível já; o BLOCKED em si vem do publisher real.
        Get-OsSql "update saga_outbox set tentativas=11, disponivel_em=now() where os_id='$($os.Id)' and tipo='RegistrarDecisao' and estado='PENDING'" | Out-Null
        Wait-Until -What 'RegistrarDecisao BLOCKED' -TimeoutSec 60 -Condition {
            if ((Get-OutboxOs $os.Id 'RegistrarDecisao') -like 'BLOCKED|*') { $true }
        } | Out-Null
    }
    finally { Set-LocalStackOffline $false }
    $linha = Get-OsSql "select message_id || '|' || tentativas || '|' || coalesce(ultimo_erro_codigo,'') from saga_outbox where os_id='$($os.Id)' and tipo='RegistrarDecisao'"
    $msgId, $tentativas, $erro = $linha -split '\|'
    Compare-Valor 'tentativas ao bloquear' $Cenario.contagens.tentativas_ao_bloquear $tentativas $falhas
    Compare-Valor 'código do último erro' 'QUEUE_SEND_FAILED' $erro $falhas
    # Detecção (runbook D19-R, passo 1): a linha aparece na consulta global de BLOCKED.
    Compare-Valor 'detecção: linhas BLOCKED' $Cenario.contagens.outbox_blocked_antes_do_rearme (Get-OutboxAbertas $os.Id 'BLOCKED') $falhas
    # Sem ação do operador nada se recupera: o publisher só consome PENDING.
    Start-Sleep -Seconds 25
    Compare-Valor 'continua BLOCKED com o LocalStack de volta' 1 (Get-OutboxAbertas $os.Id 'BLOCKED') $falhas
    Compare-Lista 'saga retida' @($Cenario.estadosSaga[0]) @((Get-Saga $ctx $os.Id).estado) $falhas
    Compare-Valor 'nenhum orçamento APROVADO antes do re-arme' 0 (Get-BillingSql "select count(*) from orcamento where os_id='$($os.Id)' and estado='APROVADO'") $falhas
    # Recuperação (passo 3): re-arme.
    Compare-Valor 'linhas re-armadas' 1 (Invoke-RearmeOutbox $msgId) $falhas
    $final = Wait-Saga $ctx $os.Id $Cenario.estadosSaga[1] -TimeoutSec 240
    Compare-Valor 'BLOCKED depois do re-arme' $Cenario.contagens.outbox_blocked_depois_do_rearme (Get-OutboxAbertas $os.Id 'BLOCKED') $falhas
    Compare-Valor 'RegistrarDecisao PUBLISHED' $Cenario.contagens.RegistrarDecisao_PUBLISHED (Get-OsSql "select count(*) from saga_outbox where os_id='$($os.Id)' and tipo='RegistrarDecisao' and estado='PUBLISHED'") $falhas
    Compare-Valor 'RegistrarDecisao total na outbox' 1 (Get-OsSql "select count(*) from saga_outbox where os_id='$($os.Id)' and tipo='RegistrarDecisao'") $falhas
    Compare-Valor 'orçamento APROVADO (efeito único)' $Cenario.contagens.efeitos_de_negocio (Get-BillingSql "select count(*) from orcamento where os_id='$($os.Id)' and estado='APROVADO'") $falhas
    Compare-Valor 'pagamento PENDENTE (efeito único)' 1 (Get-BillingSql "select count(*) from pagamento where os_id='$($os.Id)' and estado='PENDENTE'") $falhas
    Compare-Valor 'linhas no inbox do billing' 1 (Get-BillingSql "select count(*) from inbox where message_id='$msgId'") $falhas
    # Idempotência do próprio re-arme: repetir não afeta nenhuma linha e não republica.
    Compare-Valor 're-arme repetido: linhas afetadas' $Cenario.contagens.rearme_repetido_linhas_afetadas (Invoke-RearmeOutbox $msgId) $falhas
    Start-Sleep -Seconds 5
    Compare-Valor 'filas de comandos e DLQ vazias' 0 ((Get-QueueTotal 'billing-commands.fifo') + (Get-QueueTotal 'billing-commands-dlq.fifo')) $falhas
    New-Resultado $falhas "BLOCKED com $tentativas tentativas ($erro) permaneceu após a volta do LocalStack; re-arme entregou uma vez (saga $($final.estado), 1 orçamento APROVADO, 1 pagamento, 1 inbox); re-arme repetido afetou 0 linhas (OS $($os.Numero))"
}

$Handlers['BDD-07f'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    $prazos = @{ SAGA_PRAZO_PAGAMENTO = '30s'; SAGA_PRAZO_COMPENSACAO = '30s'; SAGA_MAX_TENTATIVAS_COMPENSACAO = '3' }
    Set-DeploymentEnvs oficina oficina-os-service oficina-os-service $prazos
    try {
        $os = New-OsSintetica $ctx -QtdPeca 2
        Step-AteAguardandoPagamento $ctx $os | Out-Null
        Set-LocalStackOffline $true
        try {
            $final = Wait-Saga $ctx $os.Id 'FALHA_COMPENSACAO' -TimeoutSec 300
            $pilha = @($final.compensacoesPendentes) -join ','
            Compare-Valor 'pilha preservada (não vazia)' $true (@($final.compensacoesPendentes).Count -gt 0) $falhas
            Compare-Valor 'compensação ainda não entregue (PENDING)' 'PENDING' ((Get-OutboxOs $os.Id 'CancelarOrcamento') -replace '\|.*$', '') $falhas
            Compare-Valor 'orçamento ainda APROVADO no billing' 'APROVADO' (Get-BillingSql "select estado from orcamento where os_id='$($os.Id)'") $falhas
        }
        finally { Set-LocalStackOffline $false }
        # Reconciliação: sem intervenção, a outbox entrega a compensação pendente e o billing converge.
        Wait-Until -What 'orçamento CANCELADO no billing' -TimeoutSec 180 -Condition {
            if ((Get-BillingSql "select estado from orcamento where os_id='$($os.Id)'") -eq 'CANCELADO') { $true }
        } | Out-Null
        Wait-Until -What 'saga_outbox sem PENDING/BLOCKED' -TimeoutSec 120 -Condition {
            if ((Get-OutboxAbertas $os.Id 'PENDING') + (Get-OutboxAbertas $os.Id 'BLOCKED') -eq 0) { $true }
        } | Out-Null
        Compare-Valor 'PENDING/BLOCKED após convergir' $Cenario.contagens.outbox_pendente_ou_blocked_apos_convergir ((Get-OutboxAbertas $os.Id 'PENDING') + (Get-OutboxAbertas $os.Id 'BLOCKED')) $falhas
        $envios = Get-OsSql "select count(*) from saga_outbox where os_id='$($os.Id)' and tipo='CancelarOrcamento'"
        Start-Sleep -Seconds 20
        $depois = Get-Saga $ctx $os.Id
        Compare-Valor 'a saga continua FALHA_COMPENSACAO' 'FALHA_COMPENSACAO' $depois.estado $falhas
        Compare-Valor 'pilha preservada após convergir' $pilha (@($depois.compensacoesPendentes) -join ',') $falhas
        Compare-Valor 'orçamento CANCELADO (uma linha)' 1 (Get-BillingSql "select count(*) from orcamento where os_id='$($os.Id)' and estado='CANCELADO'") $falhas
    }
    finally { Set-DeploymentEnvs oficina oficina-os-service oficina-os-service @{ SAGA_PRAZO_PAGAMENTO = $null; SAGA_PRAZO_COMPENSACAO = $null; SAGA_MAX_TENTATIVAS_COMPENSACAO = $null } }
    New-Resultado $falhas "FALHA_COMPENSACAO com pilha [$pilha]; na volta do LocalStack a outbox entregou ($envios linha(s) CancelarOrcamento), o billing foi a CANCELADO e a saga seguiu FALHA_COMPENSACAO (OS $($os.Numero))"
}

$Handlers['BDD-08f'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    $fila = 'billing-commands.fifo'; $dlq = 'billing-commands-dlq.fifo'
    $os = New-OsSintetica $ctx -QtdPeca 2
    Step-AteDecisao $ctx $os
    $ids = Get-SagaIds $os.Id
    $versao = (Get-Saga $ctx $os.Id).versaoOrcamento
    Initialize-FilaVenenosa $fila $dlq
    try {
        Get-BillingSql "update orcamento set versao=99 where os_id='$($os.Id)'" | Out-Null
        $cmd = New-EnvelopeInjetado COMMAND 'RegistrarDecisao' $ids.SagaId $os.Id 4 $null @{ versaoOrcamento = $versao; decisao = 'APROVADO' }
        Send-MensagemInjetada $fila $cmd
        Wait-Until -What 'comando válido na DLQ' -TimeoutSec 120 -Condition { (Get-QueueTotal $dlq) -ge 1 } | Out-Null
        Get-BillingSql "update orcamento set versao=$versao where os_id='$($os.Id)'" | Out-Null
        $msg = @(Get-MensagensDlq $dlq 30)[0]
        if (-not $msg) { throw 'a DLQ não devolveu a mensagem para o redrive' }
        Send-CorpoCru $fila $msg.Body $msg.Group
        Remove-MensagemDlq $dlq $msg.Receipt
        Wait-Until -What 'RegistrarDecisao reprocessado' -TimeoutSec 120 -Condition {
            [int](Get-BillingSql "select count(*) from outbox where os_id='$($os.Id)' and tipo='OrcamentoAprovado'") -ge 1
        } | Out-Null
        Wait-Until -What 'fila drenada após o primeiro redrive' -TimeoutSec 60 -Condition { (Get-QueueTotal $fila) -eq 0 } | Out-Null
        # Segundo redrive da MESMA mensagem (operador repetindo o procedimento): deduplication id novo, mesmo messageId.
        Send-CorpoCru $fila $msg.Body $msg.Group
        Wait-Until -What 'fila drenada após o redrive repetido' -TimeoutSec 60 -Condition { (Get-QueueTotal $fila) -eq 0 } | Out-Null
        Start-Sleep -Seconds 10
        Compare-Valor 'DLQ depois dos redrives' $Cenario.contagens.dlq_apos_redrive (Get-QueueTotal $dlq) $falhas
        Compare-Valor 'efeitos de negócio (OrcamentoAprovado)' $Cenario.contagens.efeitos_de_negocio (Get-BillingSql "select count(*) from outbox where os_id='$($os.Id)' and tipo='OrcamentoAprovado'") $falhas
        Compare-Valor 'orçamento APROVADO' 1 (Get-BillingSql "select count(*) from orcamento where os_id='$($os.Id)' and estado='APROVADO'") $falhas
        Compare-Valor 'pagamentos PENDENTE' 1 (Get-BillingSql "select count(*) from pagamento where os_id='$($os.Id)' and estado='PENDENTE'") $falhas
        Compare-Valor 'linhas no inbox do billing' $Cenario.contagens.linhas_no_inbox (Get-BillingSql "select count(*) from inbox where message_id='$($cmd.messageId)'") $falhas
    }
    finally { Restore-Fila $fila $dlq }
    New-Resultado $falhas "o mesmo comando redriven duas vezes teve um único efeito: 1 OrcamentoAprovado, 1 pagamento, 1 inbox, DLQ e fila vazias (OS $($os.Numero))"
}
