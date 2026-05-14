package org.saiku.service.schema.generate.infer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.JDBCType;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.draft.Provenance;
import org.saiku.service.schema.generate.model.DbColumn;
import org.saiku.service.schema.generate.model.DbForeignKey;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.model.DbTable;

/**
 * Tests the {@link SchemaInferrer} orchestrator against a hand-built foodmart-mini
 * {@link DbModel} (one fact, three FK dims, one date column).
 *
 * <p>The fixture is built inline rather than loaded from a JSON file — A7's only consumer
 * is this test, and a JSON loader belongs in A8 (golden tests) where multiple fixtures
 * justify the infrastructure.
 */
public class SchemaInferrerTest {

    private static DbColumn pk(String name) {
        return new DbColumn(name, JDBCType.INTEGER, false, true);
    }

    private static DbColumn col(String name, JDBCType type) {
        return new DbColumn(name, type, true, false);
    }

    /** Build the foodmart-mini DbModel used across assertions. */
    private static DbModel foodmartMini() {
        DbTable salesFact = new DbTable(
                "public",
                "sales_fact",
                Arrays.asList(
                        pk("id"),
                        col("customer_id", JDBCType.INTEGER),
                        col("product_id", JDBCType.INTEGER),
                        col("store_id", JDBCType.INTEGER),
                        col("order_date", JDBCType.DATE),
                        col("amount", JDBCType.DOUBLE),
                        col("units", JDBCType.INTEGER)),
                Arrays.asList(
                        new DbForeignKey("customer_id", "customer", "id"),
                        new DbForeignKey("product_id", "product", "id"),
                        new DbForeignKey("store_id", "store", "id")),
                1_000_000L);
        DbTable customer = new DbTable(
                "public",
                "customer",
                Arrays.asList(pk("id"), col("name", JDBCType.VARCHAR)),
                Collections.emptyList(),
                100L);
        DbTable product = new DbTable(
                "public",
                "product",
                Arrays.asList(pk("id"), col("name", JDBCType.VARCHAR)),
                Collections.emptyList(),
                100L);
        DbTable store = new DbTable(
                "public",
                "store",
                Arrays.asList(pk("id"), col("name", JDBCType.VARCHAR)),
                Collections.emptyList(),
                100L);
        return DbModel.of(Arrays.asList(salesFact, customer, product, store));
    }

    @Test
    public void foodmartMiniProducesOneCubeFourDimensionsMeasuresAllRuleProvenance() {
        DbModel model = foodmartMini();

        DraftSchema schema = new SchemaInferrer().infer(model);

        // 1 cube from the single fact.
        assertEquals(1, schema.cubes().size());
        DraftCube cube = schema.cubes().get(0);
        assertEquals("sales_fact", cube.name());
        assertEquals("sales_fact", cube.sourceFactTable());

        // No shared dims — degenerate time dims live on the cube.
        assertTrue(
                "no shared dims in degenerate-time model",
                schema.sharedDimensions().isEmpty());

        // 4 dimensions on the cube (customer, product, store, degenerate order_date time dim).
        assertEquals(4, cube.dimensions().size());
        Map<String, DraftDimension> byName = new HashMap<>();
        for (DraftDimension d : cube.dimensions()) {
            byName.put(d.name(), d);
        }
        assertTrue("customer dim", byName.containsKey("customer"));
        assertTrue("product dim", byName.containsKey("product"));
        assertTrue("store dim", byName.containsKey("store"));
        assertTrue("order_date time dim", byName.containsKey("order_date"));

        // Foreign keys wired from the fact side for FK dims.
        assertEquals("customer_id", byName.get("customer").foreignKey());
        assertEquals("product_id", byName.get("product").foreignKey());
        assertEquals("store_id", byName.get("store").foreignKey());
        // Degenerate time dim has no FK and colocates on the fact.
        assertEquals(DraftDimension.Type.TIME, byName.get("order_date").type());
        assertEquals("sales_fact", byName.get("order_date").sourceTable());
        assertEquals(
                "Y/Q/M/D levels",
                4,
                byName.get("order_date").hierarchies().get(0).levels().size());

        // Standard dims point at their own source tables.
        assertEquals("customer", byName.get("customer").sourceTable());
        assertEquals("product", byName.get("product").sourceTable());
        assertEquals("store", byName.get("store").sourceTable());

        // Measures: amount + units + Fact Count (>= 2).
        List<DraftMeasure> measures = cube.measures();
        assertTrue("expected >= 2 measures, got " + measures.size(), measures.size() >= 2);
        assertTrue(measures.size() >= 3);

        // Every element has RULE provenance.
        assertRuleProvenance(cube.provenance(), "cube");
        for (DraftDimension d : cube.dimensions()) {
            assertDimensionRuleProvenance(d);
        }
        for (DraftMeasure m : cube.measures()) {
            assertRuleProvenance(m.provenance(), "measure " + m.name());
        }
    }

    @Test
    public void constructorInjectsCollaborators() {
        // Sanity: the explicit-collaborators ctor wires the same result as the defaults.
        SchemaInferrer inferrer = new SchemaInferrer(
                new TableClassifier(), new DimensionBuilder(), new TimeDimensionBuilder(), new MeasureBuilder());
        DraftSchema schema = inferrer.infer(foodmartMini());
        assertEquals(1, schema.cubes().size());
        assertEquals(4, schema.cubes().get(0).dimensions().size());
    }

    @Test
    public void noDateColumnsMeansNoSharedTimeDim() {
        // If no fact has a date column, the shared Time dim should be absent and no time usages created.
        DbTable factNoDate = new DbTable(
                "public",
                "sales_fact",
                Arrays.asList(
                        pk("id"),
                        col("customer_id", JDBCType.INTEGER),
                        col("product_id", JDBCType.INTEGER),
                        col("amount", JDBCType.DOUBLE)),
                Arrays.asList(
                        new DbForeignKey("customer_id", "customer", "id"),
                        new DbForeignKey("product_id", "product", "id")),
                1_000_000L);
        DbTable customer = new DbTable(
                "public",
                "customer",
                Arrays.asList(pk("id"), col("name", JDBCType.VARCHAR)),
                Collections.emptyList(),
                100L);
        DbTable product = new DbTable(
                "public",
                "product",
                Arrays.asList(pk("id"), col("name", JDBCType.VARCHAR)),
                Collections.emptyList(),
                100L);
        DbModel model = DbModel.of(Arrays.asList(factNoDate, customer, product));

        DraftSchema schema = new SchemaInferrer().infer(model);

        assertTrue("no shared time dim", schema.sharedDimensions().isEmpty());
        assertEquals(1, schema.cubes().size());
        DraftCube cube = schema.cubes().get(0);
        assertEquals(2, cube.dimensions().size());
        for (DraftDimension d : cube.dimensions()) {
            assertFalse("no time usages on cube", d.type() == DraftDimension.Type.TIME);
        }
    }

    private static void assertRuleProvenance(Provenance p, String what) {
        assertNotNull(what + " provenance", p);
        assertEquals(what + " source", Provenance.Source.RULE, p.source());
    }

    private static void assertDimensionRuleProvenance(DraftDimension d) {
        assertRuleProvenance(d.provenance(), "dim " + d.name());
        for (DraftHierarchy h : d.hierarchies()) {
            assertRuleProvenance(h.provenance(), "hierarchy " + h.name());
            for (DraftLevel l : h.levels()) {
                assertRuleProvenance(l.provenance(), "level " + l.name());
            }
        }
    }
}
