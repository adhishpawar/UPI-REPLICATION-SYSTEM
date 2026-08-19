-- Runs after Hibernate's schema update (defer-datasource-initialization=true).
-- Idempotent: safe on every boot.
--
-- These statements exist because `ddl-auto=update` is not schema management.
-- It only ever ADDS: it will create a new table, column or constraint, but it
-- never alters or drops one that already exists. The database therefore drifts
-- away from the entity classes silently, and the drift surfaces at runtime.
-- Both statements below repair drift that did exactly that.
--
-- (This is why payment_db uses Flyway with `ddl-auto: validate` instead --
-- the schema is a reviewed, versioned artefact rather than a side effect.
-- Migrating bank-service to Flyway is tracked in known-gaps.)

-- 1. The original UNIQUE(tx_id) allowed only one posting per transaction, so
--    an intra-bank transfer could never record both its sides and the ledger
--    could never balance. The correct grain, UNIQUE(tx_id, type), is on the
--    entity; this removes the one it replaces.
ALTER TABLE ledger DROP CONSTRAINT IF EXISTS ledger_tx_id_key;

-- 2. A stale CHECK from when TransactionType had only DEBIT and CREDIT. After
--    REVERSAL was added to the enum, Hibernate left the old constraint in
--    place, so every compensating posting was rejected by the database.
--
--    The failure was instructive: the rejection surfaced as a
--    DataIntegrityViolationException, which the posting service read as
--    "duplicate", returned as 409, and the orchestrator mapped to "outcome
--    unknown". A schema defect was thereby laundered into an uncertain
--    payment, and a payer's reversal sat unresolved. Dropping it lets
--    Hibernate recreate it covering the full enum.
ALTER TABLE ledger DROP CONSTRAINT IF EXISTS ledger_type_check;
