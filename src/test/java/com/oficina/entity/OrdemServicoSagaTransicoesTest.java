package com.oficina.entity;

import com.oficina.domain.identidade.Ator;
import com.oficina.exception.BusinessRuleException;
import com.oficina.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.oficina.entity.StatusOrdemServico.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrdemServicoSagaTransicoesTest {
    static final Instant T = Instant.parse("2026-09-15T12:00:00Z");

    OrdemServico ordem(StatusOrdemServico status) {
        return Fixtures.ordem(Fixtures.cliente(UUID.randomUUID()), status);
    }

    @Test void aprovacaoPagaPassaPorAguardandoPagamentoAteExecucao() {
        var os = ordem(AGUARDANDO_APROVACAO);
        os.aguardarPagamento(Ator.sistema(), T, "Orçamento aprovado pelo cliente");
        assertThat(os.getStatus()).isEqualTo(AGUARDANDO_PAGAMENTO);
        assertThat(os.getAprovadoEm()).isNotNull();
        os.iniciarExecucaoPaga(Ator.sistema(), T, "Reparo iniciado");
        assertThat(os.getStatus()).isEqualTo(EM_EXECUCAO);
        assertThat(os.getIniciadoEm()).isNotNull();
        assertThat(os.getHistorico()).extracting(OsHistorico::getStatusNovo)
                .endsWith(AGUARDANDO_PAGAMENTO, EM_EXECUCAO);
    }

    @Test void execucaoPagaExigeAguardandoPagamento() {
        var os = ordem(AGUARDANDO_APROVACAO);
        assertThatThrownBy(() -> os.iniciarExecucaoPaga(Ator.sistema(), T, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @ParameterizedTest
    @EnumSource(value = StatusOrdemServico.class, names = {"RECEBIDA", "EM_DIAGNOSTICO", "AGUARDANDO_APROVACAO"})
    void cancelamentoAceitoAntesDaExecucao(StatusOrdemServico status) {
        var os = ordem(status);
        os.cancelar(Ator.sistema(), T, "Cancelada pela saga: PAGAMENTO_RECUSADO");
        assertThat(os.getStatus()).isEqualTo(CANCELADA);
    }

    @Test void cancelamentoAceitoAguardandoPagamento() {
        var os = ordem(AGUARDANDO_APROVACAO);
        os.aguardarPagamento(Ator.sistema(), T, null);
        os.cancelar(Ator.sistema(), T, "Cancelada pela saga: ESTOQUE_INSUFICIENTE");
        assertThat(os.getStatus()).isEqualTo(CANCELADA);
    }

    @ParameterizedTest
    @EnumSource(value = StatusOrdemServico.class, names = {"EM_EXECUCAO", "FINALIZADA", "ENTREGUE"})
    void cancelamentoRecusadoDepoisDoInicioDaExecucao(StatusOrdemServico status) {
        var os = ordem(status);
        assertThatThrownBy(() -> os.cancelar(Ator.sistema(), T, "x")).isInstanceOf(BusinessRuleException.class);
    }

    @Test void valorOrcadoNaoPodeSerNuloNemNegativo() {
        var os = ordem(EM_DIAGNOSTICO);
        assertThatThrownBy(() -> os.definirValorOrcado(null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> os.definirValorOrcado(new BigDecimal("-0.01"))).isInstanceOf(BusinessRuleException.class);
        os.definirValorOrcado(new BigDecimal("150.00"));
        assertThat(os.getValorTotal()).isEqualByComparingTo("150.00");
    }
}
