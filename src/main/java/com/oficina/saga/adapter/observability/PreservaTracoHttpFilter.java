package com.oficina.saga.adapter.observability;

import com.oficina.saga.application.rastreio.ContextoCarrierHttp;
import com.oficina.saga.application.rastreio.PropagadorTraco;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Captures the trace carrier while the web transaction is still fresh and keeps it for the request, so the outbox
 * write can fall back to it. Tracing is best effort: a capture failure never fails the request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class PreservaTracoHttpFilter extends OncePerRequestFilter {
    private final ObjectProvider<PropagadorTraco> propagador;

    public PreservaTracoHttpFilter(ObjectProvider<PropagadorTraco> propagador) { this.propagador = propagador; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        preservar();
        try { chain.doFilter(request, response); }
        finally { ContextoCarrierHttp.limpar(); }
    }

    private void preservar() {
        try {
            PropagadorTraco p = propagador.getIfAvailable();
            Map<String, String> carrier = p == null ? Map.of() : p.capturar();
            ContextoCarrierHttp.preservar(carrier);
        } catch (RuntimeException | LinkageError e) {
            ContextoCarrierHttp.limpar();
        }
    }
}
