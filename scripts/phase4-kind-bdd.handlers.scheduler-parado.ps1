# Handlers BDD-06c, 06e, 06f e 06g (dot-source de phase4-kind-bdd.ps1, depois de phase4-kind-bdd.handlers.scheduler.ps1).
# Cenários com um serviço parado (replicas=0) ou com a validade do pagamento do billing encurtada. Tudo é restaurado no
# finally (replicas=1, variáveis removidas), mesmo se o cenário falhar. Dados sintéticos apenas.

$script:PrazoRespostaCurto = '30s'

function Set-ReplicasBilling { param([int]$N) Set-DeploymentReplicas oficina-billing oficina-billing oficina-billing $N }
function Set-ReplicasExecution { param([int]$N) Set-DeploymentReplicas oficina-execution oficina-execution oficina-execution $N }

function Set-ValidadePagamentoBilling {
    param($Valor)
    Set-DeploymentEnv oficina-billing oficina-billing oficina-billing BILLING_PAGAMENTO_VALIDADE $Valor
}

# Quantas vezes um passo da saga (evento exato) foi registrado.
function Get-ContagemPasso {
    param($Saga, [string]$Evento)
    @($Saga.passos | Where-Object evento -eq $Evento).Count
}

$Handlers['BDD-06c'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    try {
        $os = New-OsSintetica $ctx -QtdPeca 2
        Step-AteDecisao $ctx $os   # o prazo curto só entra depois: com 30s o GerarOrcamento (primeira mensagem após restart, ~60 s) estouraria antes
        Set-PrazoSaga SAGA_PRAZO_RESPOSTA $script:PrazoRespostaCurto
        Set-ReplicasBilling 0   # billing mudo entre a decisão e o prazo de resposta automática
        Send-DecisaoCliente $ctx $os 'APROVADO'
        $final = Wait-Saga $ctx $os.Id 'COMPENSANDO' -TimeoutSec 150
        Compare-EstadosEComandos $Cenario $final $falhas -Sufixo -SomenteEstados
        Compare-Valor 'motivo da falha' $Cenario.motivoFalha $final.motivoFalha $falhas
        Compare-Valor 'compensação em andamento' 1 (Get-ContagemPasso $final 'COMPENSACAO:CANCELAR_ORCAMENTO') $falhas
    }
    finally {
        Set-ReplicasBilling 1
        Set-PrazoSaga SAGA_PRAZO_RESPOSTA $null
    }
    # Com o billing de volta a compensação pendente é confirmada e a saga encerra (observação, não critério do oráculo).
    $fim = Wait-Saga $ctx $os.Id 'CANCELADA' -TimeoutSec 180
    New-Resultado $falhas "billing parado após a decisão: DECISAO_SEM_RESPOSTA e COMPENSANDO; com o billing de volta a saga fecha em $($fim.estado) (OS $($os.Numero))"
}

$Handlers['BDD-06e'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    Set-ValidadePagamentoBilling '30s'
    Set-PrazoSaga SAGA_PRAZO_PAGAMENTO '10m'
    try {
        $os = New-OsSintetica $ctx -QtdPeca 2
        $pag = Step-AteAguardandoPagamento $ctx $os   # ninguém paga: o billing expira o pagamento antes da saga (10 min)
        $inicio = Get-Date
        $final = Wait-Saga $ctx $os.Id 'CANCELADA' -TimeoutSec 240
        $espera = [int]((Get-Date) - $inicio).TotalSeconds
        Compare-Valor 'motivo da falha' $Cenario.motivoFalha $final.motivoFalha $falhas
        Compare-Valor 'pagamento EXPIRADO' $Cenario.contagens.pagamentos.EXPIRADO (Get-BillingSql "select count(*) from pagamento where id='$pag' and estado='EXPIRADO'") $falhas
        Compare-Valor 'reply PagamentoExpirado na outbox do billing' 1 (Get-BillingSql "select count(*) from outbox where os_id='$($os.Id)' and tipo='PagamentoExpirado'") $falhas
        Compare-Valor 'status final da OS' 'CANCELADA' (Get-OsSql "select status from ordens_servico where id='$($os.Id)'") $falhas
    }
    finally {
        Set-PrazoSaga SAGA_PRAZO_PAGAMENTO $null
        Set-ValidadePagamentoBilling $null
    }
    New-Resultado $falhas "billing expirou o pagamento em ${espera}s; PagamentoExpirado levou a saga a PAGAMENTO_EXPIRADO com prazo da saga de 10m (OS $($os.Numero))"
}

$Handlers['BDD-06f'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    try {
        $os = New-OsSintetica $ctx -QtdPeca 2
        $pag = Step-AteAguardandoPagamento $ctx $os
        Set-PrazoSaga SAGA_PRAZO_RESPOSTA $script:PrazoRespostaCurto   # só vale para o IniciarReparo emitido a seguir
        Set-ReplicasExecution 0   # execution muda depois da reserva de peças
        Send-SimulacaoPagamento $ctx $pag 'approved'
        $final = Wait-Saga $ctx $os.Id 'COMPENSANDO' -TimeoutSec 240
        Compare-Valor 'motivo da falha' $Cenario.motivoFalha $final.motivoFalha $falhas
        Compare-Lista 'pilha antes da compensação' $Cenario.pilhaAntesDaCompensacao @($final.compensacoesPendentes) $falhas
        Compare-Valor 'CancelarExecucao enviado' 1 (Get-ContagemPasso $final 'COMANDO:CancelarExecucao') $falhas
        Compare-Valor 'estoque reservado (5 - 2)' 3 (Get-OsSql "select quantidade_estoque from pecas where id='$($ctx.PecaId)'") $falhas
    }
    finally {
        Set-ReplicasExecution 1
        Set-PrazoSaga SAGA_PRAZO_RESPOSTA $null
    }
    # Com a execution de volta a pilha é desempilhada até o fim (observação).
    $fim = Wait-Saga $ctx $os.Id 'CANCELADA' -TimeoutSec 240
    New-Resultado $falhas "execution parada após a reserva: REPARO_SEM_RESPOSTA, pilha completa; com a execution de volta a saga fecha em $($fim.estado) (OS $($os.Numero))"
}

$Handlers['BDD-06g'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    Set-PrazoSaga SAGA_MAX_TENTATIVAS_COMPENSACAO 3
    Set-PrazoSaga SAGA_PRAZO_COMPENSACAO $script:PrazoCurto
    Set-PrazoSaga SAGA_PRAZO_DECISAO '90s'
    try {
        $os = New-OsSintetica $ctx -QtdPeca 2
        Step-AteDecisao $ctx $os
        Set-ReplicasBilling 0   # billing parado antes de a decisão expirar: a compensação CancelarOrcamento nunca é respondida
        $final = Wait-Saga $ctx $os.Id 'FALHA_COMPENSACAO' -TimeoutSec 420
        Compare-EstadosEComandos $Cenario $final $falhas -Sufixo -SomenteEstados
        Compare-Valor 'envios do mesmo comando (CancelarOrcamento)' $Cenario.contagens.envios_da_mesma_compensacao (Get-ContagemPasso $final 'COMANDO:CancelarOrcamento') $falhas
        Compare-Valor 'reenvios' $Cenario.contagens.reenvios (Get-ContagemPasso $final 'REENVIO:CANCELAR_ORCAMENTO') $falhas
        Compare-Valor 'compensações pendentes (pilha não vazia)' 1 @($final.compensacoesPendentes).Count $falhas
        if ((Get-OsSql "select status from ordens_servico where id='$($os.Id)'") -eq 'CANCELADA') { $falhas.Add('a OS não deveria estar CANCELADA com a compensação esgotada') }
    }
    finally {
        Set-ReplicasBilling 1
        Set-PrazoSaga SAGA_PRAZO_DECISAO $null
        Set-PrazoSaga SAGA_PRAZO_COMPENSACAO $null
        Set-PrazoSaga SAGA_MAX_TENTATIVAS_COMPENSACAO $null
    }
    New-Resultado $falhas "compensação sem resposta: 3 envios (1 + 2 reenvios) e FALHA_COMPENSACAO (OS $($os.Numero))"
}
