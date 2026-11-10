package com.oficina.saga.adapter.observability;

import com.newrelic.api.agent.HeaderType;
import com.newrelic.api.agent.Headers;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.TraceMetadata;
import com.newrelic.api.agent.Transaction;
import com.newrelic.api.agent.TransportType;
import com.oficina.saga.application.rastreio.CarrierRastreio;
import com.oficina.saga.application.rastreio.ContextoCarrierHttp;
import com.oficina.saga.application.rastreio.PropagadorTraco;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** New Relic implementation; without the agent attached the API is a no-op and the carrier comes back empty. */
@Component
public class NewRelicPropagadorTraco implements PropagadorTraco {
    private static final Logger log = LoggerFactory.getLogger(NewRelicPropagadorTraco.class);
    private final Supplier<Transaction> transacao;
    private final BiConsumer<String, String> atributo;
    private final Supplier<TraceMetadata> metadata;
    /** Every request is captured; only the first ones and a sample of the rest are logged at INFO, the rest at DEBUG. */
    private final AtomicLong capturas = new AtomicLong();

    public NewRelicPropagadorTraco() {
        this(() -> NewRelic.getAgent().getTransaction(), NewRelic::addCustomParameter,
                () -> NewRelic.getAgent().getTraceMetadata());
    }

    NewRelicPropagadorTraco(Supplier<Transaction> transacao, BiConsumer<String, String> atributo) {
        this(transacao, atributo, () -> null);
    }

    NewRelicPropagadorTraco(Supplier<Transaction> transacao, BiConsumer<String, String> atributo,
                            Supplier<TraceMetadata> metadata) {
        this.transacao = transacao;
        this.atributo = atributo;
        this.metadata = metadata;
    }

    @Override public Map<String, String> capturar() {
        try {
            Map<String, String> tardio = capturarTardio();
            if (!tardio.isEmpty()) return tardio;
            Map<String, String> contexto = ContextoCarrierHttp.atual();
            if (!contexto.isEmpty()) registrar("contexto_http", Outcome.ok);
            return contexto;
        } catch (RuntimeException | LinkageError e) {
            return Map.of();
        }
    }

    /** Asks the agent for headers now and logs only the structured outcome, never a header value. */
    private Map<String, String> capturarTardio() {
        Outcome outcome;
        Map<String, String> carrier = Map.of();
        try {
            Transaction t = transacao.get();
            if (t == null) {
                outcome = Outcome.sem_transacao;
            } else {
                MapaHeaders headers = new MapaHeaders();
                t.insertDistributedTraceHeaders(headers);
                if (headers.valores.isEmpty()) {
                    carrier = capturarPorMetadata();
                    outcome = carrier.isEmpty() ? Outcome.sem_headers : Outcome.ok_metadata;
                } else {
                    carrier = CarrierRastreio.sanitizar(headers.valores);
                    outcome = carrier.isEmpty() ? Outcome.descartado_sanitizacao : Outcome.ok;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            outcome = Outcome.excecao;
        }
        registrar("agente", outcome);
        return carrier;
    }

    /** Cold agent: no headers yet, but the active transaction already knows its ids; build the W3C carrier from them. */
    private Map<String, String> capturarPorMetadata() {
        return CarrierRastreio.sanitizar(TraceparentW3c.de(metadata.get()));
    }

    private void registrar(String origem, Outcome outcome) {
        long n = capturas.incrementAndGet();
        if (n <= 20 || n % 100 == 0) log.info("traco_captura origem={} outcome={}", origem, outcome);
        else log.debug("traco_captura origem={} outcome={}", origem, outcome);
    }

    /** Structured, value-free result of a capture attempt. */
    enum Outcome { ok, ok_metadata, sem_transacao, sem_headers, excecao, descartado_sanitizacao }

    @Override public void aceitar(Map<String, String> headers) {
        Map<String, String> limpo = CarrierRastreio.sanitizar(headers);
        if (limpo.isEmpty()) return;
        try {
            transacao.get().acceptDistributedTraceHeaders(TransportType.Queue, new MapaHeaders(limpo));
        } catch (RuntimeException e) {
            // Tracing must never fail message handling.
        }
    }

    @Override public void anotar(String chave, String valor) {
        try {
            atributo.accept(chave, valor);
        } catch (RuntimeException e) {
            // Tracing must never fail message handling.
        }
    }

    /** Case-insensitive message-header carrier handed to the agent. */
    private static final class MapaHeaders implements Headers {
        private final Map<String, String> valores = new LinkedHashMap<>();

        MapaHeaders() {}

        MapaHeaders(Map<String, String> iniciais) { iniciais.forEach(this::setHeader); }

        @Override public HeaderType getHeaderType() { return HeaderType.MESSAGE; }
        @Override public String getHeader(String nome) { return valores.get(nome.toLowerCase()); }
        @Override public Collection<String> getHeaders(String nome) {
            String valor = getHeader(nome);
            return valor == null ? List.of() : List.of(valor);
        }
        @Override public void setHeader(String nome, String valor) { valores.put(nome.toLowerCase(), valor); }
        @Override public void addHeader(String nome, String valor) { setHeader(nome, valor); }
        @Override public Collection<String> getHeaderNames() { return valores.keySet(); }
        @Override public boolean containsHeader(String nome) { return valores.containsKey(nome.toLowerCase()); }
    }
}
