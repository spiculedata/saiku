-- Saiku demo: joint bank accounts — comprehensive feature showcase.
-- Base rows are REUSED VERBATIM from the shipped
-- ../saiku/saiku-launcher/src/main/resources/seed/bank.sql — KEEP IN SYNC.
-- Additions here are backward-compatible (new columns/table only):
--   mm_fact.tenant      -> predicate row-security (#106) + query parameter (#105)
--   mm_fact.open_date   -> account-age duration (#108), paired with a FIXED as_of
--   mm_fact.as_of_date  -> fixed reference date so duration numbers are stable
--   mm_txn              -> measure-level distinct grain (#119)
-- Loaded into H2. Total balance 13000, fees 130 (unchanged from the base).

DROP TABLE IF EXISTS "mm_fact";
DROP TABLE IF EXISTS "mm_owner";
DROP TABLE IF EXISTS "mm_customer";
DROP TABLE IF EXISTS "mm_branch";
DROP TABLE IF EXISTS "mm_date";
DROP TABLE IF EXISTS "mm_txn";

CREATE TABLE "mm_fact" (
    "account_id" INTEGER,
    "date_key"   INTEGER,
    "branch_id"  VARCHAR(8),
    "balance"    INTEGER,
    "fees"       INTEGER,
    "tenant"     INTEGER,
    "open_date"  DATE,
    "as_of_date" DATE
);
CREATE TABLE "mm_owner" (
    "account_id"  INTEGER,
    "customer_id" VARCHAR(16),
    "weight"      DECIMAL(5,4)
);
CREATE TABLE "mm_customer" (
    "customer_id"   VARCHAR(16),
    "customer_name" VARCHAR(32),
    "segment"       VARCHAR(16)
);
CREATE TABLE "mm_branch" (
    "branch_id"   VARCHAR(8),
    "branch_name" VARCHAR(32)
);
CREATE TABLE "mm_date" (
    "date_key" INTEGER,
    "yr"       INTEGER
);
CREATE TABLE "mm_txn" (
    "txn_id"     INTEGER,
    "line_no"    INTEGER,
    "account_id" INTEGER,
    "amount"     INTEGER
);

-- Base fact rows (balance/fees verbatim from shipped). tenant: LON branch=1,
-- LDS branch=2 (a clean partition for the predicate-RLS demo). open_date chosen
-- so as_of 2025-01-01 gives whole-year ages; as_of_date constant for stability.
INSERT INTO "mm_fact"
  ("account_id","date_key","branch_id","balance","fees","tenant","open_date","as_of_date") VALUES
    (1, 2024, 'LON', 1000, 10, 1, DATE '2020-01-01', DATE '2025-01-01'),
    (2, 2024, 'LON',  500,  5, 1, DATE '2021-01-01', DATE '2025-01-01'),
    (3, 2025, 'LDS',  300,  3, 2, DATE '2023-01-01', DATE '2025-01-01'),
    (4, 2024, 'LON', 2000, 20, 1, DATE '2019-01-01', DATE '2025-01-01'),
    (5, 2024, 'LDS', 1500, 15, 2, DATE '2022-01-01', DATE '2025-01-01'),
    (6, 2025, 'LDS',  700,  7, 2, DATE '2024-01-01', DATE '2025-01-01'),
    (7, 2025, 'LON', 4000, 40, 1, DATE '2015-01-01', DATE '2025-01-01'),
    (8, 2025, 'LDS', 3000, 30, 2, DATE '2018-01-01', DATE '2025-01-01');

INSERT INTO "mm_owner" ("account_id","customer_id","weight") VALUES
    (1, 'alice', 0.50), (1, 'bob',   0.50), (2, 'bob',   1.00),
    (3, 'alice', 0.25), (3, 'carol', 0.75), (4, 'erin',  0.50),
    (4, 'frank', 0.50), (5, 'frank', 1.00), (6, 'grace', 0.40),
    (6, 'heidi', 0.60), (7, 'erin',  0.50), (7, 'grace', 0.50),
    (8, 'heidi', 1.00);

INSERT INTO "mm_customer" ("customer_id","customer_name","segment") VALUES
    ('alice', 'Alice', 'Premium'), ('bob',   'Bob',   'Premium'),
    ('carol', 'Carol', 'Standard'),('dave',  'Dave',  'Standard'),
    ('erin',  'Erin',  'Premium'), ('frank', 'Frank', 'Standard'),
    ('grace', 'Grace', 'Premium'), ('heidi', 'Heidi', 'Standard');

INSERT INTO "mm_branch" ("branch_id","branch_name") VALUES
    ('LON', 'London'), ('LDS', 'Leeds');

INSERT INTO "mm_date" ("date_key","yr") VALUES (2024, 2024), (2025, 2025);

-- Transactions: a FAN-OUT table for measure-level distinct grain (#119).
-- Each txn repeats its sale amount across lines (a denormalised join would
-- double-count). Distinct sum over txn_id de-dups to the true per-txn total.
--   txn 100: 3 lines @ 100  -> distinct 100
--   txn 200: 1 line  @ 50   -> distinct 50
--   txn 300: 2 lines @ 300  -> distinct 300
-- Distinct sum = 100+50+300 = 450 ; naive SUM = 300+50+600 = 950.
INSERT INTO "mm_txn" ("txn_id","line_no","account_id","amount") VALUES
    (100, 1, 1, 100), (100, 2, 1, 100), (100, 3, 1, 100),
    (200, 1, 2,  50),
    (300, 1, 3, 300), (300, 2, 3, 300);

-- Golden values (asserted in BankShowcaseH2EndToEndTest):
--   Balance grand total ........................ 13000
--   Full-count Balance by Customer: Alice 1300, Bob 1500, Carol 300 ...
--   Weighted  Balance by Customer:  Alice 575,  Bob 1000, Carol 225 ...
--   Median Balance (all) ....................... avg of middle balances
--   Distinct Amount (Transactions) ............. 450   (naive sum 950)
--   Predicate RLS tenant=1 (LON) Balance ....... 7500 ; tenant=2 (LDS) 5500
--   Member-grant 'Premium' segment excludes Standard-only owners (see test)
--   Account age (years, as_of 2025-01-01): acct7=10, acct8=7, acct4=6 ...
