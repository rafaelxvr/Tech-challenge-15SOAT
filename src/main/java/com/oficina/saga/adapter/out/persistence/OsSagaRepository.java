package com.oficina.saga.adapter.out.persistence;

import com.oficina.saga.domain.OsSaga;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OsSagaRepository extends JpaRepository<OsSaga, UUID> {
    Optional<OsSaga> findByOsId(UUID osId);

    boolean existsByOsId(UUID osId);

    /**
     * FOR UPDATE SKIP LOCKED only keeps concurrent selections apart: the lock is released when the short
     * selection transaction commits, before each saga expires in its own transaction. Multi-pod safety comes
     * from the {@code @Version} check and the {@code prazo} re-check in {@code OsSaga.prazoExpirado}; the losing
     * pod rolls back and logs {@code saga_prazo_falhou}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT s FROM OsSaga s WHERE s.prazo <= :agora ORDER BY s.prazo")
    List<OsSaga> findVencidasParaProcessar(@Param("agora") Instant agora, Pageable limite);
}
