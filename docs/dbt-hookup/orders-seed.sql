-- Warehouse fixture that mirrors what the dbt project's `fct_orders`,
-- `dim_customers`, `dim_locations` models would produce. Small enough
-- to inspect; big enough to power the analytics demos.

DROP TABLE IF EXISTS FCT_ORDERS;
DROP TABLE IF EXISTS DIM_CUSTOMERS;
DROP TABLE IF EXISTS DIM_LOCATIONS;

CREATE TABLE DIM_CUSTOMERS (
  ID              INT PRIMARY KEY,
  CUSTOMER_TYPE   VARCHAR(16),
  COUNTRY         VARCHAR(2)
);

INSERT INTO DIM_CUSTOMERS VALUES
 (1, 'individual', 'US'),
 (2, 'business',   'US'),
 (3, 'individual', 'GB'),
 (4, 'business',   'GB'),
 (5, 'individual', 'CA'),
 (6, 'business',   'DE');

CREATE TABLE DIM_LOCATIONS (
  ID     INT PRIMARY KEY,
  NAME   VARCHAR(32),
  REGION VARCHAR(16)
);

INSERT INTO DIM_LOCATIONS VALUES
 (1, 'Downtown SF',  'West'),
 (2, 'Times Square', 'East'),
 (3, 'Camden Town',  'UK'),
 (4, 'Berlin Mitte', 'DACH');

CREATE TABLE FCT_ORDERS (
  ID             INT PRIMARY KEY,
  CUSTOMER_ID    INT,
  LOCATION_ID    INT,
  ORDERED_AT     VARCHAR(10),
  IS_FOOD_ORDER  INT,
  STATUS         VARCHAR(16),
  ORDER_TOTAL    DECIMAL(10,2)
);

-- 30 orders across 4 locations, 6 customers, 6 months.
INSERT INTO FCT_ORDERS VALUES
 ( 1, 1, 1, '2024-07-05', 1, 'completed', 42.50),
 ( 2, 2, 2, '2024-07-12', 0, 'completed', 155.00),
 ( 3, 3, 3, '2024-07-19', 1, 'completed', 18.75),
 ( 4, 4, 4, '2024-07-26', 0, 'completed', 220.00),
 ( 5, 5, 1, '2024-08-02', 1, 'completed', 36.00),
 ( 6, 6, 2, '2024-08-09', 0, 'cancelled', 89.50),
 ( 7, 1, 3, '2024-08-16', 1, 'completed', 24.25),
 ( 8, 2, 4, '2024-08-23', 0, 'completed', 175.75),
 ( 9, 3, 1, '2024-09-01', 1, 'completed', 51.00),
 (10, 4, 2, '2024-09-08', 0, 'refunded',  95.00),
 (11, 5, 3, '2024-09-15', 1, 'completed', 33.50),
 (12, 6, 4, '2024-09-22', 0, 'completed', 210.00),
 (13, 1, 1, '2024-10-05', 1, 'completed', 47.25),
 (14, 2, 2, '2024-10-12', 0, 'completed', 165.00),
 (15, 3, 3, '2024-10-19', 1, 'completed', 22.00),
 (16, 4, 4, '2024-10-26', 0, 'cancelled', 305.00),
 (17, 5, 1, '2024-11-02', 1, 'completed', 41.75),
 (18, 6, 2, '2024-11-09', 0, 'completed', 145.50),
 (19, 1, 3, '2024-11-16', 1, 'completed', 28.00),
 (20, 2, 4, '2024-11-23', 0, 'completed', 195.00),
 (21, 3, 1, '2024-12-05', 1, 'completed', 55.25),
 (22, 4, 2, '2024-12-12', 0, 'completed', 240.00),
 (23, 5, 3, '2024-12-19', 1, 'completed', 32.75),
 (24, 6, 4, '2024-12-26', 0, 'completed', 275.00),
 (25, 1, 1, '2024-12-31', 1, 'completed', 62.50),
 (26, 2, 2, '2024-07-04', 0, 'completed', 190.00),
 (27, 3, 3, '2024-08-04', 1, 'completed', 19.50),
 (28, 4, 4, '2024-09-04', 0, 'completed', 285.00),
 (29, 5, 1, '2024-10-04', 1, 'completed', 44.00),
 (30, 6, 2, '2024-11-04', 0, 'completed', 155.75);
