package com.oficina.saga.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RespostaTest {
    @Test void cadaRespostaSoRespondeAoComandoDoContrato() {
        assertThat(Resposta.DIAGNOSTICO_CONCLUIDO.respondeA(Comando.INICIAR_DIAGNOSTICO)).isTrue();
        assertThat(Resposta.REPARO_RECUSADO.respondeA(Comando.INICIAR_DIAGNOSTICO)).isFalse();
        assertThat(Resposta.ORCAMENTO_CANCELADO.respondeA(Comando.ESTORNAR_PAGAMENTO)).isFalse();
        assertThat(Resposta.PAGAMENTO_ESTORNADO.respondeA(Comando.ESTORNAR_PAGAMENTO)).isTrue();
        assertThat(Resposta.PAGAMENTO_CONFIRMADO.respondeA(Comando.REGISTRAR_DECISAO)).isTrue();
    }

    @Test void compensacaoFalhouRespondeAQualquerCompensacao() {
        for (Comando c : new Comando[]{Comando.CANCELAR_ORCAMENTO, Comando.ESTORNAR_PAGAMENTO, Comando.CANCELAR_EXECUCAO}) {
            assertThat(Resposta.COMPENSACAO_FALHOU.respondeA(c)).isTrue();
        }
        assertThat(Resposta.COMPENSACAO_FALHOU.respondeA(Comando.GERAR_ORCAMENTO)).isFalse();
        assertThat(Resposta.COMPENSACAO_FALHOU.respondeA(null)).isFalse();
    }
}
