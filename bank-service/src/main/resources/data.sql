-- Runs after Hibernate's schema update (defer-datasource-initialization=true).
-- Idempotent: safe on every boot.
--
-- Drops the original UNIQUE(tx_id) constraint on the ledger. That constraint
-- allowed only one posting per transaction, which meant an intra-bank transfer
-- could never record both its sides and the ledger could never balance.
-- The correct grain, UNIQUE(tx_id, type), is declared on the entity.
ALTER TABLE ledger DROP CONSTRAINT IF EXISTS ledger_tx_id_key;
