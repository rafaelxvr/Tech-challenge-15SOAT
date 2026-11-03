package com.oficina.saga.adapter.in.messaging;

import com.oficina.saga.application.ProcessadorRespostas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.HashSet;
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
    private ExecutorService executor;
    private volatile boolean rodando;

    public SqsRespostasListener(SqsClient sqs, String fila, ProcessadorRespostas processador) {
        this.sqs = sqs;
        this.fila = fila;
        this.processador = processador;
    }

    public int receberUmLote() {
        var mensagens = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(fila).maxNumberOfMessages(10)
                .waitTimeSeconds(10).messageSystemAttributeNames(MessageSystemAttributeName.MESSAGE_GROUP_ID)
                .build()).messages();
        Set<String> gruposComFalha = new HashSet<>();
        int processadas = 0;
        for (Message mensagem : mensagens) {
            String grupo = Objects.requireNonNullElse(mensagem.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID), "");
            if (gruposComFalha.contains(grupo)) continue;
            try {
                processador.processar(mensagem.body());
                sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(fila).receiptHandle(mensagem.receiptHandle()).build());
                processadas++;
            } catch (RuntimeException e) {
                gruposComFalha.add(grupo);
                MDC.put("event_name", "saga_resposta_falhou");
                MDC.put("error_code", e.getClass().getSimpleName());
                try { LOG.warn(""); } finally { MDC.remove("event_name"); MDC.remove("error_code"); }
            }
        }
        return processadas;
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
                    try { LOG.warn(""); } finally { MDC.remove("event_name"); MDC.remove("error_code"); }
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
