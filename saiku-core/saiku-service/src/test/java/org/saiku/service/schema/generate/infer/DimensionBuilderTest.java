package org.saiku.service.schema.generate.infer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.sql.JDBCType;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftJoin;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.Provenance;
import org.saiku.service.schema.generate.model.DbColumn;
import org.saiku.service.schema.generate.model.DbForeignKey;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.model.DbTable;

public class DimensionBuilderTest {

    private DbColumn pk(String name) {
        return new DbColumn(name, JDBCType.INTEGER, false, true);
    }

    private DbColumn intCol(String name) {
        return new DbColumn(name, JDBCType.INTEGER, true, false);
    }

    private DbColumn strCol(String name) {
        return new DbColumn(name, JDBCType.VARCHAR, true, false);
    }

    @Test
    public void flatDimensionWithPkAndSingleStringColumn() {
        DbTable customers = new DbTable(
                "public",
                "customers",
                Arrays.asList(pk("id"), strCol("name")),
                Collections.<DbForeignKey>emptyList(),
                100L);
        DbModel model = DbModel.of(Collections.singletonList(customers));

        DraftDimension dim = new DimensionBuilder().build(customers, model);

        assertEquals("customers", dim.name());
        assertEquals(DraftDimension.Type.STANDARD, dim.type());
        assertEquals("customers", dim.sourceTable());
        assertEquals(Provenance.Source.RULE, dim.provenance().source());
        assertEquals("rule:dim-flat", dim.provenance().ruleId());

        assertEquals(1, dim.hierarchies().size());
        DraftHierarchy h = dim.hierarchies().get(0);
        assertEquals("id", h.primaryKey());
        assertNull(h.join());

        assertEquals(1, h.levels().size());
        DraftLevel level = h.levels().get(0);
        assertEquals(DraftLevel.Type.REGULAR, level.type());
        assertEquals("id", level.name());
        // Caption-source column = "name" when there is exactly one non-PK string col.
        assertEquals("name", level.column());
    }

    @Test
    public void flatDimensionWithNoStringColumnsUsesPkOnly() {
        DbTable t = new DbTable(
                "public",
                "codes",
                Arrays.asList(pk("id"), intCol("amount")),
                Collections.<DbForeignKey>emptyList(),
                10L);
        DbModel model = DbModel.of(Collections.singletonList(t));
        DraftDimension dim = new DimensionBuilder().build(t, model);

        assertEquals("rule:dim-flat", dim.provenance().ruleId());
        DraftHierarchy h = dim.hierarchies().get(0);
        assertEquals(1, h.levels().size());
        assertEquals("id", h.levels().get(0).column());
    }

    @Test
    public void snowflakeOneHopAddsSecondLevelAndJoin() {
        DbTable categories = new DbTable(
                "public",
                "product_categories",
                Arrays.asList(pk("id"), strCol("label")),
                Collections.<DbForeignKey>emptyList(),
                20L);
        DbTable products = new DbTable(
                "public",
                "products",
                Arrays.asList(pk("id"), strCol("name"), intCol("category_id")),
                Collections.singletonList(new DbForeignKey("category_id", "product_categories", "id")),
                200L);
        DbModel model = DbModel.of(Arrays.asList(products, categories));

        DraftDimension dim = new DimensionBuilder().build(products, model);

        assertEquals("products", dim.name());
        assertEquals(Provenance.Source.RULE, dim.provenance().source());
        assertEquals("rule:snowflake-1hop", dim.provenance().ruleId());

        assertEquals(1, dim.hierarchies().size());
        DraftHierarchy h = dim.hierarchies().get(0);
        assertEquals("id", h.primaryKey());

        DraftJoin join = h.join();
        assertNotNull(join);
        assertEquals("products", join.leftTable());
        assertEquals("category_id", join.leftKey());
        assertEquals("product_categories", join.rightTable());
        assertEquals("id", join.rightKey());

        assertEquals(2, h.levels().size());
        // First level on products.
        DraftLevel first = h.levels().get(0);
        assertEquals("name", first.column());
        // Second level on the child (categories) uses its single non-PK string col.
        DraftLevel second = h.levels().get(1);
        assertEquals("label", second.column());
        assertEquals(DraftLevel.Type.REGULAR, second.type());
    }

    @Test
    public void deeperSnowflakeIsNotRecursed() {
        DbTable categories = new DbTable(
                "public",
                "product_categories",
                Arrays.asList(pk("id"), strCol("label"), intCol("parent_id")),
                Collections.singletonList(new DbForeignKey("parent_id", "product_categories", "id")),
                20L);
        DbTable products = new DbTable(
                "public",
                "products",
                Arrays.asList(pk("id"), strCol("name"), intCol("category_id")),
                Collections.singletonList(new DbForeignKey("category_id", "product_categories", "id")),
                200L);
        DbModel model = DbModel.of(Arrays.asList(products, categories));

        DraftDimension dim = new DimensionBuilder().build(products, model);

        assertEquals("rule:snowflake-1hop", dim.provenance().ruleId());
        DraftHierarchy h = dim.hierarchies().get(0);
        assertEquals(2, h.levels().size());
    }

    @Test
    public void multipleOutgoingFksFallBackToFlat() {
        DbTable a = new DbTable(
                "public", "a", Collections.singletonList(pk("id")), Collections.<DbForeignKey>emptyList(), 10L);
        DbTable b = new DbTable(
                "public", "b", Collections.singletonList(pk("id")), Collections.<DbForeignKey>emptyList(), 10L);
        DbTable t = new DbTable(
                "public",
                "t",
                Arrays.asList(pk("id"), strCol("name"), intCol("a_id"), intCol("b_id")),
                Arrays.asList(new DbForeignKey("a_id", "a", "id"), new DbForeignKey("b_id", "b", "id")),
                50L);
        DbModel model = DbModel.of(Arrays.asList(t, a, b));

        DraftDimension dim = new DimensionBuilder().build(t, model);

        assertEquals("rule:dim-flat", dim.provenance().ruleId());
        DraftHierarchy h = dim.hierarchies().get(0);
        assertNull(h.join());
        // Zero string cols after excluding PK? No — name is a string; a_id/b_id are ints. So one string col → caption.
        assertEquals(1, h.levels().size());
        assertEquals("name", h.levels().get(0).column());
    }
}
