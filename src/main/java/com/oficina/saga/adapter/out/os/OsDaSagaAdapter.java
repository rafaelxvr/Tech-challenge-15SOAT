package com.oficina.saga.adapter.out.os;

import com.oficina.domain.identidade.Ator;
import com.oficina.entity.OrdemServico;
import com.oficina.entity.OsPecaItem;
import com.oficina.entity.Peca;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.repository.OrdemServicoRepository;
import com.oficina.saga.application.port.OsDaSagaPort;
import com.oficina.saga.application.port.SnapshotOs;
import com.oficina.saga.domain.MotivoFalha;
import com.oficina.service.RegistroTransicaoOs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class OsDaSagaAdapter implements OsDaSagaPort {
    private static final String ENTIDADE = "Ordem de serviço";
    private final OrdemServicoRepository ordens;
    private final RegistroTransicaoOs registro;

    @Override public void iniciarDiagnostico(UUID osId) {
        transicionar(osId, (os, t) -> os.iniciarDiagnostico(Ator.sistema(), t, "Diagnóstico solicitado"));
    }

    @Override public void enviarOrcamento(UUID osId, BigDecimal valorOrcado) {
        transicionar(osId, (os, t) -> {
            os.definirValorOrcado(valorOrcado);
            os.enviarOrcamentoParaAprovacao(Ator.sistema(), t, "Orçamento gerado");
        });
    }

    @Override public void aguardarPagamento(UUID osId) {
        transicionar(osId, (os, t) -> os.aguardarPagamento(Ator.sistema(), t, "Orçamento aprovado pelo cliente"));
    }

    @Override public void voltarParaDiagnostico(UUID osId) {
        transicionar(osId, (os, t) -> os.recusarOrcamentoCliente(Ator.sistema(), t, "Orçamento recusado pelo cliente"));
    }

    @Override public void iniciarExecucao(UUID osId) {
        transicionar(osId, (os, t) -> os.iniciarExecucaoPaga(Ator.sistema(), t, "Reparo iniciado"));
    }

    @Override public void finalizar(UUID osId) {
        transicionar(osId, (os, t) -> os.finalizarServico(Ator.sistema(), t, "Execução finalizada"));
    }

    @Override public void cancelar(UUID osId, MotivoFalha motivo) {
        transicionar(osId, (os, t) -> os.cancelar(Ator.sistema(), t, "Cancelada pela saga: " + motivo.name()));
    }

    @Override public boolean reservarPecas(UUID osId) {
        Map<UUID, Necessidade> necessidades = somarPorPeca(carregar(osId));
        boolean disponivel = necessidades.values().stream()
                .allMatch(n -> n.peca().getQuantidadeEstoque() >= n.quantidade());
        if (!disponivel) return false;
        necessidades.values().forEach(n -> n.peca().baixarEstoque(n.quantidade()));
        return true;
    }

    @Override public void reporPecas(UUID osId) {
        somarPorPeca(carregar(osId)).values().forEach(n -> n.peca().reporEstoque(n.quantidade()));
    }

    @Override public SnapshotOs snapshot(UUID osId) {
        OrdemServico os = carregar(osId);
        List<SnapshotOs.ItemOs> itens = new ArrayList<>();
        os.getServicos().forEach(s -> itens.add(new SnapshotOs.ItemOs("SERVICO", s.getServico().getId(),
                s.getServico().getNome(), s.getQuantidade(), s.getValorUnitario())));
        os.getPecas().forEach(p -> itens.add(new SnapshotOs.ItemOs("PECA", p.getPeca().getId(),
                p.getPeca().getNome(), p.getQuantidade(), p.getValorUnitario())));
        return new SnapshotOs(os.getId(), os.getNumero(), os.getCliente().getId(), itens);
    }

    private void transicionar(UUID osId, BiConsumer<OrdemServico, Instant> transicao) {
        OrdemServico os = carregar(osId);
        Instant agora = registro.preparar(os);
        transicao.accept(os, agora);
        ordens.save(os);
        registro.notificar(os);
    }

    private OrdemServico carregar(UUID osId) {
        return ordens.findById(osId).orElseThrow(() -> new EntityNotFoundException(ENTIDADE, osId));
    }

    private static Map<UUID, Necessidade> somarPorPeca(OrdemServico os) {
        Map<UUID, Necessidade> porPeca = new LinkedHashMap<>();
        for (OsPecaItem item : os.getPecas()) {
            porPeca.merge(item.getPeca().getId(), new Necessidade(item.getPeca(), item.getQuantidade()),
                    (a, b) -> new Necessidade(a.peca(), a.quantidade() + b.quantidade()));
        }
        return porPeca;
    }

    private record Necessidade(Peca peca, int quantidade) {}
}
