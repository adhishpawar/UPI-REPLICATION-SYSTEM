package com.upi.payment.observability;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ExecutionEventRepository extends JpaRepository<ExecutionEvent, Long> {

    List<ExecutionEvent> findByTransactionIdOrderByIdAsc(UUID transactionId);

    List<ExecutionEvent> findByTraceIdOrderBySeqAsc(String traceId);

    @Query("SELECT COALESCE(MAX(e.seq), 0) FROM ExecutionEvent e WHERE e.traceId = :traceId")
    Integer maxSeqForTrace(@Param("traceId") String traceId);

    List<ExecutionEvent> findAllByOrderByIdDesc(Pageable pageable);

    @Query("SELECT e FROM ExecutionEvent e WHERE e.id > :afterId ORDER BY e.id ASC")
    List<ExecutionEvent> findSince(@Param("afterId") Long afterId, Pageable pageable);
}
