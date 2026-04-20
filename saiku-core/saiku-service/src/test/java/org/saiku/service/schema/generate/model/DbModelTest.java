package org.saiku.service.schema.generate.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class DbModelTest {

    private DbTable ordersTable() {
        DbColumn id = new DbColumn("id", JDBCType.INTEGER, false, true);
        DbColumn customerId = new DbColumn("customer_id", JDBCType.INTEGER, false, false);
        DbForeignKey fk = new DbForeignKey("customer_id", "customers", "id");
        return new DbTable("public", "orders", Arrays.asList(id, customerId), Collections.singletonList(fk), 1000L);
    }

    @Test
    public void tableByNameReturnsMatchingTable() {
        DbTable orders = ordersTable();
        DbModel model = DbModel.of(Collections.singletonList(orders));

        Optional<DbTable> found = model.tableByName("orders");

        assertTrue(found.isPresent());
        assertSame(orders, found.get());
    }

    @Test
    public void tableByNameReturnsEmptyWhenMissing() {
        DbModel model = DbModel.of(Collections.singletonList(ordersTable()));

        Optional<DbTable> found = model.tableByName("missing");

        assertFalse(found.isPresent());
    }

    @Test
    public void equalityIsByValue() {
        DbModel a = DbModel.of(Collections.singletonList(ordersTable()));
        DbModel b = DbModel.of(Collections.singletonList(ordersTable()));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void differentTablesAreNotEqual() {
        DbTable orders = ordersTable();
        DbTable customers = new DbTable(
                "public",
                "customers",
                Collections.singletonList(new DbColumn("id", JDBCType.INTEGER, false, true)),
                Collections.<DbForeignKey>emptyList(),
                50L);

        DbModel a = DbModel.of(Collections.singletonList(orders));
        DbModel b = DbModel.of(Arrays.asList(orders, customers));

        assertFalse(a.equals(b));
    }

    @Test
    public void columnAndForeignKeyEqualityByValue() {
        DbColumn c1 = new DbColumn("id", JDBCType.INTEGER, false, true);
        DbColumn c2 = new DbColumn("id", JDBCType.INTEGER, false, true);
        assertEquals(c1, c2);

        DbForeignKey fk1 = new DbForeignKey("customer_id", "customers", "id");
        DbForeignKey fk2 = new DbForeignKey("customer_id", "customers", "id");
        assertEquals(fk1, fk2);
    }

    @Test
    public void mutatingInputListDoesNotAffectModel() {
        List<DbTable> mutable = new ArrayList<>();
        mutable.add(ordersTable());

        DbModel model = DbModel.of(mutable);

        // Mutate the original list after the model has been built.
        mutable.clear();

        // The model's view must remain unchanged (defensive copy).
        assertEquals(1, model.tables().size());
        assertTrue(model.tableByName("orders").isPresent());
    }

    @Test
    public void tableByNameIsCaseSensitive() {
        List<DbTable> tables = Collections.singletonList(ordersTable());
        DbModel model = DbModel.of(tables);

        assertFalse(model.tableByName("ORDERS").isPresent());
        assertTrue(model.tableByName("orders").isPresent());
    }
}
