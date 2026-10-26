package com.oficina.saga.application;

import com.oficina.saga.application.mensagem.ContextoRastreio;
import com.oficina.saga.application.mensagem.FabricaEnvelopes;
import com.oficina.saga.application.port.OsDaSagaPort;
import com.oficina.saga.application.port.SaidaMensagensPort;
import com.oficina.saga.application.port.SnapshotOs;
import com.oficina.saga.domain.AcaoLocal;
import com.oficina.saga.domain.ComandoSaga;
import com.oficina.saga.domain.OsSaga;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Applies the saga's local actions and writes its commands to the outbox, in the caller's transaction. */
@Component
@RequiredArgsConstructor
public class ExecutorEfeitos {
    private final OsDaSagaPort os;
    private final FabricaEnvelopes fabrica;
    private final SaidaMensagensPort saida;

    public void executar(OsSaga saga) {
        saga.retirarAcoes().forEach(acao -> executarLocal(saga, acao));
        List<ComandoSaga> comandos = saga.retirarComandos();
        if (comandos.isEmpty()) return;
        SnapshotOs snapshot = os.snapshot(saga.getOsId());
        ContextoRastreio rastreio = ContextoRastreio.atual();
        comandos.forEach(c -> saida.registrar(c.comando().destino(), fabrica.comando(saga, c, snapshot, rastreio)));
    }

    private void executarLocal(OsSaga saga, AcaoLocal acao) {
        UUID id = saga.getOsId();
        switch (acao) {
            case INICIAR_DIAGNOSTICO_OS -> os.iniciarDiagnostico(id);
            case ENVIAR_ORCAMENTO_OS -> os.enviarOrcamento(id, saga.getValorOrcado());
            case AGUARDAR_PAGAMENTO_OS -> os.aguardarPagamento(id);
            case VOLTAR_DIAGNOSTICO_OS -> os.voltarParaDiagnostico(id);
            case INICIAR_EXECUCAO_OS -> os.iniciarExecucao(id);
            case FINALIZAR_OS -> os.finalizar(id);
            case REPOR_ESTOQUE -> os.reporPecas(id);
            case CANCELAR_OS -> os.cancelar(id, saga.getMotivoFalha());
            default -> throw new IllegalStateException("Ação local não suportada: " + acao);
        }
    }
}
