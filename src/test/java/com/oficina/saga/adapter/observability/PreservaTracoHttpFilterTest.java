package com.oficina.saga.adapter.observability;

import com.oficina.saga.application.rastreio.ContextoCarrierHttp;
import com.oficina.saga.application.rastreio.PropagadorTraco;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PreservaTracoHttpFilterTest {
    static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @BeforeEach @AfterEach void limpar() { ContextoCarrierHttp.limpar(); }

    @SuppressWarnings("unchecked")
    PreservaTracoHttpFilter filtro(PropagadorTraco p) {
        ObjectProvider<PropagadorTraco> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(p);
        return new PreservaTracoHttpFilter(provider);
    }

    PropagadorTraco propagador(Map<String, String> carrier) {
        PropagadorTraco p = mock(PropagadorTraco.class);
        when(p.capturar()).thenReturn(carrier);
        return p;
    }

    @Test void preservaDuranteACadeiaELimpaDepois() throws Exception {
        var visto = new AtomicReference<Map<String, String>>();
        FilterChain chain = (rq, rs) -> visto.set(ContextoCarrierHttp.atual());
        filtro(propagador(Map.of("traceparent", TRACEPARENT))).doFilter(new MockHttpServletRequest("GET", "/api/os"),
                new MockHttpServletResponse(), chain);
        assertThat(visto.get()).containsEntry("traceparent", TRACEPARENT);
        assertThat(ContextoCarrierHttp.atual()).isEmpty();
    }

    @Test void limpaNoFinallyQuandoACadeiaLanca() {
        FilterChain chain = (rq, rs) -> { throw new ServletException("boom"); };
        assertThatThrownBy(() -> filtro(propagador(Map.of("traceparent", TRACEPARENT)))
                .doFilter(new MockHttpServletRequest("GET", "/api/os"), new MockHttpServletResponse(), chain))
                .isInstanceOf(ServletException.class);
        assertThat(ContextoCarrierHttp.atual()).isEmpty();
    }

    @Test void falhaDoPropagadorNaoDerrubaARequisicao() throws Exception {
        PropagadorTraco p = mock(PropagadorTraco.class);
        when(p.capturar()).thenThrow(new IllegalStateException("agent"));
        var executou = new boolean[1];
        filtro(p).doFilter(new MockHttpServletRequest("GET", "/api/os"), new MockHttpServletResponse(),
                (rq, rs) -> executou[0] = true);
        assertThat(executou[0]).isTrue();
        assertThat(ContextoCarrierHttp.atual()).isEmpty();
    }

    @Test void carrierInvalidoNaoEPreservado() throws Exception {
        var visto = new AtomicReference<Map<String, String>>();
        filtro(propagador(Map.of("traceparent", "00-xx", "x-segredo", "v"))).doFilter(
                new MockHttpServletRequest("GET", "/api/os"), new MockHttpServletResponse(),
                (rq, rs) -> visto.set(ContextoCarrierHttp.atual()));
        assertThat(visto.get()).isEmpty();
    }

    @Test void healthChecksNaoSaoFiltrados() throws Exception {
        PropagadorTraco p = propagador(Map.of("traceparent", TRACEPARENT));
        filtro(p).doFilter(new MockHttpServletRequest("GET", "/api/actuator/health"), new MockHttpServletResponse(),
                (rq, rs) -> {});
        verifyNoInteractions(p);
    }

    @Test void contextoEIsoladoEntreThreads() throws Exception {
        var filtro = filtro(propagador(Map.of("traceparent", TRACEPARENT)));
        var outraThread = new AtomicReference<Map<String, String>>();
        var dentro = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            FilterChain chain = (rq, rs) -> {
                dentro.countDown();
                try { liberar.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            };
            var req = ex.submit(() -> {
                filtro.doFilter(new MockHttpServletRequest("GET", "/api/os"), new MockHttpServletResponse(), chain);
                return null;
            });
            assertThat(dentro.await(5, TimeUnit.SECONDS)).isTrue();
            outraThread.set(ContextoCarrierHttp.atual()); // esta thread nunca viu o carrier da outra
            liberar.countDown();
            req.get(5, TimeUnit.SECONDS);
        } finally { ex.shutdownNow(); }
        assertThat(outraThread.get()).isEmpty();
    }

    @Test void threadDePoolReusadaNaoVazaCarrierDaRequisicaoAnterior() throws Exception {
        var filtro = filtro(propagador(Map.of("traceparent", TRACEPARENT)));
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            ex.submit(() -> {
                filtro.doFilter(new MockHttpServletRequest("GET", "/api/os"), new MockHttpServletResponse(), (rq, rs) -> {});
                return null;
            }).get(5, TimeUnit.SECONDS);
            List<Map<String, String>> proxima = new ArrayList<>();
            ex.submit(() -> proxima.add(ContextoCarrierHttp.atual())).get(5, TimeUnit.SECONDS);
            assertThat(proxima.get(0)).isEmpty();
        } finally { ex.shutdownNow(); }
    }
}
