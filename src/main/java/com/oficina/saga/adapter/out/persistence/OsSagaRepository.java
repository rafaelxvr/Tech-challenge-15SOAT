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

    /** Rows locked FOR UPDATE SKIP LOCKED so several pods never expire the same saga twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT s FROM OsSaga s WHERE s.prazo <= :agora ORDER BY s.prazo")
    List<OsSaga> findVencidasParaProcessar(@Param("agora") Instant agora, Pageable limite);
}
