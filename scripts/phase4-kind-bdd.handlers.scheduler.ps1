# Handlers BDD-06a, 06b e 06d (dot-source de phase4-kind-bdd.ps1, depois de phase4-kind-bdd.handlers.pos-pagamento.ps1).
# Prazos da saga encurtados por variável de ambiente só durante o cenário (restaurados no finally); o poller de prazos do
# os-service roda a cada ~15 s. Só dependem dos três serviços reais. Dados sintéticos apenas.

$script:PrazoCurto = '30s'

function Set-PrazoSaga {
    param([string]$Nome, $Valor)
    Set-DeploymentEnv oficina oficina-os-service oficina-os-service $Nome $Valor
}

$Handlers['BDD-06a'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    Set-PrazoSaga SAGA_PRAZO_DIAGNOSTICO $script:PrazoCurto
    try {
        $os = New-OsSintetica $ctx -QtdPeca 2   # ninguém conclui o diagnóstico: o prazo estoura
        $final = Wait-Saga $ctx $os.Id 'CANCELADA' -TimeoutSec 150
        Compare-EstadosEComandos $Cenario $final $falhas
        Compare-Valor 'motivo da falha' $Cenario.motivoFalha $final.motivoFalha $falhas
        Compare-Valor 'status final da OS' $Cenario.statusOsFinal (Get-OsSql "select status from ordens_servico where id='$($os.Id)'") $falhas
        $exec = Wait-Until -What 'execução DIAGNOSTICO CANCELADA' -Condition { @(Get-ExecucoesDaOs $ctx $os.Id 'DIAGNOSTICO' | Where-Object status -eq 'CANCELADA') }
        Compare-Valor 'execuções DIAGNOSTICO CANCELADA' $Cenario.contagens.execucoes.CANCELADA @($exec).Count $falhas
    }
    finally { Set-PrazoSaga SAGA_PRAZO_DIAGNOSTICO $null }
    New-Resultado $falhas "diagnóstico sem conclusão cancela a saga e a execução (OS $($os.Numero))"
}

$Handlers['BDD-06b'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    Set-PrazoSaga SAGA_PRAZO_DECISAO $script:PrazoCurto
    try {
        $os = New-OsSintetica $ctx -QtdPeca 2
        Step-AteDecisao $ctx $os   # orçamento gerado; o cliente não decide
        $final = Wait-Saga $ctx $os.Id 'CANCELADA' -TimeoutSec 150
        Compare-EstadosEComandos $Cenario $final $falhas -Sufixo
        Compare-Valor 'motivo da falha' $Cenario.motivoFalha $final.motivoFalha $falhas
        Compare-Valor 'status final da OS' $Cenario.statusOsFinal (Get-OsSql "select status from ordens_servico where id='$($os.Id)'") $falhas
        Compare-Valor 'orçamento CANCELADO' 1 (Wait-Until -What 'orçamento CANCELADO no billing' -Condition { Get-BillingSql "select count(*) from orcamento where os_id='$($os.Id)' and estado='CANCELADO'" | Where-Object { $_ -ne '0' } }) $falhas
    }
    finally { Set-PrazoSaga SAGA_PRAZO_DECISAO $null }
    New-Resultado $falhas "decisão expirada cancela o orçamento (OS $($os.Numero))"
}

$Handlers['BDD-06d'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    Set-PrazoSaga SAGA_PRAZO_PAGAMENTO $script:PrazoCurto
    try {
        $os = New-OsSintetica $ctx -QtdPeca 2
        $pag = Step-AteAguardandoPagamento $ctx $os   # ninguém paga: o prazo da saga estoura
        $final = Wait-Saga $ctx $os.Id 'CANCELADA' -TimeoutSec 150
        Compare-EstadosEComandos $Cenario $final $falhas -Sufixo
        Compare-Valor 'motivo da falha' $Cenario.motivoFalha $final.motivoFalha $falhas
        Compare-Valor 'status final da OS' 'CANCELADA' (Get-OsSql "select status from ordens_servico where id='$($os.Id)'") $falhas
        Compare-Valor 'pagamento CANCELADO' $Cenario.contagens.pagamentos.CANCELADO (Wait-Until -What 'pagamento CANCELADO no billing' -Condition { Get-BillingSql "select count(*) from pagamento where id='$pag' and estado='CANCELADO'" | Where-Object { $_ -ne '0' } }) $falhas
        # Aprovação tardia: o billing estorna o pagamento (título do oráculo) e a saga não muda.
        $tardia = Invoke-Api billing POST "/billing/simulacoes/pagamentos/$pag" -Token $ctx.Staff -Body @{ status = 'approved' }
        Compare-Valor 'HTTP da aprovação tardia' 200 $tardia.Status $falhas
        Compare-Valor 'pagamento ESTORNADO após aprovação tardia' 'ESTORNADO' (Wait-Until -What 'pagamento ESTORNADO' -Condition { Get-BillingSql "select estado from pagamento where id='$pag' and estado='ESTORNADO'" }) $falhas
        Compare-Valor 'saga continua CANCELADA após aprovação tardia' 'CANCELADA' (Get-Saga $ctx $os.Id).estado $falhas
        $obs = "aprovação tardia: HTTP $($tardia.Status), pagamento ESTORNADO"
    }
    finally { Set-PrazoSaga SAGA_PRAZO_PAGAMENTO $null }
    New-Resultado $falhas "pagamento expirado pela saga cancela o orçamento (OS $($os.Numero)); $obs"
}

$Handlers['BDD-05a'] = {
    param($Cenario, $Oraculos)
    $falhas = [System.Collections.Generic.List[string]]::new()
    $ctx = Initialize-Cenario -Estoque 5
    Set-PrazoSaga SAGA_PRAZO_PAGAMENTO $script:PrazoCurto
    try {
        $os = New-OsSintetica $ctx -QtdPeca 2
        $pag = Step-AteAguardandoPagamento $ctx $os
        Wait-Saga $ctx $os.Id 'CANCELADA' -TimeoutSec 150 | Out-Null
        Wait-Until -What 'pagamento CANCELADO no billing' -Condition { Get-BillingSql "select id from pagamento where id='$pag' and estado='CANCELADO'" } | Out-Null
        $inboxAntes = [int](Get-OsSql "select count(*) from saga_inbox")
        $respostasAntes = [int](Get-BillingSql "select count(*) from outbox where os_id='$($os.Id)' and tipo like 'Pagamento%'")
        Send-SimulacaoPagamento $ctx $pag 'approved'
        Wait-Until -What 'pagamento ESTORNADO' -Condition { Get-BillingSql "select id from pagamento where id='$pag' and estado='ESTORNADO'" } | Out-Null
        Start-Sleep -Seconds 5   # margem para uma eventual reply chegar ao os-service
        $final = Get-Saga $ctx $os.Id
        Compare-Lista 'estado da saga' $Cenario.estadosSaga @($final.estado) $falhas
        Compare-Valor 'respostas novas do billing' $Cenario.respostasNovasNaFilaDeRespostas ([int](Get-BillingSql "select count(*) from outbox where os_id='$($os.Id)' and tipo like 'Pagamento%'") - $respostasAntes) $falhas
        Compare-Valor 'mensagens novas no inbox do os-service' 0 ([int](Get-OsSql "select count(*) from saga_inbox") - $inboxAntes) $falhas
        Compare-Valor 'pagamento ESTORNADO' $Cenario.contagens.pagamentos.ESTORNADO (Get-BillingSql "select count(*) from pagamento where os_id='$($os.Id)' and estado='ESTORNADO'") $falhas
        Compare-Valor 'orçamento CANCELADO' $Cenario.contagens.orcamentos.CANCELADO (Get-BillingSql "select count(*) from orcamento where os_id='$($os.Id)' and estado='CANCELADO'") $falhas
        Compare-Valor 'status final da OS' $Cenario.statusOsFinal (Get-OsSql "select status from ordens_servico where id='$($os.Id)'") $falhas
    }
    finally { Set-PrazoSaga SAGA_PRAZO_PAGAMENTO $null }
    New-Resultado $falhas "aprovação após CANCELADA é estornada sem reply (OS $($os.Numero))"
}
