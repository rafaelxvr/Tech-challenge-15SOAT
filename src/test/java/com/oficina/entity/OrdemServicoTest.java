package com.oficina.entity;

import com.oficina.entity.Cliente;
import com.oficina.exception.BusinessRuleException;
import com.oficina.entity.Peca;
import com.oficina.entity.Servico;
import com.oficina.entity.Veiculo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OrdemServicoTest {

    private OrdemServico ordem;

    @BeforeEach
    void setUp() {
        Cliente cliente = mock(Cliente.class);
        Veiculo veiculo = mock(Veiculo.class);
        ordem = OrdemServico.builder()
                .cliente(cliente)
                .veiculo(veiculo)
                .status(StatusOrdemServico.RECEBIDA)
                .valorTotal(BigDecimal.ZERO)
                .build();
    }

    @Test
    void registrarHistoricoInicial_adicionaEvento() {
        ordem.registrarHistoricoInicial(null, "Abertura");
        assertThat(ordem.getHistorico()).hasSize(1);
        assertThat(ordem.getHistorico().get(0).getStatusNovo()).isEqualTo(StatusOrdemServico.RECEBIDA);
    }

    @Test
    void adicionarServico_e_recalcularTotal() {
        Servico servico = mock(Servico.class);
        OsServicoItem item = OsServicoItem.builder()
                .servico(servico)
                .quantidade(2)
                .valorUnitario(new BigDecimal("50.00"))
                .valorTotal(new BigDecimal("100.00"))
                .build();
        ordem.adicionarServico(item);
        assertThat(item.getOrdemServico()).isEqualTo(ordem);

        ordem.recalcularValorTotal();
        assertThat(ordem.getValorTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    void adicionarPeca_somaNoTotal() {
        Peca peca = mock(Peca.class);
        OsPecaItem item = OsPecaItem.builder()
                .peca(peca)
                .quantidade(1)
                .valorUnitario(new BigDecimal("30.00"))
                .valorTotal(new BigDecimal("30.00"))
                .build();
        ordem.adicionarPeca(item);
        ordem.recalcularValorTotal();
        assertThat(ordem.getValorTotal()).isEqualByComparingTo("30.00");
    }

    @Test
    void fluxoFeliz_ateEntregue() {
        ordem.iniciarDiagnostico(null, null);
        assertThat(ordem.getStatus()).isEqualTo(StatusOrdemServico.EM_DIAGNOSTICO);

        ordem.enviarOrcamentoParaAprovacao(null, null);
        assertThat(ordem.getStatus()).isEqualTo(StatusOrdemServico.AGUARDANDO_APROVACAO);

        ordem.aprovarExecucaoCliente(null, null);
        assertThat(ordem.getStatus()).isEqualTo(StatusOrdemServico.EM_EXECUCAO);

        ordem.finalizarServico(null, null);
        assertThat(ordem.getStatus()).isEqualTo(StatusOrdemServico.FINALIZADA);

        ordem.registrarEntrega(null, null);
        assertThat(ordem.getStatus()).isEqualTo(StatusOrdemServico.ENTREGUE);
    }

    @Test
    void recusarOrcamento_voltaParaDiagnostico() {
        ordem.iniciarDiagnostico(null, null);
        ordem.enviarOrcamentoParaAprovacao(null, null);
        ordem.recusarOrcamentoCliente(null, "Cliente recusou");
        assertThat(ordem.getStatus()).isEqualTo(StatusOrdemServico.EM_DIAGNOSTICO);
    }

    @Test
    void recusarOrcamento_semObservacao_usaMensagemPadrao() {
        ordem.iniciarDiagnostico(null, null);
        ordem.enviarOrcamentoParaAprovacao(null, null);
        ordem.recusarOrcamentoCliente(null, null);
        assertThat(ordem.getStatus()).isEqualTo(StatusOrdemServico.EM_DIAGNOSTICO);
        assertThat(ordem.getHistorico().get(ordem.getHistorico().size() - 1).getObservacao())
                .contains("recusado");
    }

    @Test
    void transicaoInvalida_lancaExcecao() {
        assertThatThrownBy(() -> ordem.enviarOrcamentoParaAprovacao(null, null))
                .isInstanceOf(BusinessRuleException.class);
    }
}