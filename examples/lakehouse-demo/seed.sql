-- Seed the Iceberg lakehouse with a small analytics star built from
-- Trino's built-in TPC-H generator. Everything lands as REAL Iceberg
-- tables (Parquet on MinIO, metadata in the REST catalog).
--
-- fact: sales — one row per order, denormalised with the customer's
-- market (region -> nation) and the order date split for the cube's
-- Time hierarchy. ~15k rows from tpch.tiny: small enough to seed in
-- seconds, big enough that pivots feel real.

CREATE SCHEMA IF NOT EXISTS iceberg.sales;

CREATE TABLE IF NOT EXISTS iceberg.sales.fact_orders AS
SELECT
    o.orderkey                                   AS order_key,
    c.name                                       AS customer_name,
    r.name                                       AS region_name,
    n.name                                       AS nation_name,
    CAST(year(o.orderdate) AS INTEGER)           AS order_year,
    CAST(month(o.orderdate) AS INTEGER)          AS order_month,
    CASE o.orderstatus
        WHEN 'O' THEN 'Open'
        WHEN 'F' THEN 'Fulfilled'
        WHEN 'P' THEN 'Pending'
        ELSE o.orderstatus
    END                                          AS order_status,
    o.orderpriority                              AS order_priority,
    o.totalprice                                 AS total_price
FROM tpch.tiny.orders o
JOIN tpch.tiny.customer c ON o.custkey = c.custkey
JOIN tpch.tiny.nation   n ON c.nationkey = n.nationkey
JOIN tpch.tiny.region   r ON n.regionkey = r.regionkey;
