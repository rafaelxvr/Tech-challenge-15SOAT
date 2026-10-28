# Handlers BDD-08a a 08e (dot-source de phase4-kind-bdd.ps1, depois de phase4-kind-bdd.handlers.injecao.ps1).
# Mensagens venenosas, DLQ e redrive manual no LocalStack do Kind. A visibilidade da fila sob teste cai de 60 s para 5 s só
# durante o cenário (restaurada no finally), de modo que os 5 recebimentos do maxReceiveCount cabem em ~30 s. O consumidor
# só apaga a mensagem quando processa; se lança exceção, ela volta à fila e, no 5º recebimento, o SQS a move para a DLQ.
# O redrive é manual (receive + send + delete): StartMessageMoveTask não foi verificado no LocalStack 3.8. Dados sintéticos apenas.

$script:VisibilidadeDlq = 5
$script:VisibilidadePadrao = 60
$script:PoisonJson = '{ isto nao e json'

function Set-VisibilidadeFila {
    param([string]$Queue, [int]$Segundos)
    $attrs = @{ VisibilityTimeout = "$Segundos" } | ConvertTo-Json -Compress
    Invoke-Kube @('-n', 'infra', 'exec', '-i', 'deploy/localstack', '--', 'sh', '-c', 'awslocal sqs set-queue-attributes --queue-url "http://localhost:4566/000000000000/$1" --attributes "$(cat)"', 'sh', $Queue) -StdIn $attrs | Out-Null
}

# Injeta um corpo cru (inclusive JSON inválido) com grupo e deduplication id explícitos.
function Send-CorpoCru {
    param([string]$Queue, [string]$Corpo, [string]$Grupo, [string]$DedupId = [guid]::NewGuid().ToString())
    $sh = 'awslocal sqs send-message --queue-url "http://localhost:4566/000000000000/$1" --message-group-id "$2" --message-deduplication-id "$3" --message-body "$(cat)" --output text >/dev/null'
    Invoke-Kube @('-n', 'infra', 'exec', '-i', 'deploy/localstack', '--', 'sh', '-c', $sh, 'sh', $Queue, $Grupo, $DedupId) -StdIn $Corpo | Out-Null
}

# Lê (sem retirar da DLQ: visibilidade 0) até 10 mensagens: devolve objetos { Body; Receipt; Group }.
function Get-MensagensDlq {
    param([string]$Queue, [int]$VisibilidadeSeg = 0)
    $r = Invoke-Kube @('-n', 'infra', 'exec', 'deploy/localstack', '--', 'awslocal', 'sqs', 'receive-message', '--queue-url', "http://localhost:4566/000000000000/$Queue",
        '--max-number-of-messages', '10', '--visibility-timeout', "$VisibilidadeSeg", '--attribute-names', 'MessageGroupId', '--output', 'json')
    if (-not $r.Output.Trim()) { return @() }
    @(($r.Output | ConvertFrom-Json).Messages | ForEach-Object { [pscustomobject]@{ Body = $_.Body.TrimEnd(); Receipt = $_.ReceiptHandle; Group = $_.Attributes.MessageGroupId } })
}

function Remove-MensagemDlq {
    param([string]$Queue, [string]$Receipt)
    Invoke-Kube @('-n', 'infra', 'exec', 'deploy/localstack', '--', 'awslocal', 'sqs', 'delete-message', '--queue-url', "http://localhost:4566/000000000000/$Queue", '--receipt-handle', $Receipt) | Out-Null
}

function Get-LinhasLogServico {
    param([string]$Namespace, [string]$Deployment, [string]$Evento)
    $r = Invoke-Kube @('-n', $Namespace, 'logs', "deploy/$Deployment", '--since=30m', '--tail=-1') -AllowFailure
    @($r.Output -split "`n" | Where-Object { $_ -like "*$Evento*" }).Count
}

# Fila e DLQ do par começam vazias (purge) e com a visibilidade curta; devolve o necessário para restaurar.
function Initialize-FilaVenenosa {
    param([string]$Queue, [string]$Dlq)
    Clear-Fila $Queue
    Clear-Fila $Dlq
    Set-VisibilidadeFila $Queue $script:VisibilidadeDlq
}

function Restore-Fila {
    param([string]$Queue, [string]$Dlq)
    Set-VisibilidadeFila $Queue $script:VisibilidadePadrao
    Clear-Fila $Queue
    Clear-Fila $Dlq
}

$Handlers['BDD-08a'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    $fila = 'billing-commands.fifo'; $dlq = 'billing-commands-dlq.fifo'
    Initialize-FilaVenenosa $fila $dlq
    try {
        $grupoVenenoso = [guid]::NewGuid().ToString()
        $base = Get-LinhasLogServico oficina-billing oficina-billing 'billing_comando_falhou'
        Send-CorpoCru $fila $script:PoisonJson $grupoVenenoso
        # Outra OS (outro grupo FIFO) segue o fluxo real enquanto a venenosa é recebida e devolvida.
        $os = New-OsSintetica $ctx -QtdPeca 2
        Step-AteDecisao $ctx $os
        $orcamentos = Get-BillingSql "select count(*) from orcamento where os_id='$($os.Id)' and estado='GERADO'"
        Compare-Valor 'orçamento da outra OS gerado' $Cenario.contagens.mensagem_valida_de_outra_os_processada $orcamentos $falhas
        Wait-Until -What 'venenosa na DLQ do billing' -TimeoutSec 120 -Condition { (Get-QueueTotal $dlq) -ge 1 } | Out-Null
        Compare-Valor 'mensagens na DLQ' $Cenario.contagens.dlq_billing_commands (Get-QueueTotal $dlq) $falhas
        $corpos = @(Get-MensagensDlq $dlq)
        Compare-Valor 'corpo na DLQ é a venenosa' $script:PoisonJson ($corpos[0].Body) $falhas
        Compare-Valor 'grupo preservado na DLQ' $grupoVenenoso ($corpos[0].Group) $falhas
        Wait-Until -What 'fila de comandos drenada' -TimeoutSec 60 -Condition { (Get-QueueTotal $fila) -eq 0 } | Out-Null
        $tentativas = (Get-LinhasLogServico oficina-billing oficina-billing 'billing_comando_falhou') - $base
        if ($tentativas -lt $Cenario.contagens.maxReceiveCount) { $falhas.Add("recebimentos com falha: esperado >= $($Cenario.contagens.maxReceiveCount), observado $tentativas") }
    }
    finally { Restore-Fila $fila $dlq }
    New-Resultado $falhas "venenosa (JSON inválido) foi à DLQ após $tentativas falhas; a OS $($os.Numero) de outro grupo gerou orçamento normalmente"
}

$Handlers['BDD-08b'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    $fila = 'billing-commands.fifo'; $dlq = 'billing-commands-dlq.fifo'
    $os = New-OsSintetica $ctx -QtdPeca 2
    Step-AteDecisao $ctx $os   # orçamento GERADO; o grupo FIFO é o osId
    Initialize-FilaVenenosa $fila $dlq
    try {
        $ids = Get-SagaIds $os.Id
        $versao = (Get-Saga $ctx $os.Id).versaoOrcamento
        $base = Get-LinhasLogServico oficina-billing oficina-billing 'billing_comando_falhou'
        Send-CorpoCru $fila $script:PoisonJson $os.Id
        $valido = New-EnvelopeInjetado COMMAND 'CancelarOrcamento' $ids.SagaId $os.Id 4 $null @{ versaoOrcamento = $versao; motivo = 'DECISAO_EXPIRADA' }
        Send-MensagemInjetada $fila $valido   # mesmo grupo, depois da venenosa
        $processadoEm = $null; $dlqEm = $null; $falhasAteProcessar = $null
        $fim = (Get-Date).AddSeconds(150)
        while ((Get-Date) -lt $fim -and -not $processadoEm) {
            if (-not $dlqEm -and (Get-QueueTotal $dlq) -ge 1) { $dlqEm = Get-Date }
            if ([int](Get-BillingSql "select count(*) from outbox where os_id='$($os.Id)' and tipo='OrcamentoCancelado'") -ge 1) {
                $processadoEm = Get-Date
                $falhasAteProcessar = (Get-LinhasLogServico oficina-billing oficina-billing 'billing_comando_falhou') - $base
            } else { Start-Sleep -Seconds 1 }
        }
        if (-not $processadoEm) { throw 'o comando válido do mesmo grupo nunca foi processado' }
        if ($falhasAteProcessar -lt 5) { $falhas.Add("válido processado com a venenosa ainda no grupo: apenas $falhasAteProcessar recebimentos com falha (esperado >= 5)") }
        Wait-Until -What 'venenosa na DLQ' -TimeoutSec 30 -Condition { (Get-QueueTotal $dlq) -ge 1 } | Out-Null
        Compare-Valor 'mensagens na DLQ' 1 (Get-QueueTotal $dlq) $falhas
        Compare-Valor 'orçamento CANCELADO pelo comando posterior' 1 (Get-BillingSql "select count(*) from orcamento where os_id='$($os.Id)' and estado='CANCELADO'") $falhas
        Compare-Valor 'fila de comandos drenada' 0 (Get-QueueTotal $fila) $falhas
    }
    finally { Restore-Fila $fila $dlq }
    New-Resultado $falhas "CancelarOrcamento do mesmo grupo ficou retido até a venenosa sair: processado com $falhasAteProcessar falhas registradas (OS $($os.Numero))"
}

$Handlers['BDD-08c'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    $fila = 'billing-commands.fifo'; $dlq = 'billing-commands-dlq.fifo'
    $os = New-OsSintetica $ctx -QtdPeca 2
    Step-AteDecisao $ctx $os
    Initialize-FilaVenenosa $fila $dlq
    Get-BillingSql "update orcamento set versao=99 where os_id='$($os.Id)'" | Out-Null   # o RegistrarDecisao real (versão 1) aponta para um orçamento que não existe
    # inbox não tem os_id e acumula os RegistrarDecisao dos cenários anteriores: o rollback se prova pelo delta.
    $inboxAntes = [int](Get-BillingSql "select count(*) from inbox where tipo='RegistrarDecisao'")
    Set-PrazoSaga SAGA_PRAZO_RESPOSTA $script:PrazoCurto
    try {
        Send-DecisaoCliente $ctx $os 'APROVADO'
        $final = Wait-Saga $ctx $os.Id 'COMPENSANDO' -TimeoutSec 150
        Compare-Valor 'motivo da falha' $Cenario.motivoFalha $final.motivoFalha $falhas
        Wait-Until -What 'RegistrarDecisao na DLQ do billing' -TimeoutSec 120 -Condition { (Get-QueueTotal $dlq) -ge 1 } | Out-Null
        $tipos = @(Get-MensagensDlq $dlq | ForEach-Object { ($_.Body | ConvertFrom-Json).type } | Sort-Object -Unique)
        if ($tipos -notcontains 'RegistrarDecisao') { $falhas.Add("DLQ sem RegistrarDecisao (tipos: $($tipos -join ','))") }
        Compare-Valor 'respostas à decisão no billing' $Cenario.contagens.respostas (Get-BillingSql "select count(*) from outbox where os_id='$($os.Id)' and tipo in ('OrcamentoAprovado','OrcamentoRecusado')") $falhas
        Compare-Valor 'pagamentos criados' 0 (Get-BillingSql "select count(*) from pagamento where os_id='$($os.Id)'") $falhas
        Compare-Valor 'novos registros do RegistrarDecisao no inbox do billing (rollback)' 0 ([int](Get-BillingSql "select count(*) from inbox where tipo='RegistrarDecisao'") - $inboxAntes) $falhas
        $dlqTotal = Get-QueueTotal $dlq
    }
    finally { Set-PrazoSaga SAGA_PRAZO_RESPOSTA $null; Restore-Fila $fila $dlq }
    New-Resultado $falhas "RegistrarDecisao de orçamento inexistente: sem reply, DLQ com [$($tipos -join ',')] ($dlqTotal msg), saga COMPENSANDO por $($final.motivoFalha) (OS $($os.Numero))"
}

$Handlers['BDD-08d'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    $fila = 'os-saga-replies.fifo'; $dlq = 'os-saga-replies-dlq.fifo'
    $os = New-OsSintetica $ctx -QtdPeca 2
    Wait-Saga $ctx $os.Id 'AGUARDANDO_DIAGNOSTICO' -TimeoutSec 120 | Out-Null
    Initialize-FilaVenenosa $fila $dlq
    try {
        $antes = Get-Saga $ctx $os.Id
        $aguardado = (Get-SagaIds $os.Id).Aguardado
        $base = Get-LinhasLogServico oficina oficina-os-service 'saga_resposta_falhou'
        Send-CorpoCru $fila $script:PoisonJson $os.Id
        Wait-Until -What 'reply venenosa na DLQ do os-service' -TimeoutSec 120 -Condition { (Get-QueueTotal $dlq) -ge 1 } | Out-Null
        Compare-Valor 'mensagens na DLQ de respostas' $Cenario.contagens.dlq_os_saga_replies (Get-QueueTotal $dlq) $falhas
        Compare-Valor 'corpo na DLQ é a venenosa' $script:PoisonJson (@(Get-MensagensDlq $dlq)[0].Body) $falhas
        $depois = Get-Saga $ctx $os.Id
        Compare-Valor 'estado da saga' 'AGUARDANDO_DIAGNOSTICO' $depois.estado $falhas
        Compare-Valor 'transições após a venenosa' $Cenario.contagens.transicoes ($depois.passos.Count - $antes.passos.Count) $falhas
        Compare-Valor 'comando aguardado inalterado' $aguardado (Get-SagaIds $os.Id).Aguardado $falhas
        $tentativas = (Get-LinhasLogServico oficina oficina-os-service 'saga_resposta_falhou') - $base
        if ($tentativas -lt 5) { $falhas.Add("recebimentos com falha: esperado >= 5, observado $tentativas") }
        Compare-Valor 'fila de respostas drenada' 0 (Get-QueueTotal $fila) $falhas
    }
    finally { Restore-Fila $fila $dlq }
    New-Resultado $falhas "reply venenosa foi à os-saga-replies-dlq após $tentativas falhas; saga seguiu em AGUARDANDO_DIAGNOSTICO sem transições (OS $($os.Numero))"
}

$Handlers['BDD-08e'] = {
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
        # Causa: o orçamento da versão pedida não existe (a versão é deslocada); o comando é válido e vai à DLQ.
        Get-BillingSql "update orcamento set versao=99 where os_id='$($os.Id)'" | Out-Null
        $cmd = New-EnvelopeInjetado COMMAND 'RegistrarDecisao' $ids.SagaId $os.Id 4 $null @{ versaoOrcamento = $versao; decisao = 'APROVADO' }
        Send-MensagemInjetada $fila $cmd
        Wait-Until -What 'comando válido na DLQ' -TimeoutSec 120 -Condition { (Get-QueueTotal $dlq) -ge 1 } | Out-Null
        # Causa corrigida; redrive manual: lê da DLQ, republica na fila original (mesmo grupo, deduplication id novo) e remove da DLQ.
        Get-BillingSql "update orcamento set versao=$versao where os_id='$($os.Id)'" | Out-Null
        $msg = @(Get-MensagensDlq $dlq 30)[0]
        if (-not $msg) { throw 'a DLQ não devolveu a mensagem para o redrive' }
        Send-CorpoCru $fila $msg.Body $msg.Group
        Remove-MensagemDlq $dlq $msg.Receipt
        Wait-Until -What 'RegistrarDecisao reprocessado (OrcamentoAprovado)' -TimeoutSec 120 -Condition {
            [int](Get-BillingSql "select count(*) from outbox where os_id='$($os.Id)' and tipo='OrcamentoAprovado'") -ge 1
        } | Out-Null
        Start-Sleep -Seconds 10
        Compare-Valor 'DLQ depois do redrive' $Cenario.contagens.dlq_apos_redrive (Get-QueueTotal $dlq) $falhas
        Compare-Valor 'efeitos de negócio (OrcamentoAprovado)' $Cenario.contagens.efeitos_de_negocio (Get-BillingSql "select count(*) from outbox where os_id='$($os.Id)' and tipo='OrcamentoAprovado'") $falhas
        Compare-Valor 'orçamento APROVADO' 1 (Get-BillingSql "select count(*) from orcamento where os_id='$($os.Id)' and estado='APROVADO'") $falhas
        Compare-Valor 'pagamentos PENDENTE' 1 (Get-BillingSql "select count(*) from pagamento where os_id='$($os.Id)' and estado='PENDENTE'") $falhas
        Compare-Valor 'registros no inbox do billing' 1 (Get-BillingSql "select count(*) from inbox where message_id='$($cmd.messageId)'") $falhas
        Compare-Valor 'fila de comandos drenada' 0 (Get-QueueTotal $fila) $falhas
    }
    finally { Restore-Fila $fila $dlq }
    New-Resultado $falhas "redrive manual (receive+send+delete) reprocessou o RegistrarDecisao uma vez: orçamento APROVADO, 1 pagamento, DLQ vazia (OS $($os.Numero))"
}
