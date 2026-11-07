package com.oficina.saga.application.mensagem;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oficina.saga.application.port.SnapshotOs;
import com.oficina.saga.domain.ComandoSaga;
import com.oficina.saga.domain.OsSaga;

import java.time.Clock;

public class FabricaEnvelopes {
    private final Clock clock;
    private final String filialPadrao;

    public FabricaEnvelopes(Clock clock, String filialPadrao) {
        this.clock = clock;
        this.filialPadrao = filialPadrao;
    }

    public Envelope comando(OsSaga saga, ComandoSaga comando, SnapshotOs os, ContextoRastreio rastreio) {
        return new Envelope(comando.messageId(), Envelope.Kind.COMMAND, comando.comando().tipo(), 1, saga.getId(),
                saga.getOsId(), comando.etapa().numero(), null, clock.instant(), rastreio.correlationId(),
                rastreio.traceparent(), payload(saga, comando, os));
    }

    private ObjectNode payload(OsSaga saga, ComandoSaga comando, SnapshotOs os) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        switch (comando.comando()) {
            case INICIAR_DIAGNOSTICO -> p.put("numero", os.numero()).put("filialId", filialPadrao)
                    .put("versaoOrcamento", saga.getVersaoOrcamento());
            case INICIAR_REPARO -> {
                p.put("numero", os.numero()).put("filialId", filialPadrao);
                ArrayNode servicos = p.putArray("servicos");
                os.itens().stream().filter(i -> "SERVICO".equals(i.tipo()))
                        .forEach(i -> servicos.addObject().put("descricao", i.descricao()).put("quantidade", i.quantidade()));
            }
            case CANCELAR_EXECUCAO -> p.put("motivo", motivo(saga));
            case GERAR_ORCAMENTO -> {
                p.put("numero", os.numero()).put("clienteId", os.clienteId().toString())
                        .put("versaoOrcamento", saga.getVersaoOrcamento());
                ArrayNode itens = p.putArray("itens");
                os.itens().forEach(i -> itens.addObject().put("tipo", i.tipo())
                        .put("referenciaId", i.referenciaId().toString()).put("descricao", i.descricao())
                        .put("quantidade", i.quantidade()).put("precoUnitario", i.precoUnitario()));
            }
            case REGISTRAR_DECISAO -> p.put("versaoOrcamento", saga.getVersaoOrcamento()).put("decisao", decisao(saga));
            case CANCELAR_ORCAMENTO, ESTORNAR_PAGAMENTO -> p.put("versaoOrcamento", saga.getVersaoOrcamento())
                    .put("motivo", motivo(saga));
        }
        return p;
    }

    private static String motivo(OsSaga saga) {
        if (saga.getMotivoFalha() == null) throw new IllegalStateException("Compensação sem motivo de falha");
        return saga.getMotivoFalha().name();
    }

    private static String decisao(OsSaga saga) {
        if (saga.getDecisaoAprovada() == null) throw new IllegalStateException("Decisão não registrada");
        return saga.getDecisaoAprovada() ? "APROVADO" : "RECUSADO";
    }
}
