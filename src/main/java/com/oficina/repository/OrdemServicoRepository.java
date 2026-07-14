package com.oficina.repository;

import com.oficina.entity.OrdemServico;
import com.oficina.entity.StatusOrdemServico;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.lang.NonNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrdemServicoRepository extends JpaRepository<OrdemServico, UUID> {

    @EntityGraph(attributePaths = {"cliente", "veiculo"})
    @NonNull
    Page<OrdemServico> findAll(@NonNull Pageable pageable);

    @EntityGraph(attributePaths = {"cliente", "veiculo"})
    Page<OrdemServico> findByStatus(StatusOrdemServico status, Pageable pageable);

    /**
     * Listagem operacional: exclui FINALIZADA/ENTREGUE (exclusão lógica na listagem)
     * e ordena por prioridade de status + mais antigas primeiro.
     */
    @EntityGraph(attributePaths = {"cliente", "veiculo"})
    @Query(
            value = """
                    SELECT o FROM OrdemServico o
                    WHERE o.status NOT IN :excluidos
                      AND (:status IS NULL OR o.status = :status)
                    ORDER BY
                      CASE o.status
                        WHEN com.oficina.entity.StatusOrdemServico.EM_EXECUCAO THEN 1
                        WHEN com.oficina.entity.StatusOrdemServico.AGUARDANDO_APROVACAO THEN 2
                        WHEN com.oficina.entity.StatusOrdemServico.EM_DIAGNOSTICO THEN 3
                        WHEN com.oficina.entity.StatusOrdemServico.RECEBIDA THEN 4
                        ELSE 5
                      END ASC,
                      o.criadoEm ASC
                    """,
            countQuery = """
                    SELECT COUNT(o) FROM OrdemServico o
                    WHERE o.status NOT IN :excluidos
                      AND (:status IS NULL OR o.status = :status)
                    """
    )
    Page<OrdemServico> findAtivasOrdenadasPorPrioridade(
            @Param("status") StatusOrdemServico status,
            @Param("excluidos") java.util.Collection<StatusOrdemServico> excluidos,
            Pageable pageable);

    Optional<OrdemServico> findByNumero(Long numero);

    @EntityGraph(attributePaths = {"pecas", "pecas.peca", "cliente"})
    @Query("SELECT o FROM OrdemServico o WHERE o.numero = :numero")
    Optional<OrdemServico> findComPecasEClientePorNumero(@Param("numero") Long numero);

    @Query(value = """
            SELECT AVG(EXTRACT(EPOCH FROM (finalizado_em - iniciado_em)) / 60.0)
            FROM ordens_servico
            WHERE iniciado_em IS NOT NULL AND finalizado_em IS NOT NULL
            """, nativeQuery = true)
    Double mediaTempoExecucaoMinutosReal();

    @Query(value = """
            SELECT COUNT(*) FROM ordens_servico
            WHERE iniciado_em IS NOT NULL AND finalizado_em IS NOT NULL
            """, nativeQuery = true)
    long countOrdensComTempoMedido();

    @Query("""
            SELECT DISTINCT o FROM OrdemServico o
            JOIN FETCH o.cliente c
            JOIN FETCH o.veiculo v
            JOIN FETCH v.cliente
            WHERE o.numero = :numero
            """)
    Optional<OrdemServico> findDetalheAcompanhamentoPorNumero(@Param("numero") Long numero);
}
