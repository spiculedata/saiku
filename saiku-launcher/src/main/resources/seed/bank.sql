-- Saiku demo: joint bank accounts — a bridge (many-to-many) dimension.
--
-- Loaded by Database.loadBank() via RUNSCRIPT into the SAME H2 database as
-- FoodMart (distinct mm_* table names, no clash). Quoted-lowercase
-- identifiers match how Mondrian references them, mirroring foodmart_h2.sql.
--
-- One account has one balance but can be co-owned by several customers, so
-- the Customer dimension is linked through the account_owner bridge table
-- (mm_owner) with an ownership weight. Two cubes (full-count and weighted)
-- over the one fact demonstrate both allocation styles.
--
-- Deterministic and tiny so the numbers are verifiable by hand:
--   total balance 13000, total fees 130.

DROP TABLE IF EXISTS "mm_fact";
DROP TABLE IF EXISTS "mm_owner";
DROP TABLE IF EXISTS "mm_customer";
DROP TABLE IF EXISTS "mm_branch";
DROP TABLE IF EXISTS "mm_date";

CREATE TABLE "mm_fact" (
    "account_id" INTEGER,
    "date_key"   INTEGER,
    "branch_id"  VARCHAR(8),
    "balance"    INTEGER,
    "fees"       INTEGER
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

INSERT INTO "mm_fact" ("account_id","date_key","branch_id","balance","fees") VALUES
    (1, 2024, 'LON', 1000, 10),
    (2, 2024, 'LON',  500,  5),
    (3, 2025, 'LDS',  300,  3),
    (4, 2024, 'LON', 2000, 20),
    (5, 2024, 'LDS', 1500, 15),
    (6, 2025, 'LDS',  700,  7),
    (7, 2025, 'LON', 4000, 40),
    (8, 2025, 'LDS', 3000, 30);

INSERT INTO "mm_owner" ("account_id","customer_id","weight") VALUES
    (1, 'alice', 0.50),
    (1, 'bob',   0.50),
    (2, 'bob',   1.00),
    (3, 'alice', 0.25),
    (3, 'carol', 0.75),
    (4, 'erin',  0.50),
    (4, 'frank', 0.50),
    (5, 'frank', 1.00),
    (6, 'grace', 0.40),
    (6, 'heidi', 0.60),
    (7, 'erin',  0.50),
    (7, 'grace', 0.50),
    (8, 'heidi', 1.00);

INSERT INTO "mm_customer" ("customer_id","customer_name","segment") VALUES
    ('alice', 'Alice', 'Premium'),
    ('bob',   'Bob',   'Premium'),
    ('carol', 'Carol', 'Standard'),
    ('dave',  'Dave',  'Standard'),
    ('erin',  'Erin',  'Premium'),
    ('frank', 'Frank', 'Standard'),
    ('grace', 'Grace', 'Premium'),
    ('heidi', 'Heidi', 'Standard');

INSERT INTO "mm_branch" ("branch_id","branch_name") VALUES
    ('LON', 'London'),
    ('LDS', 'Leeds');

INSERT INTO "mm_date" ("date_key","yr") VALUES
    (2024, 2024),
    (2025, 2025);
