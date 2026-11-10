package com.oficina.saga.adapter.in.messaging;

import com.newrelic.api.agent.Trace;
import com.oficina.saga.application.ProcessadorRespostas;
import com.oficina.saga.application.rastreio.CarrierRastreio;
import com.oficina.saga.application.rastreio.PropagadorTraco;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Long-polls the reply queue; a failed message holds back later messages of its FIFO group. */
public class SqsRespostasListener implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(SqsRespostasListener.class);
    private final SqsClient sqs;
    private final String fila;
    private final ProcessadorRespostas processador;
    private final PropagadorTraco propagador;
    private ExecutorService executor;
    private volatile boolean rodando;

    public SqsRespostasListener(SqsClient sqs, String fila, ProcessadorRespostas processador,
                                PropagadorTraco propagador) {
        this.sqs = sqs;
        this.fila = fila;
        this.processador = processador;
        this.propagador = propagador;
    }

    public int receberUmLote() {
        var mensagens = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(fila).maxNumberOfMessages(10)
                .waitTimeSeconds(10).messageSystemAttributeNames(MessageSystemAttributeName.MESSAGE_GROUP_ID)
                .messageAttributeNames(CarrierRastreio.NOMES)
                .build()).messages();
        Set<String> gruposComFalha = new HashSet<>();
        int processadas = 0;
        for (Message mensagem : mensagens) {
            String grupo = Objects.requireNonNullElse(mensagem.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID), "");
            if (gruposComFalha.contains(grupo)) continue;
            try {
                processarNaTransacao(mensagem);
                sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(fila).receiptHandle(mensagem.receiptHandle()).build());
                processadas++;
            } catch (RuntimeException e) {
                gruposComFalha.add(grupo);
                MDC.put("event_name", "saga_resposta_falhou");
                MDC.put("error_code", e.getClass().getSimpleName());
                try { LOG.warn("saga resposta falhou"); } finally { MDC.remove("event_name"); MDC.remove("error_code"); }
            }
        }
        return processadas;
    }

    /** One New Relic transaction per message, joined to the producer's trace before any business logic runs. */
    @Trace(dispatcher = true)
    void processarNaTransacao(Message mensagem) {
        propagador.aceitar(carrier(mensagem));
        processador.processar(mensagem.body());
    }

    private static Map<String, String> carrier(Message mensagem) {
        Map<String, String> headers = new LinkedHashMap<>();
        mensagem.messageAttributes().forEach((nome, atributo) -> {
            if (atributo != null && atributo.stringValue() != null) headers.put(nome, atributo.stringValue());
        });
        return headers;
    }

    @Override public synchronized void start() {
        if (isRunning()) return;
        rodando = true;
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "saga-respostas");
            t.setDaemon(true);
            return t;
        });
        executor.submit(() -> {
            while (rodando) {
                try {
                    receberUmLote();
                } catch (RuntimeException e) {
                    MDC.put("event_name", "saga_recepcao_falhou");
                    MDC.put("error_code", e.getClass().getSimpleName());
                    try { LOG.warn("saga recepcao falhou"); } finally { MDC.remove("event_name"); MDC.remove("error_code"); }
                    dormir();
                }
            }
        });
    }

    private static void dormir() {
        try { Thread.sleep(1_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override public synchronized void stop() {
        rodando = false;
        if (executor != null) executor.shutdownNow();
    }

    @Override public synchronized boolean isRunning() { return executor != null && !executor.isShutdown(); }
}
