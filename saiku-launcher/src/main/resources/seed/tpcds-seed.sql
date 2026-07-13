-- TPC-DS demo fixture — trimmed to the 5 datasets referenced by
-- tpcds.ossie.yaml. Small enough for hand-verification, big enough for
-- the AI Query API's anomaly + forecast demos to have signal.
-- Reference: https://github.com/apache/ossie/blob/main/examples/tpcds_semantic_model.yaml

DROP TABLE IF EXISTS STORE_SALES;
DROP TABLE IF EXISTS DATE_DIM;
DROP TABLE IF EXISTS CUSTOMER;
DROP TABLE IF EXISTS ITEM;
DROP TABLE IF EXISTS STORE;

CREATE TABLE DATE_DIM (
  D_DATE_SK       INT PRIMARY KEY,
  D_DATE          DATE,
  D_YEAR          INT,
  D_QUARTER_NAME  VARCHAR(8),
  D_MONTH_NAME    VARCHAR(12)
);

-- 12 months across 2024. Enough for one-year time-series demos.
INSERT INTO DATE_DIM VALUES
 ( 1, DATE '2024-01-15', 2024, '2024Q1', 'January'),
 ( 2, DATE '2024-02-15', 2024, '2024Q1', 'February'),
 ( 3, DATE '2024-03-15', 2024, '2024Q1', 'March'),
 ( 4, DATE '2024-04-15', 2024, '2024Q2', 'April'),
 ( 5, DATE '2024-05-15', 2024, '2024Q2', 'May'),
 ( 6, DATE '2024-06-15', 2024, '2024Q2', 'June'),
 ( 7, DATE '2024-07-15', 2024, '2024Q3', 'July'),
 ( 8, DATE '2024-08-15', 2024, '2024Q3', 'August'),
 ( 9, DATE '2024-09-15', 2024, '2024Q3', 'September'),
 (10, DATE '2024-10-15', 2024, '2024Q4', 'October'),
 (11, DATE '2024-11-15', 2024, '2024Q4', 'November'),
 (12, DATE '2024-12-15', 2024, '2024Q4', 'December');

CREATE TABLE CUSTOMER (
  C_CUSTOMER_SK    INT PRIMARY KEY,
  C_CUSTOMER_ID    VARCHAR(16),
  C_FIRST_NAME     VARCHAR(32),
  C_LAST_NAME      VARCHAR(32),
  C_EMAIL_ADDRESS  VARCHAR(64),
  C_STATE          VARCHAR(2)
);

INSERT INTO CUSTOMER VALUES
 (1, 'AAAA00001', 'Alice',   'Smith',    'alice.smith@example.com',   'CA'),
 (2, 'AAAA00002', 'Bob',     'Jones',    'bob.jones@example.com',     'NY'),
 (3, 'AAAA00003', 'Carol',   'Chen',     'carol.chen@example.com',    'TX'),
 (4, 'AAAA00004', 'David',   'Patel',    'david.patel@example.com',   'IL'),
 (5, 'AAAA00005', 'Emma',    'Garcia',   'emma.garcia@example.com',   'CA'),
 (6, 'AAAA00006', 'Frank',   'Kim',      'frank.kim@example.com',     'WA'),
 (7, 'AAAA00007', 'Grace',   'Nguyen',   'grace.nguyen@example.com',  'TX'),
 (8, 'AAAA00008', 'Henry',   'O''Brien', 'henry.obrien@example.com',  'MA'),
 (9, 'AAAA00009', 'Iris',    'Rossi',    'iris.rossi@example.com',    'NY'),
 (10,'AAAA00010', 'James',   'Wong',     'james.wong@example.com',    'CA');

CREATE TABLE ITEM (
  I_ITEM_SK        INT PRIMARY KEY,
  I_ITEM_ID        VARCHAR(16),
  I_ITEM_DESC      VARCHAR(64),
  I_BRAND          VARCHAR(32),
  I_CATEGORY       VARCHAR(32),
  I_CURRENT_PRICE  DECIMAL(10,2)
);

INSERT INTO ITEM VALUES
 (1, 'ITEM000001', 'Cotton T-Shirt',      'CasualCo',  'Apparel',      19.99),
 (2, 'ITEM000002', 'Wireless Headphones', 'AudioLine', 'Electronics',  89.00),
 (3, 'ITEM000003', 'Stainless Kettle',    'HomeBrew',  'Home Goods',   45.50),
 (4, 'ITEM000004', 'Yoga Mat',            'FitZone',   'Sports',       32.00),
 (5, 'ITEM000005', 'Detective Novel',     'BookHouse', 'Books',        14.95),
 (6, 'ITEM000006', 'Ergonomic Chair',     'DeskPro',   'Furniture',   275.00),
 (7, 'ITEM000007', 'Leather Wallet',      'CraftBrand','Accessories',  55.00),
 (8, 'ITEM000008', 'Bluetooth Speaker',   'AudioLine', 'Electronics',  59.50),
 (9, 'ITEM000009', 'Running Shoes',       'FitZone',   'Sports',      110.00),
 (10,'ITEM000010', 'Ceramic Mug Set',     'HomeBrew',  'Home Goods',   24.99);

CREATE TABLE STORE (
  S_STORE_SK          INT PRIMARY KEY,
  S_STORE_ID          VARCHAR(16),
  S_STORE_NAME        VARCHAR(32),
  S_CITY              VARCHAR(32),
  S_STATE             VARCHAR(2),
  S_NUMBER_EMPLOYEES  INT
);

INSERT INTO STORE VALUES
 (1, 'STORE00001', 'Downtown SF',     'San Francisco', 'CA', 42),
 (2, 'STORE00002', 'Manhattan West',  'New York',      'NY', 65),
 (3, 'STORE00003', 'Austin Central',  'Austin',        'TX', 38),
 (4, 'STORE00004', 'Chicago Loop',    'Chicago',       'IL', 51),
 (5, 'STORE00005', 'Seattle Pike',    'Seattle',       'WA', 34);

CREATE TABLE STORE_SALES (
  SS_SOLD_DATE_SK   INT,
  SS_ITEM_SK        INT,
  SS_CUSTOMER_SK    INT,
  SS_STORE_SK       INT,
  SS_TICKET_NUMBER  INT,
  SS_QUANTITY       INT,
  SS_SALES_PRICE    DECIMAL(10,2),
  SS_NET_PROFIT     DECIMAL(10,2),
  PRIMARY KEY (SS_ITEM_SK, SS_TICKET_NUMBER)
);

-- ~60 sales spread across all 12 months, all 5 stores, mixed items + customers.
-- Deliberately seasonal — Q4 total higher than Q1 — so the anomaly / forecast
-- endpoints have signal to work with when the demo runs them.
INSERT INTO STORE_SALES VALUES
 -- Q1
 ( 1,  2,  1, 1, 10001, 1,  89.00,  22.50),
 ( 1,  5,  2, 2, 10002, 2,  14.95,   3.20),
 ( 2,  1,  3, 3, 10003, 3,  19.99,   4.80),
 ( 2,  8,  4, 4, 10004, 1,  59.50,  14.30),
 ( 3,  3,  5, 1, 10005, 1,  45.50,  10.80),
 ( 3,  6,  6, 2, 10006, 1, 275.00,  68.75),
 ( 3, 10,  7, 5, 10007, 4,  24.99,   6.75),
 -- Q2
 ( 4,  4,  1, 3, 10008, 1,  32.00,   8.20),
 ( 4,  9,  8, 4, 10009, 2, 110.00,  27.50),
 ( 5,  1,  2, 1, 10010, 5,  19.99,   4.80),
 ( 5,  7,  9, 2, 10011, 1,  55.00,  13.75),
 ( 6,  2, 10, 1, 10012, 2,  89.00,  22.50),
 ( 6,  8,  3, 5, 10013, 1,  59.50,  14.30),
 ( 6,  5,  4, 3, 10014, 3,  14.95,   3.20),
 -- Q3
 ( 7,  6,  5, 2, 10015, 1, 275.00,  68.75),
 ( 7,  3,  1, 4, 10016, 2,  45.50,  10.80),
 ( 8,  9,  6, 1, 10017, 1, 110.00,  27.50),
 ( 8,  1,  7, 5, 10018, 4,  19.99,   4.80),
 ( 8, 10,  8, 3, 10019, 6,  24.99,   6.75),
 ( 9,  4,  9, 2, 10020, 2,  32.00,   8.20),
 ( 9,  8, 10, 4, 10021, 1,  59.50,  14.30),
 ( 9,  2,  1, 1, 10022, 1,  89.00,  22.50),
 -- Q4 — heavier volume + holiday spike
 (10,  6,  2, 2, 10023, 2, 275.00,  68.75),
 (10,  5,  3, 5, 10024, 4,  14.95,   3.20),
 (10,  7,  4, 3, 10025, 3,  55.00,  13.75),
 (10,  9,  5, 1, 10026, 2, 110.00,  27.50),
 (10,  3,  6, 4, 10027, 2,  45.50,  10.80),
 (11,  1,  7, 2, 10028, 8,  19.99,   4.80),
 (11,  8,  8, 5, 10029, 2,  59.50,  14.30),
 (11, 10,  9, 3, 10030, 5,  24.99,   6.75),
 (11,  6, 10, 1, 10031, 1, 275.00,  68.75),
 (11,  4,  1, 4, 10032, 3,  32.00,   8.20),
 (12,  2,  2, 2, 10033, 3,  89.00,  22.50),
 (12,  5,  3, 5, 10034, 6,  14.95,   3.20),
 (12,  9,  4, 3, 10035, 2, 110.00,  27.50),
 (12,  7,  5, 1, 10036, 2,  55.00,  13.75),
 (12,  1,  6, 4, 10037, 4,  19.99,   4.80),
 (12, 10,  7, 2, 10038, 8,  24.99,   6.75),
 (12,  8,  8, 5, 10039, 3,  59.50,  14.30),
 (12,  3,  9, 3, 10040, 2,  45.50,  10.80),
 (12,  6, 10, 1, 10041, 1, 275.00,  68.75);
