package com.oficina.support;

import com.oficina.entity.*;
import com.oficina.domain.identidade.Ator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import com.oficina.entity.TipoDocumento;

import java.util.UUID;

public final class Fixtures {
    public static final UUID CLIENTE_A = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private Fixtures() {}

    /** New synthetic history uses explicit UTC provenance; no legacy timestamps are converted. */
    public static OrdemServico ordem(Cliente cliente, StatusOrdemServico status) {
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        OrdemServico os = OrdemServico.builder().cliente(cliente).status(StatusOrdemServico.RECEBIDA)
                .valorTotal(BigDecimal.ZERO).zonaCompatibilidade(ZoneOffset.UTC).build();
        os.registrarHistoricoInicial(Ator.sistema(), now, "Fixture UTC");
        if (status == StatusOrdemServico.RECEBIDA) return os;
        os.iniciarDiagnostico(Ator.sistema(), now, null);
        if (status == StatusOrdemServico.EM_DIAGNOSTICO) return os;
        os.enviarOrcamentoParaAprovacao(Ator.sistema(), now, null);
        if (status == StatusOrdemServico.AGUARDANDO_APROVACAO) return os;
        os.aprovarExecucaoCliente(Ator.cliente(cliente.getId()), now, null);
        if (status == StatusOrdemServico.EM_EXECUCAO) return os;
        os.finalizarServico(Ator.sistema(), now, null);
        if (status == StatusOrdemServico.FINALIZADA) return os;
        os.registrarEntrega(Ator.sistema(), now, null);
        return os;
    }

    public static Cliente cliente(UUID id) {
        return Cliente.builder().id(id).nome("Cliente de teste")
                .tipoDocumento(TipoDocumento.CPF).documento("39053344705")
                .email("cliente@example.invalid").telefone("11999999999").ativo(true).build();
    }
}
