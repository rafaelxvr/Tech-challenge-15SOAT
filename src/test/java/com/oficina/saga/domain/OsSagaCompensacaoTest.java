package com.oficina.saga.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static com.oficina.saga.domain.EstadoSaga.*;
import static com.oficina.saga.domain.OsSagaTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OsSagaCompensacaoTest {
    final UUID osId = UUID.randomUUID();

    OsSaga aguardandoDecisao() {
        var s = OsSaga.iniciar(osId, em(0));
        s.diagnosticoConcluido(em(1));
        s.orcamentoGerado(UUID.randomUUID(), new BigDecimal("150.00"), em(2));
        s.retirarComandos(); s.retirarAcoes();
        return s;
    }

    OsSaga aguardandoPagamento() {
        var s = aguardandoDecisao();
        s.registrarDecisao(true, em(3));
        s.orcamentoAprovado("https://pagamento.example/checkout/1", em(4));
        s.retirarComandos(); s.retirarAcoes();
        return s;
    }

    OsSaga aguardandoReparo() {
        var s = aguardandoPagamento();
        s.pagamentoConfirmado(em(5));
        s.pecasReservadas(em(5));
        s.retirarComandos(); s.retirarAcoes();
        return s;
    }

    @Test void pagamentoRecusadoCancelaOrcamentoEDepoisAOs() {
        var s = aguardandoPagamento();
        s.falhar(MotivoFalha.PAGAMENTO_RECUSADO, em(10));
        assertThat(s.getEstado()).isEqualTo(COMPENSANDO);
        assertThat(s.getMotivoFalha()).isEqualTo(MotivoFalha.PAGAMENTO_RECUSADO);
        var cancelar = s.retirarComandos();
        assertThat(tipos(cancelar)).containsExactly(Comando.CANCELAR_ORCAMENTO);
        assertThat(s.aceita(cancelar.get(0).messageId())).isTrue();
        assertThat(s.retirarAcoes()).isEmpty();
        s.compensacaoConcluida(em(11));
        assertThat(s.getEstado()).isEqualTo(CANCELADA);
        assertThat(s.retirarAcoes()).containsExactly(AcaoLocal.CANCELAR_OS);
        assertThat(s.retirarComandos()).isEmpty();
        assertThat(s.getPrazo()).isNull();
        assertThat(s.compensacoesPendentes()).isEmpty();
    }

    @Test void estoqueInsuficienteEstornaAntesDeCancelarOrcamento() {
        var s = aguardandoPagamento();
        s.pagamentoConfirmado(em(10));
        s.falhar(MotivoFalha.ESTOQUE_INSUFICIENTE, em(10));
        assertThat(tipos(s.retirarComandos())).containsExactly(Comando.ESTORNAR_PAGAMENTO);
        s.compensacaoConcluida(em(11));
        assertThat(tipos(s.retirarComandos())).containsExactly(Comando.CANCELAR_ORCAMENTO);
        s.compensacaoConcluida(em(12));
        assertThat(s.getEstado()).isEqualTo(CANCELADA);
        assertThat(s.retirarAcoes()).containsExactly(AcaoLocal.CANCELAR_OS);
    }

    @Test void reparoRecusadoCancelaExecucaoRepoeEstoqueEstornaECancela() {
        var s = aguardandoReparo();
        s.falhar(MotivoFalha.REPARO_RECUSADO, em(10));
        assertThat(tipos(s.retirarComandos())).containsExactly(Comando.CANCELAR_EXECUCAO);
        assertThat(s.retirarAcoes()).isEmpty();
        s.compensacaoConcluida(em(11));
        assertThat(s.retirarAcoes()).containsExactly(AcaoLocal.REPOR_ESTOQUE);
        assertThat(tipos(s.retirarComandos())).containsExactly(Comando.ESTORNAR_PAGAMENTO);
        s.compensacaoConcluida(em(12));
        assertThat(tipos(s.retirarComandos())).containsExactly(Comando.CANCELAR_ORCAMENTO);
        s.compensacaoConcluida(em(13));
        assertThat(s.getEstado()).isEqualTo(CANCELADA);
        assertThat(s.retirarAcoes()).containsExactly(AcaoLocal.CANCELAR_OS);
    }

    @Test void diagnosticoRecusadoCancelaExecucaoECancelaOs() {
        var s = OsSaga.iniciar(osId, em(0));
        s.retirarComandos(); s.retirarAcoes();
        s.falhar(MotivoFalha.DIAGNOSTICO_RECUSADO, em(1));
        assertThat(tipos(s.retirarComandos())).containsExactly(Comando.CANCELAR_EXECUCAO);
        s.compensacaoConcluida(em(2));
        assertThat(s.getEstado()).isEqualTo(CANCELADA);
        assertThat(s.retirarAcoes()).containsExactly(AcaoLocal.CANCELAR_OS);
    }

    @Test void depoisDoPivoNaoHaFalha() {
        var s = aguardandoReparo();
        s.reparoIniciado(em(10));
        assertThatThrownBy(() -> s.falhar(MotivoFalha.REPARO_RECUSADO, em(11))).isInstanceOf(IllegalStateException.class);
    }

    @Test void falhaDuranteCompensacaoERejeitada() {
        var s = aguardandoPagamento();
        s.falhar(MotivoFalha.PAGAMENTO_RECUSADO, em(10));
        assertThatThrownBy(() -> s.falhar(MotivoFalha.PAGAMENTO_EXPIRADO, em(11))).isInstanceOf(IllegalStateException.class);
    }

    @Test void prazoDePagamentoVencidoCompensa() {
        var s = aguardandoPagamento();
        s.prazoExpirado(em(33));
        assertThat(s.getEstado()).isEqualTo(AGUARDANDO_PAGAMENTO);
        s.prazoExpirado(em(34));
        assertThat(s.getEstado()).isEqualTo(COMPENSANDO);
        assertThat(s.getMotivoFalha()).isEqualTo(MotivoFalha.PAGAMENTO_EXPIRADO);
        assertThat(tipos(s.retirarComandos())).containsExactly(Comando.CANCELAR_ORCAMENTO);
    }

    @Test void decisaoNaoTomadaNoPrazoExpira() {
        var s = aguardandoDecisao();
        s.prazoExpirado(new MomentoSaga(em(2).agora().plus(Duration.ofDays(7)), PRAZOS));
        assertThat(s.getMotivoFalha()).isEqualTo(MotivoFalha.DECISAO_EXPIRADA);
        assertThat(tipos(s.retirarComandos())).containsExactly(Comando.CANCELAR_ORCAMENTO);
    }

    @Test void orcamentoSemRespostaFalhaSemCompensacaoRemota() {
        var s = OsSaga.iniciar(osId, em(0));
        s.diagnosticoConcluido(em(1));
        s.retirarComandos(); s.retirarAcoes();
        s.prazoExpirado(em(6));
        assertThat(s.getMotivoFalha()).isEqualTo(MotivoFalha.ORCAMENTO_SEM_RESPOSTA);
        assertThat(s.getEstado()).isEqualTo(CANCELADA);
        assertThat(s.retirarAcoes()).containsExactly(AcaoLocal.CANCELAR_OS);
    }

    @Test void compensacaoSemRespostaReenviaAteLimiteEDepoisFalha() {
        var s = aguardandoPagamento();
        s.falhar(MotivoFalha.PAGAMENTO_RECUSADO, em(10));
        UUID anterior = s.retirarComandos().get(0).messageId();
        for (int i = 1; i <= 4; i++) {
            s.prazoExpirado(em(10 + 5L * i));
            var reenvio = s.retirarComandos();
            assertThat(tipos(reenvio)).containsExactly(Comando.CANCELAR_ORCAMENTO);
            assertThat(reenvio.get(0).messageId()).isNotEqualTo(anterior);
            assertThat(s.aceita(anterior)).isFalse();
            anterior = reenvio.get(0).messageId();
            assertThat(s.getEstado()).isEqualTo(COMPENSANDO);
        }
        s.prazoExpirado(em(35));
        assertThat(s.getEstado()).isEqualTo(FALHA_COMPENSACAO);
        assertThat(s.retirarComandos()).isEmpty();
        assertThat(s.getPrazo()).isNull();
    }

    @Test void compensacaoFalhouVaiParaFalhaCompensacao() {
        var s = aguardandoPagamento();
        s.falhar(MotivoFalha.PAGAMENTO_RECUSADO, em(10));
        s.compensacaoFalhou(em(11));
        assertThat(s.getEstado()).isEqualTo(FALHA_COMPENSACAO);
        assertThat(s.getEstado().terminal()).isTrue();
    }

    @Test void semPrazoNadaAcontece() {
        var s = aguardandoReparo();
        s.reparoIniciado(em(10));
        s.prazoExpirado(em(100_000));
        assertThat(s.getEstado()).isEqualTo(EM_EXECUCAO);
    }

    @Test void recusaSemDecisaoDeRecusaERejeitada() {
        var s = aguardandoDecisao();
        s.registrarDecisao(true, em(3));
        assertThatThrownBy(() -> s.orcamentoRecusado(em(4))).isInstanceOf(IllegalStateException.class)
                .hasMessage("Recusa recebida sem decisão de recusa");
        assertThat(s.getEstado()).isEqualTo(AGUARDANDO_DECISAO);
    }

    @Test void checkoutUrlSemHttpsERejeitado() {
        var s = aguardandoDecisao();
        s.registrarDecisao(true, em(3));
        assertThatThrownBy(() -> s.orcamentoAprovado("http://x.example", em(4)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("checkoutUrl deve ser https");
        assertThatThrownBy(() -> s.orcamentoAprovado(null, em(4)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(s.getEstado()).isEqualTo(AGUARDANDO_DECISAO);
    }
}
