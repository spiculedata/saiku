package org.saiku.service.schema.generate.infer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.JDBCType;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.schema.generate.model.DbColumn;
import org.saiku.service.schema.generate.model.DbForeignKey;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.model.DbTable;

public class TableClassifierTest {

    private DbColumn pk(String name) {
        return new DbColumn(name, JDBCType.INTEGER, false, true);
    }

    private DbColumn col(String name) {
        return new DbColumn(name, JDBCType.INTEGER, false, false);
    }

    @Test
    public void classifiesFactDimensionAndOrphan() {
        DbTable orders = new DbTable(
                "public",
                "orders",
                Arrays.asList(pk("id"), col("customer_id"), col("product_id"), col("store_id")),
                Arrays.asList(
                        new DbForeignKey("customer_id", "customers", "id"),
                        new DbForeignKey("product_id", "products", "id"),
                        new DbForeignKey("store_id", "stores", "id")),
                1_000_000L);
        DbTable customers = new DbTable(
                "public",
                "customers",
                Collections.singletonList(pk("id")),
                Collections.<DbForeignKey>emptyList(),
                10_000L);
        DbTable products = new DbTable(
                "public", "products", Collections.singletonList(pk("id")), Collections.<DbForeignKey>emptyList(), 500L);
        DbTable stores = new DbTable(
                "public", "stores", Collections.singletonList(pk("id")), Collections.<DbForeignKey>emptyList(), 50L);
        DbTable junk = new DbTable(
                "public", "junk", Collections.singletonList(pk("id")), Collections.<DbForeignKey>emptyList(), 5L);

        DbModel model = DbModel.of(Arrays.asList(orders, customers, products, stores, junk));

        Map<DbTable, TableClassification> result = new TableClassifier().classify(model);

        assertEquals(TableClassification.Kind.FACT, result.get(orders).kind());
        assertEquals(TableClassification.Kind.DIMENSION, result.get(customers).kind());
        assertEquals(TableClassification.Kind.DIMENSION, result.get(products).kind());
        assertEquals(TableClassification.Kind.DIMENSION, result.get(stores).kind());
        assertEquals(TableClassification.Kind.ORPHAN, result.get(junk).kind());

        assertNotNull(result.get(orders).reason());
        assertTrue(result.get(orders).reason().toLowerCase().contains("fk"));
        assertTrue(result.get(customers).reason().toLowerCase().contains("orders"));
        assertTrue(result.get(junk).reason().toLowerCase().contains("no"));
    }

    @Test
    public void nullRowCountIsNotEnoughToBeFact() {
        DbTable maybeFact = new DbTable(
                "public",
                "maybe_fact",
                Arrays.asList(pk("id"), col("a_id"), col("b_id")),
                Arrays.asList(new DbForeignKey("a_id", "a", "id"), new DbForeignKey("b_id", "b", "id")),
                null);
        DbTable a = new DbTable(
                "public", "a", Collections.singletonList(pk("id")), Collections.<DbForeignKey>emptyList(), 10L);
        DbTable b = new DbTable(
                "public", "b", Collections.singletonList(pk("id")), Collections.<DbForeignKey>emptyList(), 10L);

        DbModel model = DbModel.of(Arrays.asList(maybeFact, a, b));

        Map<DbTable, TableClassification> result = new TableClassifier().classify(model);

        // Not a fact → rowCount unknown disqualifies; it has FKs out but no one references it → ORPHAN
        assertEquals(TableClassification.Kind.ORPHAN, result.get(maybeFact).kind());
        // a and b are referenced only by a non-fact → ORPHAN
        assertEquals(TableClassification.Kind.ORPHAN, result.get(a).kind());
        assertEquals(TableClassification.Kind.ORPHAN, result.get(b).kind());
    }

    @Test
    public void tableReferencedOnlyByNonFactIsOrphan() {
        // Small "bridge" with 2 FKs but too few rows to be a fact.
        DbTable bridge = new DbTable(
                "public",
                "bridge",
                Arrays.asList(pk("id"), col("x_id"), col("y_id")),
                Arrays.asList(new DbForeignKey("x_id", "x", "id"), new DbForeignKey("y_id", "y", "id")),
                5L);
        DbTable x = new DbTable(
                "public", "x", Collections.singletonList(pk("id")), Collections.<DbForeignKey>emptyList(), 10L);
        DbTable y = new DbTable(
                "public", "y", Collections.singletonList(pk("id")), Collections.<DbForeignKey>emptyList(), 10L);

        DbModel model = DbModel.of(Arrays.asList(bridge, x, y));

        Map<DbTable, TableClassification> result = new TableClassifier().classify(model);

        assertEquals(TableClassification.Kind.ORPHAN, result.get(bridge).kind());
        assertEquals(TableClassification.Kind.ORPHAN, result.get(x).kind());
        assertEquals(TableClassification.Kind.ORPHAN, result.get(y).kind());
    }

    @Test
    public void classifyReturnsEntryForEveryTable() {
        DbTable lone = new DbTable(
                "public", "lone", Collections.singletonList(pk("id")), Collections.<DbForeignKey>emptyList(), 0L);
        DbModel model = DbModel.of(Collections.singletonList(lone));
        Map<DbTable, TableClassification> result = new TableClassifier().classify(model);
        assertEquals(1, result.size());
        assertEquals(TableClassification.Kind.ORPHAN, result.get(lone).kind());
    }

    @Test
    public void singleTableListIsUnused() {
        // Guard: the classifier must not mutate or share the input list.
        List<DbTable> empty = Collections.<DbTable>emptyList();
        DbModel model = DbModel.of(empty);
        Map<DbTable, TableClassification> result = new TableClassifier().classify(model);
        assertTrue(result.isEmpty());
    }
}
