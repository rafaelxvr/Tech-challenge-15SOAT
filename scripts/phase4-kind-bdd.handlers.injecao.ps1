# Handlers BDD-03c, 04a, 04b, 04c, 05b e 05c (dot-source de phase4-kind-bdd.ps1, depois de phase4-kind-bdd.handlers.falhas.ps1).
# O harness injeta mensagens direto nas filas FIFO do LocalStack (`awslocal sqs send-message` dentro do pod, só localhost:4566,
# credenciais fictícias): replies na fila de respostas (INJ-R) e comandos nas filas dos serviços (INJ-C). Os demais passos usam os
# três serviços reais. As injeções reaproveitam sagaId/osId/causationId lidos do banco do os-service. Dados sintéticos apenas.
#
# A deduplicação FIFO do SQS filtraria um reenvio com o mesmo MessageDeduplicationId; por isso a duplicata injetada leva um
# deduplication id novo e o MESMO messageId do envelope, de modo que quem barra é o inbox do serviço, não o SQS.

$script:TraceparentInjecao = '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01'

function New-EnvelopeInjetado {
    param([ValidateSet('COMMAND', 'REPLY')][string]$Kind, [string]$Type, [string]$SagaId, [string]$OsId, [int]$Passo,
          [string]$CausationId, $Payload, [string]$MessageId = [guid]::NewGuid().ToString())
    [ordered]@{
        messageId = $MessageId; kind = $Kind; type = $Type; schemaVersion = 1; sagaId = $SagaId; osId = $OsId; passo = $Passo
        causationId = $(if ($CausationId) { $CausationId } else { $null })
        occurredAt = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'"); correlationId = [guid]::NewGuid().ToString()
        traceparent = $script:TraceparentInjecao; payload = $Payload
    }
}

# Publica um envelope (hashtable) ou um JSON pronto na fila; o grupo FIFO é o osId, como nos publicadores reais.
function Send-MensagemInjetada {
    param([string]$Queue, $Envelope, [string]$DedupId = [guid]::NewGuid().ToString())
    $json = if ($Envelope -is [string]) { $Envelope } else { $Envelope | ConvertTo-Json -Compress -Depth 8 }
    $osId = ($json | ConvertFrom-Json).osId
    $sh = 'awslocal sqs send-message --queue-url "http://localhost:4566/000000000000/$1" --message-group-id "$2" --message-deduplication-id "$3" --message-body "$(cat)" --output text >/dev/null'
    Invoke-Kube @('-n', 'infra', 'exec', '-i', 'deploy/localstack', '--', 'sh', '-c', $sh, 'sh', $Queue, $osId, $DedupId) -StdIn $json | Out-Null
}

function Clear-Fila {
    param([string]$Queue)
    Invoke-Kube @('-n', 'infra', 'exec', 'deploy/localstack', '--', 'awslocal', 'sqs', 'purge-queue', '--queue-url', "http://localhost:4566/000000000000/$Queue") | Out-Null
}

function Get-SagaIds {
    param([string]$OsId)
    $l = Get-OsSql "select id || '|' || coalesce(comando_aguardado_id::text,'') from os_saga where os_id='$OsId'"
    $id, $aguardado = $l -split '\|'
    [pscustomobject]@{ SagaId = $id; Aguardado = $aguardado }
}

function Get-OutboxEnvelope {
    param([string]$OsId, [string]$Tipo)
    Get-OsSql "select envelope::text from saga_outbox where os_id='$OsId' and tipo='$Tipo' order by sequencia desc limit 1" | ConvertFrom-Json
}

# Linhas do log do os-service com o evento e o id (saga) informados.
function Get-LinhasLog {
    param([string]$Evento, [string]$Id)
    $r = Invoke-Kube @('-n', 'oficina', 'logs', 'deploy/oficina-os-service', '--since=30m', '--tail=-1') -AllowFailure
    @($r.Output -split "`n" | Where-Object { $_ -like "*$Evento*" -and $_ -like "*$Id*" }).Count
}

function Wait-Log {
    param([string]$Evento, [string]$Id, [int]$Minimo = 1, [int]$TimeoutSec = 90)
    Wait-Until -What "log $Evento ($Id)" -TimeoutSec $TimeoutSec -Condition { $n = Get-LinhasLog $Evento $Id; if ($n -ge $Minimo) { $n } }
}

function Get-QtdPassos {
    param($Ctx, [string]$OsId)
    @((Get-Saga $Ctx $OsId).passos).Count
}

function Get-QtdPassosEvento {
    param($Ctx, [string]$OsId, [string]$Evento)
    @((Get-Saga $Ctx $OsId).passos | Where-Object evento -eq $Evento).Count
}

$Handlers['BDD-03c'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 1
    $os = New-OsSintetica $ctx -QtdPeca 2
    $pag = Step-AteAguardandoPagamento $ctx $os
    $ids = Get-SagaIds $os.Id
    Set-DeploymentReplicas oficina-billing oficina-billing oficina-billing 0   # o EstornarPagamento fica sem consumidor
    try {
        $decisao = Get-OutboxEnvelope $os.Id 'RegistrarDecisao'
        # O billing devolveria PagamentoConfirmado; o harness o injeta. Estoque 1 < 2 leva a ESTOQUE_INSUFICIENTE e a COMPENSANDO.
        Send-MensagemInjetada 'os-saga-replies.fifo' (New-EnvelopeInjetado REPLY 'PagamentoConfirmado' $ids.SagaId $os.Id $decisao.passo $ids.Aguardado @{ pagamentoId = $pag })
        Wait-Until -What 'saga COMPENSANDO aguardando EstornarPagamento' -TimeoutSec 90 -Condition {
            $s = Get-Saga $ctx $os.Id
            if ($s -and $s.estado -eq 'COMPENSANDO' -and (Get-OutboxOs $os.Id 'EstornarPagamento')) { $s }
        } | Out-Null
        $ids = Get-SagaIds $os.Id
        $estorno = Get-OutboxEnvelope $os.Id 'EstornarPagamento'
        Send-MensagemInjetada 'os-saga-replies.fifo' (New-EnvelopeInjetado REPLY 'CompensacaoFalhou' $ids.SagaId $os.Id $estorno.passo $ids.Aguardado @{ compensacao = 'EstornarPagamento' })
        $final = Wait-Saga $ctx $os.Id 'FALHA_COMPENSACAO' -TimeoutSec 90
        Compare-EstadosEComandos $Cenario $final $falhas -Sufixo
        Compare-Lista 'pilha de compensação preservada' @($Cenario.pilhaCompensacaoFinal) @($final.compensacoesPendentes) $falhas
        $status = Get-OsSql "select status from ordens_servico where id='$($os.Id)'"
        if ($status -eq $Cenario.statusOsFinalNao) { $falhas.Add("OS não deveria estar $($Cenario.statusOsFinalNao)") }
        Start-Sleep -Seconds 20   # um ciclo do poller de prazos: o estado terminal não pode mudar
        Compare-Valor 'saga continua FALHA_COMPENSACAO' 'FALHA_COMPENSACAO' (Get-Saga $ctx $os.Id).estado $falhas
    }
    finally {
        Clear-Fila 'billing-commands.fifo'   # o EstornarPagamento pendente não deve ser processado na volta do billing
        Set-DeploymentReplicas oficina-billing oficina-billing oficina-billing 1
    }
    New-Resultado $falhas "CompensacaoFalhou injetada: COMPENSANDO -> FALHA_COMPENSACAO, pilha [$($final.compensacoesPendentes -join ', ')] preservada, OS em $status (OS $($os.Numero))"
}

$Handlers['BDD-04a'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    $os = New-OsSintetica $ctx -QtdPeca 2
    Wait-Saga $ctx $os.Id 'AGUARDANDO_DIAGNOSTICO' -TimeoutSec 120 | Out-Null
    $ids = Get-SagaIds $os.Id
    $diag = Get-OutboxEnvelope $os.Id 'IniciarDiagnostico'
    $reply = New-EnvelopeInjetado REPLY 'DiagnosticoConcluido' $ids.SagaId $os.Id $diag.passo $ids.Aguardado @{ execucaoId = [guid]::NewGuid().ToString() }
    Send-MensagemInjetada 'os-saga-replies.fifo' $reply
    Send-MensagemInjetada 'os-saga-replies.fifo' $reply   # mesmo messageId, deduplication id novo
    Wait-Saga $ctx $os.Id 'AGUARDANDO_DECISAO' -TimeoutSec 120 | Out-Null
    Wait-Log 'saga_resposta_duplicada' $os.Id | Out-Null
    Compare-Valor 'transições por DiagnosticoConcluido' $Cenario.contagens.transicoes (Get-QtdPassosEvento $ctx $os.Id 'RESPOSTA:DiagnosticoConcluido') $falhas
    Compare-Valor 'linhas no saga_inbox por messageId' $Cenario.contagens.saga_inbox_por_messageId (Get-OsSql "select count(*) from saga_inbox where message_id='$($reply.messageId)'") $falhas
    Compare-Valor 'log saga_resposta_duplicada' 1 (Get-LinhasLog 'saga_resposta_duplicada' $os.Id) $falhas
    Compare-Valor 'fila de respostas drenada' 0 (Get-QueueTotal 'os-saga-replies.fifo') $falhas
    New-Resultado $falhas "reply duplicada (mesmo messageId) transita uma vez e o inbox descarta a segunda (OS $($os.Numero))"
}

$Handlers['BDD-04b'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    $os = New-OsSintetica $ctx -QtdPeca 2
    Step-AteDecisao $ctx $os   # orçamento GERADO no billing
    $ids = Get-SagaIds $os.Id
    $versao = (Get-Saga $ctx $os.Id).versaoOrcamento
    $msgs = @()
    foreach ($n in 1, 2) {
        $cmd = New-EnvelopeInjetado COMMAND 'CancelarOrcamento' $ids.SagaId $os.Id 4 $null @{ versaoOrcamento = $versao; motivo = 'DECISAO_EXPIRADA' }
        $msgs += $cmd.messageId
        Send-MensagemInjetada 'billing-commands.fifo' $cmd
        Wait-Until -What "OrcamentoCancelado #$n na outbox do billing" -TimeoutSec 90 -Condition {
            $c = [int](Get-BillingSql "select count(*) from outbox where os_id='$($os.Id)' and tipo='OrcamentoCancelado'")
            if ($c -ge $n) { $c }
        } | Out-Null
    }
    $linhas = Get-BillingSql "select tipo || '|' || coalesce(envelope->'payload'->>'orcamentoId','') from outbox where os_id='$($os.Id)' and tipo='OrcamentoCancelado' order by sequencia"
    $respostas = @($linhas -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    Compare-Lista 'respostas do billing' $Cenario.respostas @($respostas | ForEach-Object { ($_ -split '\|')[0] }) $falhas
    $orcIds = @($respostas | ForEach-Object { ($_ -split '\|')[1] } | Select-Object -Unique)
    Compare-Valor 'mesmo orcamentoId nas duas respostas' $Cenario.contagens.mesmoOrcamentoIdNasDuasRespostas ($orcIds.Count -eq 1 -and $orcIds[0] -ne '') $falhas
    Compare-Valor 'orçamento CANCELADO (sem efeito duplo)' $Cenario.contagens.orcamentos.CANCELADO (Get-BillingSql "select count(*) from orcamento where os_id='$($os.Id)' and estado='CANCELADO'") $falhas
    Compare-Valor 'comandos registrados no inbox do billing' 2 (Get-BillingSql "select count(*) from inbox where message_id in ('$($msgs -join "','")')") $falhas
    Compare-Valor 'orçamentos da OS' 1 (Get-BillingSql "select count(*) from orcamento where os_id='$($os.Id)'") $falhas
    Compare-Valor 'fila de comandos do billing drenada' 0 (Get-QueueTotal 'billing-commands.fifo') $falhas
    Compare-Valor 'DLQ do billing vazia' 0 (Get-QueueTotal 'billing-commands-dlq.fifo') $falhas
    New-Resultado $falhas "CancelarOrcamento reenviado com novo messageId: 2 OrcamentoCancelado do mesmo orçamento $($orcIds -join ','), orçamento CANCELADO uma vez (OS $($os.Numero))"
}

$Handlers['BDD-04c'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    Set-DeploymentReplicas oficina-execution oficina-execution oficina-execution 0   # as três cópias ficam juntas na fila
    try {
        $os = New-OsSintetica $ctx -QtdPeca 2
        Wait-Saga $ctx $os.Id 'AGUARDANDO_DIAGNOSTICO' -TimeoutSec 120 | Out-Null
        Wait-Until -What 'IniciarDiagnostico PUBLISHED' -TimeoutSec 60 -Condition { (Get-OutboxOs $os.Id 'IniciarDiagnostico') -like 'PUBLISHED*' } | Out-Null
        $json = Get-OsSql "select envelope::text from saga_outbox where os_id='$($os.Id)' and tipo='IniciarDiagnostico'"
        Send-MensagemInjetada 'execution-commands.fifo' $json
        Send-MensagemInjetada 'execution-commands.fifo' $json   # mesmo messageId do comando real, deduplication ids novos
        Compare-Valor 'cópias do comando na fila antes de religar' 3 (Get-QueueTotal 'execution-commands.fifo') $falhas
    }
    finally { Set-DeploymentReplicas oficina-execution oficina-execution oficina-execution 1 }
    Get-Execucao $ctx $os.Id 'DIAGNOSTICO' -TimeoutSec 180 | Out-Null
    Wait-Until -What 'fila de comandos da execution drenada' -TimeoutSec 120 -Condition { (Get-QueueTotal 'execution-commands.fifo') -eq 0 } | Out-Null
    Start-Sleep -Seconds 10
    Compare-Valor 'execuções DIAGNOSTICO' $Cenario.contagens.execucoes.DIAGNOSTICO @(Get-ExecucoesDaOs $ctx $os.Id 'DIAGNOSTICO').Count $falhas
    Compare-Valor 'DLQ da execution vazia' 0 (Get-QueueTotal 'execution-commands-dlq.fifo') $falhas
    New-Resultado $falhas "IniciarDiagnostico em 3 cópias (mesmo messageId) cria uma única execução DIAGNOSTICO (OS $($os.Numero))"
}

$Handlers['BDD-05b'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    Set-PrazoSaga SAGA_PRAZO_DIAGNOSTICO $script:PrazoCurto
    try {
        $os = New-OsSintetica $ctx -QtdPeca 2
        Wait-Saga $ctx $os.Id 'AGUARDANDO_DIAGNOSTICO' -TimeoutSec 120 | Out-Null
        $diag = Get-OutboxEnvelope $os.Id 'IniciarDiagnostico'
        $cancelada = Wait-Saga $ctx $os.Id 'CANCELADA' -TimeoutSec 150   # DIAGNOSTICO_SEM_RESPOSTA
        Compare-Valor 'motivo da falha' 'DIAGNOSTICO_SEM_RESPOSTA' $cancelada.motivoFalha $falhas
        $antes = Get-QtdPassos $ctx $os.Id
        $ids = Get-SagaIds $os.Id
        Send-MensagemInjetada 'os-saga-replies.fifo' (New-EnvelopeInjetado REPLY 'DiagnosticoConcluido' $ids.SagaId $os.Id $diag.passo $diag.messageId @{ execucaoId = [guid]::NewGuid().ToString() })
        Wait-Log 'saga_resposta_obsoleta' $os.Id | Out-Null
        Start-Sleep -Seconds 5
        $depois = Get-Saga $ctx $os.Id
        Compare-Lista 'estado final da saga' $Cenario.estadosSaga @($depois.estado) $falhas
        Compare-Valor 'passos novos após a reply tardia' $Cenario.contagens.transicoes_apos_resposta ($depois.passos.Count - $antes) $falhas
        Compare-Valor 'DiagnosticoConcluido não registrada' 0 (Get-QtdPassosEvento $ctx $os.Id 'RESPOSTA:DiagnosticoConcluido') $falhas
        Compare-Valor 'status da OS' 'CANCELADA' (Get-OsSql "select status from ordens_servico where id='$($os.Id)'") $falhas
        Compare-Valor 'fila de respostas drenada' 0 (Get-QueueTotal 'os-saga-replies.fifo') $falhas
    }
    finally { Set-PrazoSaga SAGA_PRAZO_DIAGNOSTICO $null }
    New-Resultado $falhas "DiagnosticoConcluido com causationId antigo descartada como obsoleta; saga segue CANCELADA ($antes passos antes e depois, OS $($os.Numero))"
}

$Handlers['BDD-05c'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    $os = New-OsSintetica $ctx -QtdPeca 2
    Wait-Saga $ctx $os.Id 'AGUARDANDO_DIAGNOSTICO' -TimeoutSec 120 | Out-Null
    $ids = Get-SagaIds $os.Id
    $antes = Get-QtdPassos $ctx $os.Id
    $semSaga = [guid]::NewGuid().ToString()
    $incompativel = New-EnvelopeInjetado REPLY 'PagamentoConfirmado' $ids.SagaId $os.Id 2 $ids.Aguardado @{ pagamentoId = [guid]::NewGuid().ToString() }
    $inexistente = New-EnvelopeInjetado REPLY 'DiagnosticoConcluido' $semSaga ([guid]::NewGuid().ToString()) 1 ([guid]::NewGuid().ToString()) @{ execucaoId = [guid]::NewGuid().ToString() }
    $desconhecida = New-EnvelopeInjetado REPLY 'ReplyInventada' $ids.SagaId $os.Id 1 $ids.Aguardado @{ x = 1 }
    foreach ($e in $incompativel, $inexistente, $desconhecida) { Send-MensagemInjetada 'os-saga-replies.fifo' $e }
    Wait-Log 'saga_resposta_incompativel' $ids.SagaId | Out-Null
    Wait-Log 'saga_resposta_sem_saga' $semSaga | Out-Null
    Wait-Log 'saga_resposta_desconhecida' $ids.SagaId | Out-Null
    Compare-Valor 'log saga_resposta_incompativel' 1 (Get-LinhasLog 'saga_resposta_incompativel' $ids.SagaId) $falhas
    Compare-Valor 'log saga_resposta_sem_saga' 1 (Get-LinhasLog 'saga_resposta_sem_saga' $semSaga) $falhas
    Compare-Valor 'log saga_resposta_desconhecida' 1 (Get-LinhasLog 'saga_resposta_desconhecida' $ids.SagaId) $falhas
    $depois = Get-Saga $ctx $os.Id
    Compare-Valor 'estado da saga' 'AGUARDANDO_DIAGNOSTICO' $depois.estado $falhas
    Compare-Valor 'passos novos após as replies descartadas' $Cenario.contagens.transicoes_apos_resposta ($depois.passos.Count - $antes) $falhas
    Compare-Valor 'comando aguardado inalterado' $ids.Aguardado (Get-SagaIds $os.Id).Aguardado $falhas
    Compare-Valor 'fila de respostas drenada' 0 (Get-QueueTotal 'os-saga-replies.fifo') $falhas
    Compare-Valor 'DLQ de respostas vazia' 0 (Get-QueueTotal 'os-saga-replies-dlq.fifo') $falhas
    New-Resultado $falhas "3 replies descartadas (incompatível, sem saga, tipo desconhecido) sem mudar a saga (OS $($os.Numero))"
}
