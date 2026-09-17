package com.oficina.entity;

import com.oficina.domain.identidade.Ator;
import com.oficina.domain.identidade.TipoAtor;
import com.oficina.support.Fixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class HistoriaCanonicaTest {
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

    @Test void customerActorIsNotAStaffForeignKey() {
        OrdemServico os = Fixtures.ordem(Fixtures.cliente(Fixtures.CLIENTE_A),
                StatusOrdemServico.AGUARDANDO_APROVACAO);
        os.aprovarExecucaoCliente(Ator.cliente(Fixtures.CLIENTE_A), NOW, null);
        OsHistorico h = os.getHistorico().get(os.getHistorico().size() - 1);
        assertThat(h.getAtorClienteId()).isEqualTo(Fixtures.CLIENTE_A);
        assertThat(h.getAlteradoPor()).isNull();
        assertThat(h.getAtorTipo()).isEqualTo(TipoAtor.CUSTOMER);
        assertThat(h.getOcorridoEm()).isEqualTo(NOW);
    }

    @Test void sequenceAndInstantAreOwnedByEachTransition() {
        OrdemServico os = Fixtures.ordem(Fixtures.cliente(Fixtures.CLIENTE_A), StatusOrdemServico.RECEBIDA);
        UUID staff = UUID.randomUUID();
        os.iniciarDiagnostico(Ator.staff(staff), NOW, "diagnostico");
        os.enviarOrcamentoParaAprovacao(Ator.sistema(), NOW, null);
        os.recusarOrcamentoCliente(Ator.cliente(Fixtures.CLIENTE_A), NOW, null);
        os.enviarOrcamentoParaAprovacao(Ator.staff(staff), NOW, null);
        os.aprovarExecucaoCliente(Ator.cliente(Fixtures.CLIENTE_A), NOW, null);
        os.finalizarServico(Ator.staff(staff), NOW, null);
        os.registrarEntrega(Ator.staff(staff), NOW, null);
        assertThat(os.getHistorico()).extracting(OsHistorico::getSequencia)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(os.getSequenciaHistorico()).isEqualTo(8);
        assertThat(os.getHistorico()).allSatisfy(h -> assertThat(h.getOcorridoEm()).isEqualTo(NOW));
        assertThat(os.getHistorico().get(1).getAlteradoPor()).isEqualTo(staff);
        assertThat(os.getHistorico().get(1).getAtorClienteId()).isNull();
        assertThat(os.getHistorico().get(2).getAlteradoPor()).isNull();
        assertThat(os.isHistoricoCompletoDesdeInicio()).isTrue();
        assertThat(os.getCriadoEmUtc()).isEqualTo(NOW);
        assertThat(os.getAprovadoEm()).isEqualTo(os.getIniciadoEm());
    }

    @Test void compatibilityUsesExplicitZoneAndSameInstant() {
        OrdemServico os = Fixtures.ordem(Fixtures.cliente(Fixtures.CLIENTE_A), StatusOrdemServico.AGUARDANDO_APROVACAO);
        os.setZonaCompatibilidade(ZoneId.of("America/Sao_Paulo"));
        os.aprovarExecucaoCliente(Ator.cliente(Fixtures.CLIENTE_A), NOW, null);
        LocalDateTime expected = LocalDateTime.of(2026, 9, 15, 9, 0);
        assertThat(os.getAprovadoEm()).isEqualTo(expected);
        assertThat(os.getIniciadoEm()).isEqualTo(expected);
        assertThat(os.getHistorico().get(3).getCriadoEm()).isEqualTo(expected);
        assertThat(os.getHistorico().get(3).getOcorridoEm()).isEqualTo(NOW);
    }

    @Test void invalidActorOrTimeDoesNotMutateAggregate() {
        OrdemServico os = Fixtures.ordem(Fixtures.cliente(Fixtures.CLIENTE_A), StatusOrdemServico.RECEBIDA);
        assertThatThrownBy(() -> os.iniciarDiagnostico(null, NOW, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> os.iniciarDiagnostico(Ator.sistema(), null, null)).isInstanceOf(NullPointerException.class);
        assertThat(os.getStatus()).isEqualTo(StatusOrdemServico.RECEBIDA);
        assertThat(os.getSequenciaHistorico()).isEqualTo(1);
        assertThat(os.getHistorico()).hasSize(1);
    }

    @Test void initialHistoryCannotBeRegisteredTwice() {
        OrdemServico os = Fixtures.ordem(Fixtures.cliente(Fixtures.CLIENTE_A), StatusOrdemServico.RECEBIDA);
        assertThatThrownBy(() -> os.registrarHistoricoInicial(Ator.sistema(), NOW, null))
                .isInstanceOf(com.oficina.exception.BusinessRuleException.class);
        assertThat(os.getHistorico()).hasSize(1);
    }

    @Test void liveActorsRejectLegacyAndInvalidIdentityCombinations() {
        assertThatThrownBy(() -> new Ator(TipoAtor.LEGACY_UNKNOWN, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ator(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Ator.staff(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ator.cliente(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ator(TipoAtor.SYSTEM, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThat(Ator.sistema().id()).isNull();
    }

    @Test void persistenceCannotCreateLiveLegacyUnknownHistory() {
        OsHistorico history = OsHistorico.builder().atorTipo(TipoAtor.LEGACY_UNKNOWN)
                .sequencia(1L).ocorridoEm(NOW).criadoEm(LocalDateTime.of(2026, 9, 15, 12, 0)).build();
        assertThatThrownBy(history::onCreate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void missingCompatibilityZoneDoesNotMutateAggregate() {
        OrdemServico os = Fixtures.ordem(Fixtures.cliente(Fixtures.CLIENTE_A), StatusOrdemServico.RECEBIDA);
        os.setZonaCompatibilidade(null);
        assertThatThrownBy(() -> os.iniciarDiagnostico(Ator.sistema(), NOW, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Zona");
        assertThat(os.getSequenciaHistorico()).isEqualTo(1);
        assertThat(os.getStatus()).isEqualTo(StatusOrdemServico.RECEBIDA);
    }
}
