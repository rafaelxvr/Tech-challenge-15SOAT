package com.oficina.saga.application.mensagem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.oficina.saga.application.port.SnapshotOs;
import com.oficina.saga.domain.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContratoComandosTest {
    static final Instant T0 = Instant.parse("2026-09-15T12:00:00Z");
    static final PrazosSaga PRAZOS = new PrazosSaga(Duration.ofDays(3), Duration.ofMinutes(5),
            Duration.ofDays(7), Duration.ofMinutes(30), Duration.ofMinutes(5), 5);
    static final UUID OS_ID = UUID.randomUUID();
    static final SnapshotOs SNAPSHOT = new SnapshotOs(OS_ID, 42L, UUID.randomUUID(), List.of(
            new SnapshotOs.ItemOs("SERVICO", UUID.randomUUID(), "Troca de óleo", 1, new BigDecimal("80.00")),
            new SnapshotOs.ItemOs("PECA", UUID.randomUUID(), "Filtro", 2, new BigDecimal("35.50"))));

    final FabricaEnvelopes fabrica = new FabricaEnvelopes(Clock.fixed(T0, ZoneOffset.UTC), "MATRIZ");
    final MapeadorMensagens mapeador = new MapeadorMensagens();
    final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    final ObjectMapper json = new ObjectMapper();

    static OsSaga sagaComDecisaoEFalha() {
        var m = new MomentoSaga(T0, PRAZOS);
        var s = OsSaga.iniciar(OS_ID, m);
        s.diagnosticoConcluido(m);
        s.orcamentoGerado(UUID.randomUUID(), new BigDecimal("151.00"), m);
        s.registrarDecisao(true, m);
        s.orcamentoAprovado("https://pagamento.example/c/1", m);
        s.falhar(MotivoFalha.PAGAMENTO_RECUSADO, m);
        return s;
    }

    @ParameterizedTest
    @EnumSource(Comando.class)
    void comandoEmitidoRespeitaEnvelopeEPayload(Comando comando) throws Exception {
        var envelope = fabrica.comando(sagaComDecisaoEFalha(),
                new ComandoSaga(UUID.randomUUID(), comando, EtapaSaga.PAGAMENTO), SNAPSHOT,
                new ContextoRastreio(UUID.randomUUID().toString(), null));
        JsonNode node = json.readTree(mapeador.escrever(envelope));
        assertThat(validar("contracts/envelope/v1.json", node)).isEmpty();
        assertThat(validar("contracts/" + comando.tipo() + "/v1.json", node.get("payload"))).isEmpty();
        assertThat(node.get("type").asText()).isEqualTo(comando.tipo());
        assertThat(node.get("kind").asText()).isEqualTo("COMMAND");
        assertThat(node.get("passo").asInt()).isEqualTo(4);
    }

    Set<ValidationMessage> validar(String recurso, JsonNode node) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(in).as(recurso).isNotNull();
            return schemas.getSchema(in).validate(node);
        }
    }
}
