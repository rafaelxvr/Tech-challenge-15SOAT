package com.oficina.repository;

import com.oficina.entity.Veiculo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface VeiculoRepository extends JpaRepository<Veiculo, UUID> {

    Optional<Veiculo> findByPlaca(String placa);

    Page<Veiculo> findByAtivoTrue(Pageable pageable);

    Page<Veiculo> findByClienteIdAndAtivoTrue(UUID clienteId, Pageable pageable);
}
