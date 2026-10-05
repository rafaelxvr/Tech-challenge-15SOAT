package com.oficina.saga.domain;

import com.oficina.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.oficina.saga.domain.Compensacao.*;
import static com.oficina.saga.domain.EstadoSaga.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OsSagaTest {
    static final Instant T0 = Instant.parse("2026-09-15T12:00:00Z");
    static final PrazosSaga PRAZOS = new PrazosSaga(Duration.ofDays(3), Duration.ofMinutes(5),
            Duration.ofDays(7), Duration.ofMinutes(30), Duration.ofMinutes(5), 5);
    final UUID osId = UUID.randomUUID();

    static MomentoSaga em(long minutos) { return new MomentoSaga(T0.plus(Duration.ofMinutes(minutos)), PRAZOS); }
    static List<Comando> tipos(List<ComandoSaga> comandos) { return comandos.stream().map(ComandoSaga::comando).toList(); }

    OsSaga aguardandoDecisao() {
        var s = OsSaga.iniciar(osId, em(0));
        s.retirarComandos(); s.retirarAcoes();
        s.diagnosticoConcluido(em(1));
        s.retirarComandos();
        s.orcamentoGerado(UUID.randomUUID(), new BigDecimal("150.00"), em(2));
        s.retirarAcoes();
        return s;
    }

    @Test void iniciarPedeDiagnosticoEAbreDiagnosticoNaOs() {
        var s = OsSaga.iniciar(osId, em(0));
        assertThat(s.getEstado()).isEqualTo(AGUARDANDO_DIAGNOSTICO);
        assertThat(s.getEtapa()).isEqualTo(EtapaSaga.DIAGNOSTICO);
        assertThat(s.getVersaoOrcamento()).isEqualTo(1);
        assertThat(s.retirarAcoes()).containsExactly(AcaoLocal.INICIAR_DIAGNOSTICO_OS);
        var comandos = s.retirarComandos();
        assertThat(tipos(comandos)).containsExactly(Comando.INICIAR_DIAGNOSTICO);
        assertThat(comandos.get(0).etapa()).isEqualTo(EtapaSaga.DIAGNOSTICO);
        assertThat(s.aceita(comandos.get(0).messageId())).isTrue();
        assertThat(s.getPrazo()).isEqualTo(T0.plus(Duration.ofDays(3)));
        assertThat(s.compensacoesPendentes()).containsExactly(CANCELAR_EXECUCAO);
        assertThat(s.retirarComandos()).isEmpty();
    }

    @Test void diagnosticoConcluidoPedeOrcamentoComPrazoCurto() {
        var s = OsSaga.iniciar(osId, em(0));
        s.retirarComandos();
        s.diagnosticoConcluido(em(60));
        assertThat(s.getEstado()).isEqualTo(AGUARDANDO_ORCAMENTO);
        assertThat(tipos(s.retirarComandos())).containsExactly(Comando.GERAR_ORCAMENTO);
        assertThat(s.getPrazo()).isEqualTo(em(65).agora());
        assertThat(s.compensacoesPendentes()).isEmpty();
    }

    @Test void orcamentoGeradoGuardaValorEAvisaOs() {
        var s = OsSaga.iniciar(osId, em(0));
        s.retirarComandos(); s.retirarAcoes();
        s.diagnosticoConcluido(em(1));
        s.retirarComandos();
        var orcamento = UUID.randomUUID();
        s.orcamentoGerado(orcamento, new BigDecimal("150.00"), em(2));
        assertThat(s.getEstado()).isEqualTo(AGUARDANDO_DECISAO);
        assertThat(s.getOrcamentoId()).isEqualTo(orcamento);
        assertThat(s.getValorOrcado()).isEqualByComparingTo("150.00");
        assertThat(s.retirarAcoes()).containsExactly(AcaoLocal.ENVIAR_ORCAMENTO_OS);
        assertThat(s.retirarComandos()).isEmpty();
        assertThat(s.getComandoAguardadoId()).isNull();
        assertThat(s.getPrazo()).isEqualTo(em(2).agora().plus(Duration.ofDays(7)));
        assertThat(s.compensacoesPendentes()).containsExactly(CANCELAR_ORCAMENTO);
    }

    @Test void caminhoFelizAteConclusao() {
        var s = aguardandoDecisao();
        s.registrarDecisao(true, em(3));
        var decisao = s.retirarComandos();
        assertThat(tipos(decisao)).containsExactly(Comando.REGISTRAR_DECISAO);
        s.orcamentoAprovado("https://pagamento.example/checkout/1", em(4));
        assertThat(s.retirarAcoes()).containsExactly(AcaoLocal.AGUARDAR_PAGAMENTO_OS);
        assertThat(s.getEstado()).isEqualTo(AGUARDANDO_PAGAMENTO);
        assertThat(s.getCheckoutUrl()).isEqualTo("https://pagamento.example/checkout/1");
        assertThat(s.aceita(decisao.get(0).messageId())).isTrue();
        s.pagamentoConfirmado(em(5));
        assertThat(s.getEstado()).isEqualTo(RESERVANDO_PECAS);
        s.pecasReservadas(em(5));
        assertThat(tipos(s.retirarComandos())).containsExactly(Comando.INICIAR_REPARO);
        assertThat(s.getEstado()).isEqualTo(AGUARDANDO_REPARO);
        assertThat(s.compensacoesPendentes())
                .containsExactly(CANCELAR_ORCAMENTO, ESTORNAR_PAGAMENTO, REPOR_ESTOQUE, CANCELAR_EXECUCAO);
        s.reparoIniciado(em(6));
        assertThat(s.retirarAcoes()).containsExactly(AcaoLocal.INICIAR_EXECUCAO_OS);
        assertThat(s.getEstado()).isEqualTo(EM_EXECUCAO);
        assertThat(s.compensacoesPendentes()).isEmpty();
        assertThat(s.getPrazo()).isNull();
        s.execucaoFinalizada(em(90));
        assertThat(s.retirarAcoes()).containsExactly(AcaoLocal.FINALIZAR_OS);
        assertThat(s.getEstado()).isEqualTo(CONCLUIDA);
        assertThat(s.getEstado().terminal()).isTrue();
        assertThat(s.getComandoAguardadoId()).isNull();
    }

    @Test void segundaDecisaoAntesDaConfirmacaoERejeitada() {
        var s = aguardandoDecisao();
        s.registrarDecisao(true, em(3));
        assertThatThrownBy(() -> s.registrarDecisao(false, em(3))).isInstanceOf(BusinessRuleException.class);
        assertThat(s.retirarComandos()).hasSize(1);
    }

    @Test void recusaVoltaAoDiagnosticoComNovaVersaoDeOrcamento() {
        var s = aguardandoDecisao();
        s.registrarDecisao(false, em(3));
        var decisao = s.retirarComandos().get(0);
        s.orcamentoRecusado(em(4));
        assertThat(s.getVersaoOrcamento()).isEqualTo(2);
        assertThat(s.getEstado()).isEqualTo(AGUARDANDO_DIAGNOSTICO);
        assertThat(s.retirarAcoes()).containsExactly(AcaoLocal.VOLTAR_DIAGNOSTICO_OS);
        var novo = s.retirarComandos();
        assertThat(tipos(novo)).containsExactly(Comando.INICIAR_DIAGNOSTICO);
        assertThat(s.aceita(decisao.messageId())).isFalse();
        assertThat(s.aceita(novo.get(0).messageId())).isTrue();
        assertThat(s.compensacoesPendentes()).containsExactly(CANCELAR_EXECUCAO);
        assertThat(s.getValorOrcado()).isNull();
        assertThat(s.getOrcamentoId()).isNull();
        assertThat(s.getDecisaoAprovada()).isNull();
    }

    @Test void respostaSemCausaOuDeComandoDesconhecidoNaoEAceita() {
        var s = OsSaga.iniciar(osId, em(0));
        assertThat(s.aceita(null)).isFalse();
        assertThat(s.aceita(UUID.randomUUID())).isFalse();
    }

    @Test void transicaoForaDeOrdemFalha() {
        var s = OsSaga.iniciar(osId, em(0));
        assertThatThrownBy(() -> s.pagamentoConfirmado(em(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> s.orcamentoAprovado("https://x.example", em(1))).isInstanceOf(IllegalStateException.class);
    }

    @Test void passosRegistramTrilhaOrdenada() {
        var s = OsSaga.iniciar(osId, em(0));
        s.registrarResposta(Resposta.DIAGNOSTICO_CONCLUIDO, em(1));
        assertThat(s.getPassos()).extracting(OsSagaPasso::getEvento).containsExactly(
                "ESTADO:AGUARDANDO_DIAGNOSTICO", "COMANDO:IniciarDiagnostico", "RESPOSTA:DiagnosticoConcluido");
        assertThat(s.getPassos()).extracting(OsSagaPasso::getOrdem).containsExactly(1, 2, 3);
    }

    @Test void vocabularioDeMensagensUsaNomesDoContrato() {
        assertThat(Comando.GERAR_ORCAMENTO.tipo()).isEqualTo("GerarOrcamento");
        assertThat(Comando.GERAR_ORCAMENTO.destino()).isEqualTo(Destino.BILLING);
        assertThat(Comando.INICIAR_REPARO.destino()).isEqualTo(Destino.EXECUCAO);
        assertThat(Resposta.deTipo("PagamentoConfirmado")).contains(Resposta.PAGAMENTO_CONFIRMADO);
        assertThat(Resposta.deTipo("GerarOrcamento")).isEmpty();
        assertThat(Compensacao.REPOR_ESTOQUE.local()).isTrue();
        assertThat(Compensacao.ESTORNAR_PAGAMENTO.comando()).isEqualTo(Comando.ESTORNAR_PAGAMENTO);
    }

    @Test void prazosInvalidosSaoRejeitados() {
        assertThatThrownBy(() -> new PrazosSaga(Duration.ZERO, Duration.ofMinutes(5), Duration.ofDays(7),
                Duration.ofMinutes(30), Duration.ofMinutes(5), 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrazosSaga(Duration.ofDays(3), Duration.ofMinutes(5), Duration.ofDays(7),
                Duration.ofMinutes(30), Duration.ofMinutes(5), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
