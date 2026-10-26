package com.oficina.saga.application;

import com.oficina.config.CorrelationFilter;
import com.oficina.saga.application.mensagem.Envelope;
import com.oficina.saga.application.mensagem.MapeadorMensagens;
import com.oficina.saga.application.mensagem.MensagemInvalidaException;
import com.oficina.saga.application.port.InboxPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** One reply = one transaction: inbox row, saga change, OS change and next commands commit together. */
@Component
@RequiredArgsConstructor
public class ProcessadorRespostas {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessadorRespostas.class);
    private final MapeadorMensagens mapeador;
    private final InboxPort inbox;
    private final CicloOsSagaService ciclo;

    @Transactional
    public void processar(String corpo) {
        Envelope envelope = mapeador.ler(corpo);
        if (envelope.kind() != Envelope.Kind.REPLY) throw new MensagemInvalidaException("Somente respostas são aceitas");
        String priorCorrelation = MDC.get(CorrelationFilter.CORRELATION_ID);
        String priorTrace = MDC.get("traceparent");
        try {
            // Reply fields are untrusted: only a UUID correlation id and a W3C traceparent reach MDC and commands.
            MDC.put(CorrelationFilter.CORRELATION_ID, CorrelationFilter.correlationIdOrNew(envelope.correlationId()));
            restaurar("traceparent", CorrelationFilter.validTraceparent(envelope.traceparent()));
            MDC.put("saga_id", envelope.sagaId().toString());
            MDC.put("os_id", envelope.osId().toString());
            if (!inbox.registrar(envelope.messageId(), envelope.type())) {
                MDC.put("event_name", "saga_resposta_duplicada");
                LOG.info("");
                return;
            }
            ciclo.aplicarResposta(envelope);
        } finally {
            MDC.remove("event_name");
            MDC.remove("saga_id");
            MDC.remove("os_id");
            restaurar(CorrelationFilter.CORRELATION_ID, priorCorrelation);
            restaurar("traceparent", priorTrace);
        }
    }

    private static void restaurar(String chave, String valor) {
        if (valor == null) MDC.remove(chave); else MDC.put(chave, valor);
    }
}
