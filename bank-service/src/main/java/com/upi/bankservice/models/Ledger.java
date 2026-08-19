package com.upi.bankservice.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single-sided posting against an account.
 *
 * <p><b>Constraint change.</b> {@code tx_id} previously carried
 * {@code unique = true}. That made it physically impossible for one
 * transaction to have both a debit and a credit posting -- so an intra-bank
 * transfer could never be recorded in full, and the ledger could not balance.
 *
 * <p>The correct grain is one posting per (transaction, direction):
 * a transaction may have a DEBIT, a CREDIT and a REVERSAL, but never two of
 * the same kind. That constraint is also what makes a repeated funds-movement
 * request safe: the second attempt violates it and is recognised as a
 * duplicate rather than posting again.
 */
@Entity
@Table(name = "ledger",
       uniqueConstraints = @UniqueConstraint(
               name = "uq_ledger_tx_leg", columnNames = {"tx_id", "type"}))
public class Ledger {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(name = "ledger_id", updatable = false, nullable = false)
    private UUID ledgerId;

    @Column(name = "tx_id", nullable = false)
    private String txId;

    /** Retrieval Reference Number of the originating payment, when known. */
    @Column(name = "rrn", length = 20)
    private String rrn;

    /** This posting's own reference, quoted back to the orchestrator. */
    @Column(name = "reference", length = 64)
    private String reference;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type; // DEBIT / CREDIT

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status; // SUCCESS / FAILED / PENDING

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // --- Enums ---
    /**
     * REVERSAL is a compensating credit, kept distinct from an ordinary
     * CREDIT. Collapsing the two would make "was this payment reversed?"
     * unanswerable from the ledger, and a ledger that cannot answer that is
     * not an audit trail.
     */
    public enum TransactionType { DEBIT, CREDIT, REVERSAL }
    public enum TransactionStatus { SUCCESS, FAILED, PENDING }

    // --- Getters and Setters ---
    public UUID getLedgerId() { return ledgerId; }
    public void setLedgerId(UUID ledgerId) { this.ledgerId = ledgerId; }

    public String getTxId() { return txId; }
    public void setTxId(String txId) { this.txId = txId; }

    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }

    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public TransactionType getType() { return type; }
    public void setType(TransactionType type) { this.type = type; }

    public TransactionStatus getStatus() { return status; }
    public void setStatus(TransactionStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public String getRrn() { return rrn; }
    public void setRrn(String rrn) { this.rrn = rrn; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    // --- Custom Builder ---
    public static class LedgerBuilder {
        private String txId;
        private String accountNumber;
        private BigDecimal amount;
        private TransactionType type;
        private TransactionStatus status;
        private BigDecimal balanceAfter;
        private String rrn;
        private String reference;

        public LedgerBuilder rrn(String rrn) { this.rrn = rrn; return this; }
        public LedgerBuilder reference(String reference) { this.reference = reference; return this; }

        public LedgerBuilder txId(String txId) { this.txId = txId; return this; }
        public LedgerBuilder accountNumber(String accountNumber) { this.accountNumber = accountNumber; return this; }
        public LedgerBuilder amount(BigDecimal amount) { this.amount = amount; return this; }
        public LedgerBuilder type(TransactionType type) { this.type = type; return this; }
        public LedgerBuilder status(TransactionStatus status) { this.status = status; return this; }
        public LedgerBuilder balanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; return this; }

        public Ledger build() {
            Ledger ledger = new Ledger();
            ledger.setTxId(this.txId);
            ledger.setAccountNumber(this.accountNumber);
            ledger.setAmount(this.amount);
            ledger.setType(this.type);
            ledger.setStatus(this.status);
            ledger.setBalanceAfter(this.balanceAfter);
            ledger.setRrn(this.rrn);
            ledger.setReference(this.reference);
            return ledger;
        }
    }

    public static LedgerBuilder builder() { return new LedgerBuilder(); }
}
