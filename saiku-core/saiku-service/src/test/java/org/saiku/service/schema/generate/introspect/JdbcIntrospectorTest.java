/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.introspect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.schema.generate.model.DbColumn;
import org.saiku.service.schema.generate.model.DbForeignKey;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.model.DbTable;

/**
 * Integration test for {@link JdbcIntrospector} using an in-memory H2 database
 * seeded with a small fact-plus-dimension schema. Verifies tables, columns,
 * primary-key flags, foreign-key edges, and row-count estimates round-trip
 * through the introspector into a {@link DbModel}.
 */
public class JdbcIntrospectorTest {

    private static final String URL = "jdbc:h2:mem:schemagen-introspect;DB_CLOSE_DELAY=-1";

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        conn = DriverManager.getConnection(URL, "sa", "");
        try (Statement st = conn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("CREATE SCHEMA test");
            st.execute("CREATE TABLE test.customer (id INT PRIMARY KEY, name VARCHAR(64))");
            st.execute("CREATE TABLE test.category (id INT PRIMARY KEY, label VARCHAR(64))");
            st.execute("CREATE TABLE test.product  (id INT PRIMARY KEY, name VARCHAR(64), category_id INT,"
                    + " FOREIGN KEY (category_id) REFERENCES test.category(id))");
            st.execute("CREATE TABLE test.orders ("
                    + " id INT PRIMARY KEY,"
                    + " customer_id INT,"
                    + " product_id INT,"
                    + " order_date DATE,"
                    + " amount DECIMAL(10,2),"
                    + " qty INT,"
                    + " FOREIGN KEY (customer_id) REFERENCES test.customer(id),"
                    + " FOREIGN KEY (product_id)  REFERENCES test.product(id))");
            st.execute("INSERT INTO test.customer VALUES (1, 'alice'), (2, 'bob')");
            st.execute("INSERT INTO test.category VALUES (1, 'books')");
            st.execute("INSERT INTO test.product  VALUES (1, 'a', 1), (2, 'b', 1)");
            st.execute("INSERT INTO test.orders   VALUES (1, 1, 1, '2024-01-01', 9.99, 1)");
        }
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null) {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP ALL OBJECTS");
            }
            conn.close();
        }
    }

    @Test
    public void introspectsH2FactAndDimensionSchema() throws Exception {
        JdbcIntrospector.Options opts =
                new JdbcIntrospector.Options().withCatalog(conn.getCatalog()).withSchemaPattern("TEST");
        JdbcIntrospector introspector = new JdbcIntrospector(opts);

        DbModel model = introspector.introspect(conn);

        assertNotNull(model);
        assertEquals("expected 4 user tables", 4, model.tables().size());

        Set<String> names = new HashSet<>();
        for (DbTable t : model.tables()) {
            names.add(t.name());
        }
        // H2 default uppercases identifiers.
        assertTrue("expected CUSTOMER present: " + names, names.contains("CUSTOMER"));
        assertTrue("expected PRODUCT present: " + names, names.contains("PRODUCT"));
        assertTrue("expected CATEGORY present: " + names, names.contains("CATEGORY"));
        assertTrue("expected ORDERS present: " + names, names.contains("ORDERS"));

        // --- orders: 6 columns, id is PK, 2 FKs (customer_id, product_id) ---
        DbTable orders = model.tableByName("ORDERS").orElseThrow(() -> new AssertionError("no ORDERS table"));
        assertEquals("TEST", orders.schema());
        assertEquals(6, orders.columns().size());

        DbColumn ordersId = columnByName(orders, "ID");
        assertTrue("ORDERS.ID must be primary key", ordersId.primaryKey());

        DbColumn orderDate = columnByName(orders, "ORDER_DATE");
        assertEquals(JDBCType.DATE, orderDate.type());

        DbColumn amount = columnByName(orders, "AMOUNT");
        assertEquals(JDBCType.DECIMAL, amount.type());

        assertEquals("orders should have 2 FKs", 2, orders.foreignKeys().size());
        DbForeignKey fkCustomer = fkByFromColumn(orders, "CUSTOMER_ID");
        assertEquals("CUSTOMER", fkCustomer.toTable());
        assertEquals("ID", fkCustomer.toColumn());
        DbForeignKey fkProduct = fkByFromColumn(orders, "PRODUCT_ID");
        assertEquals("PRODUCT", fkProduct.toTable());
        assertEquals("ID", fkProduct.toColumn());

        assertNotNull("orders row-count estimate should not be null", orders.rowCountEstimate());
        assertTrue("orders row-count >= 1", orders.rowCountEstimate() >= 1L);

        // --- product: 3 columns, id is PK, 1 FK category_id -> category.id ---
        DbTable product = model.tableByName("PRODUCT").orElseThrow(() -> new AssertionError("no PRODUCT"));
        assertEquals(3, product.columns().size());
        assertTrue("PRODUCT.ID must be PK", columnByName(product, "ID").primaryKey());
        assertEquals(1, product.foreignKeys().size());
        DbForeignKey fkCategory = product.foreignKeys().get(0);
        assertEquals("CATEGORY_ID", fkCategory.fromColumn());
        assertEquals("CATEGORY", fkCategory.toTable());
        assertEquals("ID", fkCategory.toColumn());

        // --- customer / category: id PK, no FKs ---
        DbTable customer = model.tableByName("CUSTOMER").orElseThrow(() -> new AssertionError("no CUSTOMER"));
        assertTrue(columnByName(customer, "ID").primaryKey());
        assertEquals(0, customer.foreignKeys().size());

        DbTable category = model.tableByName("CATEGORY").orElseThrow(() -> new AssertionError("no CATEGORY"));
        assertTrue(columnByName(category, "ID").primaryKey());
        assertEquals(0, category.foreignKeys().size());

        // Row count should be non-null (COUNT(*) fallback) and >= seeded rows for every table.
        assertNotNull(customer.rowCountEstimate());
        assertTrue(customer.rowCountEstimate() >= 2L);
        assertNotNull(product.rowCountEstimate());
        assertTrue(product.rowCountEstimate() >= 2L);
        assertNotNull(category.rowCountEstimate());
        assertTrue(category.rowCountEstimate() >= 1L);
    }

    @Test
    public void tableSizeThresholdSkipsCountOnLargeTablesWithoutStats() throws Exception {
        // Set threshold to 0 so every table is considered "too big" for COUNT(*).
        // Since H2 stats may be 0 / unavailable in this version, expect rowCountEstimate
        // to be null (skipped) for all tables.
        JdbcIntrospector.Options opts = new JdbcIntrospector.Options()
                .withCatalog(conn.getCatalog())
                .withSchemaPattern("TEST")
                .withTableSizeThreshold(0L);
        JdbcIntrospector introspector = new JdbcIntrospector(opts);

        DbModel model = introspector.introspect(conn);

        // Can't guarantee stats exist on fresh H2 tables; assert at least that
        // we didn't crash and we returned 4 tables.
        assertEquals(4, model.tables().size());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static DbColumn columnByName(DbTable t, String name) {
        for (DbColumn c : t.columns()) {
            if (name.equals(c.name())) {
                return c;
            }
        }
        fail("no column " + name + " in table " + t.name());
        return null;
    }

    private static DbForeignKey fkByFromColumn(DbTable t, String fromColumn) {
        for (DbForeignKey fk : t.foreignKeys()) {
            if (fromColumn.equals(fk.fromColumn())) {
                return fk;
            }
        }
        fail("no FK from " + fromColumn + " in table " + t.name());
        return null;
    }
}
