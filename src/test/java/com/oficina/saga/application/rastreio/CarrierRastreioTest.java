package com.oficina.saga.application.rastreio;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CarrierRastreioTest {
    static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test void sanitizarMantemSoNomesConhecidosEValoresSeguros() {
        var limpo = CarrierRastreio.sanitizar(Map.of("traceparent", TRACEPARENT, "tracestate", "a=b", "email", "x@y.z",
                "newrelic", "x".repeat(1025)));
        assertThat(limpo).containsOnlyKeys("traceparent", "tracestate");
    }

    @Test void headerInvalidoNaoApagaOsValidos() {
        assertThat(CarrierRastreio.sanitizar(Map.of("traceparent", "00-xx", "tracestate", "a=b")))
                .containsOnlyKeys("tracestate");
        assertThat(CarrierRastreio.sanitizar(Map.of("traceparent", TRACEPARENT, "tracestate", "ruim\n", "newrelic", "abc=")))
                .containsOnlyKeys("traceparent", "newrelic");
        assertThat(CarrierRastreio.sanitizar(Map.of("traceparent", "00-xx"))).isEmpty();
    }

    @Test void envelopeNoLimiteDeTamanhoCarregaOCarrierEUmByteAMaisDescarta() {
        var carrier = Map.of("traceparent", TRACEPARENT);
        String corpo = "{\"a\":\"\"}";
        String comCarrier = CarrierRastreio.embutir(corpo, carrier, 65_536);
        int extra = comCarrier.getBytes(java.nio.charset.StandardCharsets.UTF_8).length - corpo.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int limite = 65_536;
        String justo = "{\"a\":\"" + "x".repeat(limite - extra - 8) + "\"}";
        assertThat(CarrierRastreio.embutir(justo, carrier, limite).getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(limite);
        String estoura = "{\"a\":\"" + "x".repeat(limite - extra - 7) + "\"}";
        assertThat(CarrierRastreio.embutir(estoura, carrier, limite)).isEqualTo(estoura);
    }

    @Test void embutirELerFazemIdaEVolta() {
        String guardado = CarrierRastreio.embutir("{\"a\":1}", Map.of("traceparent", TRACEPARENT), 65_536);
        assertThat(guardado).contains("\"_carrierRastreio\"").contains("\"a\":1");
        assertThat(CarrierRastreio.ler("{\"traceparent\":\"" + TRACEPARENT + "\",\"x\":1}")).containsOnlyKeys("traceparent");
    }

    @Test void semEspacoOuSemCarrierMantemOCorpoIntacto() {
        assertThat(CarrierRastreio.embutir("{\"a\":1}", Map.of(), 65_536)).isEqualTo("{\"a\":1}");
        assertThat(CarrierRastreio.embutir("{\"a\":1}", Map.of("traceparent", TRACEPARENT), 10)).isEqualTo("{\"a\":1}");
    }

    @Test void lerToleraAusenciaELixo() {
        assertThat(CarrierRastreio.ler(null)).isEmpty();
        assertThat(CarrierRastreio.ler("não é json")).isEmpty();
        assertThat(CarrierRastreio.ler("[1]")).isEmpty();
    }
}
