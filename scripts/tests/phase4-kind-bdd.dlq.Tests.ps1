# Pester (v3+): BDD-08c não pode depender de o polling enxergar o estado transitório COMPENSANDO.
# Roda sem cluster: o relógio e todos os colaboradores de cluster são simulados; as funções de espera e de comparação são as reais.
#   Invoke-Pester scripts/tests/phase4-kind-bdd.dlq.Tests.ps1

$scripts = Split-Path -Parent $PSScriptRoot
$Handlers = @{}
foreach ($f in 'phase4-kind-bdd.support.ps1', 'phase4-kind-bdd.handlers.ps1', 'phase4-kind-bdd.handlers.scheduler.ps1', 'phase4-kind-bdd.handlers.injecao.ps1', 'phase4-kind-bdd.handlers.dlq.ps1') {
    . (Join-Path $scripts $f)
}

function New-PassosSaga {
    param([string[]]$Estados)
    $i = 0
    @($Estados | ForEach-Object { $i++; [pscustomobject]@{ ordem = $i; evento = "ESTADO:$_" } })
}

Describe 'BDD-08c com estado transitório pulado pelo polling' {
    $cenario = [pscustomobject]@{ motivoFalha = 'PRAZO_RESPOSTA'; contagens = [pscustomobject]@{ respostas = 0 } }

    function Initialize-Dublês {
        param([string[]]$Historico)
        # Relógio simulado: cada Start-Sleep avança 1 s, então um polling que nunca converge estoura o timeout sem esperar de verdade.
        $global:AgoraSimulado = [datetime]'2026-01-01T00:00:00'
        $global:HistoricoSaga = $Historico
        $global:LeiturasSaga = 0
        Mock Get-Date { $global:AgoraSimulado }
        Mock Start-Sleep { $global:AgoraSimulado = $global:AgoraSimulado.AddSeconds(1) }
        Mock Initialize-Cenario { [pscustomobject]@{ Staff = 't' } }
        Mock New-OsSintetica { [pscustomobject]@{ Id = 'os-1'; Numero = 1 } }
        Mock Step-AteDecisao { }
        Mock Initialize-FilaVenenosa { }
        Mock Restore-Fila { }
        Mock Set-PrazoSaga { }
        Mock Send-DecisaoCliente { }
        Mock Get-QueueTotal { 1 }
        Mock Get-MensagensDlq { [pscustomobject]@{ Body = '{"type":"RegistrarDecisao"}' } }
        Mock Get-BillingSql { '0' }
        # Duas leituras ainda em AGUARDANDO_DECISAO e a seguinte já em CANCELADA: COMPENSANDO só existe no histórico de passos.
        Mock Get-Saga {
            $global:LeiturasSaga++
            if ($global:LeiturasSaga -le 2) {
                [pscustomobject]@{ estado = 'AGUARDANDO_DECISAO'; motivoFalha = $null; passos = (New-PassosSaga 'AGUARDANDO_DECISAO') }
            } else {
                [pscustomobject]@{ estado = 'CANCELADA'; motivoFalha = 'PRAZO_RESPOSTA'; passos = (New-PassosSaga $global:HistoricoSaga) }
            }
        }
    }

    It 'passa quando o polling só observa CANCELADA e o histórico contém COMPENSANDO' {
        Initialize-Dublês @('AGUARDANDO_DECISAO', 'COMPENSANDO', 'CANCELADA')
        $r = & $Handlers['BDD-08c'] $cenario $null
        $r.Status | Should Be 'PASS'
        $global:LeiturasSaga | Should BeGreaterThan 2
    }

    It 'falha quando a saga chega a CANCELADA sem nunca passar por COMPENSANDO' {
        Initialize-Dublês @('AGUARDANDO_DECISAO', 'CANCELADA')
        $r = & $Handlers['BDD-08c'] $cenario $null
        $r.Status | Should Be 'FAIL'
        $r.Detalhe | Should Match 'saga não passou por COMPENSANDO'
    }
}

Describe 'BDD-08d com a 5ª linha de log chegando depois da DLQ' {
    $cenario = [pscustomobject]@{ contagens = [pscustomobject]@{ dlq_os_saga_replies = 1; transicoes = 0 } }

    function Initialize-DublesOito {
        param([int[]]$ContagensLog)
        $global:AgoraSimulado = [datetime]'2026-01-01T00:00:00'
        $global:ContagensLog = $ContagensLog
        $global:LeiturasLog = 0
        Mock Get-Date { $global:AgoraSimulado }
        Mock Start-Sleep { $global:AgoraSimulado = $global:AgoraSimulado.AddSeconds(1) }
        Mock Initialize-Cenario { [pscustomobject]@{ Staff = 't' } }
        Mock New-OsSintetica { [pscustomobject]@{ Id = 'os-1'; Numero = 1 } }
        Mock Wait-Saga { }
        Mock Initialize-FilaVenenosa { }
        Mock Restore-Fila { }
        Mock Send-CorpoCru { }
        Mock Get-QueueTotal { param($Queue) if ($Queue -like '*dlq*') { 1 } else { 0 } }
        Mock Get-MensagensDlq { [pscustomobject]@{ Body = $script:PoisonJson } }
        Mock Get-SagaIds { [pscustomobject]@{ Aguardado = 'cmd-1' } }
        Mock Get-Saga { [pscustomobject]@{ estado = 'AGUARDANDO_DIAGNOSTICO'; passos = @(1, 2) } }
        # A primeira leitura é a base (0); depois a contagem sobe uma linha por leitura, como o log do pod.
        Mock Get-LinhasLogServico {
            $i = $global:LeiturasLog++
            $global:ContagensLog[[math]::Min($i, $global:ContagensLog.Count - 1)]
        }
    }

    It 'passa quando a contagem de linhas de log chega a 5 depois de a DLQ já ter a mensagem' {
        Initialize-DublesOito @(0, 3, 4, 5)
        $r = & $Handlers['BDD-08d'] $cenario $null
        $r.Status | Should Be 'PASS'
    }

    It 'falha quando a contagem nunca chega a 5' {
        Initialize-DublesOito @(0, 4)
        $r = & $Handlers['BDD-08d'] $cenario $null
        $r.Status | Should Be 'FAIL'
        $r.Detalhe | Should Match 'observado 4'
    }
}
