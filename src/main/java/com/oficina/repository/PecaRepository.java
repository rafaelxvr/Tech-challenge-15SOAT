package com.oficina.repository;

import com.oficina.entity.Peca;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PecaRepository extends JpaRepository<Peca, UUID> {

    Optional<Peca> findByCodigo(String codigo);

    Page<Peca> findByAtivoTrue(Pageable pageable);
}
