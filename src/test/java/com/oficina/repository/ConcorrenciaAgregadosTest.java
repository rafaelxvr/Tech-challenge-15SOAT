package com.oficina.repository;

import com.oficina.domain.identidade.Ator;
import com.oficina.entity.*;
import com.oficina.support.Fixtures;
import com.oficina.support.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

class ConcorrenciaAgregadosTest extends PostgresIntegrationSupport {
    private static final Instant NOW = Instant.parse("2026-09-15T13:00:00Z");
    private UUID clienteId;
    private UUID pecaId;
    private UUID primeiraOrdem;
    private UUID segundaOrdem;

    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM os_historico");
        jdbc.update("DELETE FROM os_pecas");
        jdbc.update("DELETE FROM os_servicos");
        jdbc.update("DELETE FROM ordens_servico");
        jdbc.update("DELETE FROM veiculos");
        jdbc.update("DELETE FROM cliente_identidade_auditoria");
        jdbc.update("DELETE FROM clientes");
        jdbc.update("DELETE FROM pecas");
        EntityManager em = entityManagers.createEntityManager();
        try {
            em.getTransaction().begin();
            Cliente cliente = Fixtures.cliente(null);
            em.persist(cliente);
            clienteId = cliente.getId();
            Veiculo veiculo = Veiculo.builder().cliente(cliente).placa("ABC1D23")
                    .marca("Teste").modelo("Teste").ano(2026).ativo(true).build();
            em.persist(veiculo);
            Peca peca = Peca.builder().codigo("LAST").nome("Última peça").valorUnitario(BigDecimal.TEN)
                    .quantidadeEstoque(1).quantidadeMinima(0).unidadeMedida("UN").ativo(true).build();
            em.persist(peca);
            pecaId = peca.getId();
            primeiraOrdem = persistOrder(em, cliente, veiculo, peca);
            segundaOrdem = persistOrder(em, cliente, veiculo, peca);
            em.getTransaction().commit();
        } finally { em.close(); }
    }

    private UUID persistOrder(EntityManager em, Cliente cliente, Veiculo veiculo, Peca peca) {
        OrdemServico os = Fixtures.ordem(cliente, StatusOrdemServico.AGUARDANDO_APROVACAO);
        os.setVeiculo(veiculo);
        os.adicionarPeca(OsPecaItem.builder().peca(peca).quantidade(1)
                .valorUnitario(BigDecimal.TEN).valorTotal(BigDecimal.TEN).build());
        em.persist(os);
        return os.getId();
    }

    @Test void simultaneousApprovalCommitsOneTransitionAndOneStockMovement() throws Exception {
        Throwable loser = race(primeiraOrdem, primeiraOrdem, this::approve);
        assertThat(loser).isNotNull();
        assertThat(isOptimistic(loser) || isSequenceConflict(loser)).isTrue();
        assertOrder(primeiraOrdem, "EM_EXECUCAO", 4, 1);
        assertThat(jdbc.queryForObject("SELECT quantidade_estoque FROM pecas WHERE id=?", Integer.class, pecaId)).isZero();
        assertThat(jdbc.queryForObject("SELECT versao FROM pecas WHERE id=?", Long.class, pecaId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ator_cliente_id FROM os_historico WHERE os_id=? AND sequencia=4", UUID.class, primeiraOrdem))
                .isEqualTo(clienteId);
        assertThat(jdbc.queryForObject("SELECT alterado_por FROM os_historico WHERE os_id=? AND sequencia=4", UUID.class, primeiraOrdem)).isNull();
    }

    @Test void lastStockRaceBetweenDifferentOrdersRollsBackLosingHistoryAndStatus() throws Exception {
        Throwable loser = race(primeiraOrdem, segundaOrdem, this::approve);
        assertThat(isOptimistic(loser)).isTrue();
        assertOrder(primeiraOrdem, "EM_EXECUCAO", 4, 1);
        assertOrder(segundaOrdem, "AGUARDANDO_APROVACAO", 3, 0);
        assertThat(jdbc.queryForObject("SELECT quantidade_estoque FROM pecas WHERE id=?", Integer.class, pecaId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM os_historico WHERE status_novo='EM_EXECUCAO'", Integer.class)).isEqualTo(1);
    }

    @Test void orderVersionRejectsStaleWriteEvenWithoutAHistoryInsert() throws Exception {
        Throwable loser = race(primeiraOrdem, primeiraOrdem, os -> os.setObservacoes("alterada"));
        assertThat(isOptimistic(loser)).isTrue();
        assertOrder(primeiraOrdem, "AGUARDANDO_APROVACAO", 3, 1);
    }

    @Test void newHistoryFollowsUnresolvedLegacyPrefixWithoutCompletingIt() {
        jdbc.update("UPDATE os_historico SET sequencia=NULL, ocorrido_em=NULL, ator_tipo='LEGACY_UNKNOWN' WHERE os_id=?", primeiraOrdem);
        jdbc.update("UPDATE ordens_servico SET sequencia_historico=0, criado_em_utc=NULL, historico_completo_desde_inicio=false WHERE id=?", primeiraOrdem);
        EntityManager em = entityManagers.createEntityManager();
        try {
            em.getTransaction().begin();
            OrdemServico os = em.find(OrdemServico.class, primeiraOrdem);
            approve(os);
            em.getTransaction().commit();
            em.clear();
            OrdemServico reloaded = em.find(OrdemServico.class, primeiraOrdem);
            assertThat(reloaded.getHistorico()).extracting(OsHistorico::getSequencia)
                    .containsExactly(null, null, null, 1L);
            assertThat(reloaded.getHistorico().get(3).getOcorridoEm()).isEqualTo(NOW);
            assertThat(reloaded.getSequenciaHistorico()).isEqualTo(1);
            assertThat(reloaded.isHistoricoCompletoDesdeInicio()).isFalse();
            assertThat(reloaded.getCriadoEmUtc()).isNull();
            assertThat(reloaded.getAprovadoEm()).isEqualTo(java.time.LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
            assertThat(reloaded.getAtualizadoEm()).isEqualTo(reloaded.getAprovadoEm());
        } finally { em.close(); }
    }

    private void approve(OrdemServico os) {
        os.setZonaCompatibilidade(ZoneOffset.UTC);
        os.aprovarExecucaoCliente(Ator.cliente(clienteId), NOW, "aprovação concorrente");
        os.getPecas().get(0).getPeca().baixarEstoque(1);
    }

    private void assertOrder(UUID id, String status, long sequence, long version) {
        assertThat(jdbc.queryForObject("SELECT status::text FROM ordens_servico WHERE id=?", String.class, id)).isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT sequencia_historico FROM ordens_servico WHERE id=?", Long.class, id)).isEqualTo(sequence);
        assertThat(jdbc.queryForObject("SELECT versao FROM ordens_servico WHERE id=?", Long.class, id)).isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM os_historico WHERE os_id=?", Long.class, id)).isEqualTo(sequence);
    }

    /** Both independent transactions load order/history/stock before either may commit. */
    private Throwable race(UUID winnerId, UUID loserId, Consumer<OrdemServico> mutation) throws Exception {
        CyclicBarrier loaded = new CyclicBarrier(2);
        CountDownLatch firstCommitted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> winner = executor.submit(() -> write(winnerId, mutation, loaded, firstCommitted, true));
            Future<Throwable> loser = executor.submit(() -> write(loserId, mutation, loaded, firstCommitted, false));
            assertThat(winner.get(20, TimeUnit.SECONDS)).isNull();
            return loser.get(20, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }

    private Throwable write(UUID id, Consumer<OrdemServico> mutation, CyclicBarrier loaded,
                            CountDownLatch firstCommitted, boolean first) {
        EntityManager em = entityManagers.createEntityManager();
        try {
            em.getTransaction().begin();
            OrdemServico os = em.find(OrdemServico.class, id);
            os.getHistorico().size();
            assertThat(os.getVersao()).isZero();
            assertThat(os.getPecas().get(0).getPeca().getQuantidadeEstoque()).isEqualTo(1);
            loaded.await(10, TimeUnit.SECONDS);
            if (!first && !firstCommitted.await(10, TimeUnit.SECONDS)) throw new TimeoutException("first commit");
            mutation.accept(os);
            em.flush();
            em.getTransaction().commit();
            return null;
        } catch (Throwable failure) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            return failure;
        } finally {
            if (first) firstCommitted.countDown();
            em.close();
        }
    }

    private boolean isOptimistic(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockException) return true;
        }
        return false;
    }

    private boolean isSequenceConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException c
                    && "uk_os_historico_os_sequencia".equals(c.getConstraintName())) return true;
        }
        return false;
    }
}
