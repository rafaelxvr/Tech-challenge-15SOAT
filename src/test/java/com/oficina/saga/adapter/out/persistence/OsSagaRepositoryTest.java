package com.oficina.saga.adapter.out.persistence;

import com.oficina.entity.StatusOrdemServico;
import com.oficina.repository.*;
import com.oficina.saga.domain.*;
import com.oficina.support.PostgresIntegrationSupport;
import com.oficina.support.SagaFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OsSagaRepositoryTest extends PostgresIntegrationSupport {
    static final Instant T0 = Instant.parse("2026-09-15T12:00:00Z");
    static final PrazosSaga PRAZOS = new PrazosSaga(Duration.ofDays(3), Duration.ofMinutes(5),
            Duration.ofDays(7), Duration.ofMinutes(30), Duration.ofMinutes(5), 5);
    @Autowired OsSagaRepository sagas;
    @Autowired ClienteRepository clientes;
    @Autowired VeiculoRepository veiculos;
    @Autowired OrdemServicoRepository ordens;
    @Autowired PecaRepository pecas;
    @Autowired ServicoRepository servicos;

    @BeforeEach void limpar() {
        jdbc.update("DELETE FROM os_saga_passo");
        jdbc.update("DELETE FROM os_saga");
    }

    UUID novaOrdem() {
        var cliente = SagaFixtures.cliente(clientes);
        return SagaFixtures.ordem(transaction, ordens, veiculos, pecas, servicos, cliente,
                SagaFixtures.veiculo(jdbc, cliente.getId()), StatusOrdemServico.RECEBIDA, Map.of(), Map.of());
    }

    @Test void persisteEstadoPilhaEPassosEmOrdem() {
        UUID osId = novaOrdem();
        transaction.executeWithoutResult(st -> sagas.save(OsSaga.iniciar(osId, new MomentoSaga(T0, PRAZOS))));
        transaction.executeWithoutResult(st -> {
            OsSaga lida = sagas.findByOsId(osId).orElseThrow();
            assertThat(lida.getEstado()).isEqualTo(EstadoSaga.AGUARDANDO_DIAGNOSTICO);
            assertThat(lida.compensacoesPendentes()).containsExactly(Compensacao.CANCELAR_EXECUCAO);
            assertThat(lida.getPassos()).extracting(OsSagaPasso::getEvento)
                    .containsExactly("ESTADO:AGUARDANDO_DIAGNOSTICO", "COMANDO:IniciarDiagnostico");
            assertThat(lida.aceita(lida.getComandoAguardadoId())).isTrue();
            assertThat(lida.getComandoAguardado()).isEqualTo(Comando.INICIAR_DIAGNOSTICO);
            assertThat(lida.getVersao()).isZero();
        });
        assertThat(sagas.existsByOsId(osId)).isTrue();
    }

    @Test void umaSagaPorOrdem() {
        UUID osId = novaOrdem();
        transaction.executeWithoutResult(st -> sagas.save(OsSaga.iniciar(osId, new MomentoSaga(T0, PRAZOS))));
        assertThatThrownBy(() -> transaction.executeWithoutResult(st ->
                sagas.saveAndFlush(OsSaga.iniciar(osId, new MomentoSaga(T0, PRAZOS)))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void colunasDeEnumAceitamSomenteOsNomesDoDominio() {
        UUID osId = novaOrdem();
        transaction.executeWithoutResult(st -> sagas.save(OsSaga.iniciar(osId, new MomentoSaga(T0, PRAZOS))));
        String saga = "UPDATE os_saga SET %s = ? WHERE os_id = ?";
        String passo = "UPDATE os_saga_passo SET %s = ? WHERE saga_id = (SELECT id FROM os_saga WHERE os_id = ?)";
        assertSomente(saga.formatted("comando_aguardado"), osId, Comando.values());
        assertSomente(saga.formatted("motivo_falha"), osId, MotivoFalha.values());
        assertSomente(passo.formatted("etapa"), osId, EtapaSaga.values());
        assertSomente(passo.formatted("estado_saga"), osId, EstadoSaga.values());
        jdbc.update(saga.formatted("comando_aguardado"), null, osId);
        jdbc.update(saga.formatted("motivo_falha"), null, osId);
    }

    private void assertSomente(String update, UUID osId, Enum<?>[] valores) {
        for (Enum<?> valor : valores) assertThat(jdbc.update(update, valor.name(), osId)).isPositive();
        assertThatThrownBy(() -> jdbc.update(update, "INVALIDO", osId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void vencidasSaoSelecionadasPeloPrazo() {
        UUID osId = novaOrdem();
        transaction.executeWithoutResult(st -> sagas.save(OsSaga.iniciar(osId, new MomentoSaga(T0, PRAZOS))));
        transaction.executeWithoutResult(st -> {
            assertThat(sagas.findVencidasParaProcessar(T0.plus(Duration.ofDays(1)), PageRequest.of(0, 10))).isEmpty();
            assertThat(sagas.findVencidasParaProcessar(T0.plus(Duration.ofDays(3)), PageRequest.of(0, 10)))
                    .extracting(OsSaga::getOsId).containsExactly(osId);
        });
    }
}
