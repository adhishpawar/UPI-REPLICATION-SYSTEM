package com.upi.payment.recovery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReconciliationRecordRepository
        extends JpaRepository<ReconciliationRecord, UUID> {

    List<ReconciliationRecord> findByTransactionIdOrderByCheckedAtAsc(UUID transactionId);

    long countByMatchedFalse();
}
