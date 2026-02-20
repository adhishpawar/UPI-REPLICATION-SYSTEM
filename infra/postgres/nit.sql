-- infra/postgres/init.sql
-- Creates all 7 microservice databases in one PostgreSQL instance

CREATE DATABASE vpa_db;
CREATE DATABASE psp_db;
CREATE DATABASE payment_db;
CREATE DATABASE debit_db;
CREATE DATABASE credit_db;
CREATE DATABASE notif_db;
CREATE DATABASE qr_db;

-- Grant all privileges to the admin user
GRANT ALL PRIVILEGES ON DATABASE vpa_db      TO upi_admin;
GRANT ALL PRIVILEGES ON DATABASE psp_db      TO upi_admin;
GRANT ALL PRIVILEGES ON DATABASE payment_db  TO upi_admin;
GRANT ALL PRIVILEGES ON DATABASE debit_db    TO upi_admin;
GRANT ALL PRIVILEGES ON DATABASE credit_db   TO upi_admin;
GRANT ALL PRIVILEGES ON DATABASE notif_db    TO upi_admin;
GRANT ALL PRIVILEGES ON DATABASE qr_db       TO upi_admin;
