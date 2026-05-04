package com.oficina.repository;

import com.oficina.entity.Servico;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ServicoRepository extends JpaRepository<Servico, UUID> {

    Page<Servico> findByAtivoTrue(Pageable pageable);

    @Query("SELECT COALESCE(AVG(s.tempoEstimadoMin), 0) FROM Servico s WHERE s.ativo = true")
    Double mediaTempoEstimadoMinutosAtivos();
}
