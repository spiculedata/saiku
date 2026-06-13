package org.saiku.web.embed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiSchema;

/**
 * Unit coverage for {@link EmbedPiiInspector} (saiku-cloud#940). Drives the
 * fail-CLOSED contract on the saved-query (.saiku/ThinQuery JSON) +
 * dashboard (.saikudash JSON) parse paths, the cube-level "any PII"
 * detection, and the dedupe across multi-tile dashboards.
 */
public class EmbedPiiInspectorTest {

    private StubDatasourceService ds;
    private StubCubeMetadataService cubes;
    private EmbedPiiInspector inspector;

    @Before
    public void setUp() {
        ds = new StubDatasourceService();
        cubes = new StubCubeMetadataService();
        inspector = new EmbedPiiInspector(ds, cubes);
    }

    /* ---------------------------- queries ---------------------------- */

    @Test
    public void query_with_clean_cube_returns_no_pii() {
        // ThinQuery JSON with a cube whose schema has no PII annotations.
        ds.put("/clean.saiku", thinQueryJson("conn-1", "Cat", "Sch", "Sales"));
        cubes.putSchema("conn-1/Cat/Sch/Sales", cleanSchema());

        EmbedPiiInspector.Result r = inspector.inspect("query", "/clean.saiku", "admin", List.of());
        assertFalse(r.referencesPii);
        assertEquals(1, r.cubeIds.size());
        assertFalse(r.fellOpenForUnresolvable);
    }

    @Test
    public void query_with_pii_measure_returns_pii() {
        ds.put("/dirty.saiku", thinQueryJson("conn-1", "Cat", "Sch", "Customer"));
        AiSchema schema = cleanSchema();
        AiSchema.Measure m = new AiSchema.Measure("SSN", "[Measures].[SSN]");
        m.pii = true;
        schema.measures.put(AiSchema.key("SSN"), m);
        cubes.putSchema("conn-1/Cat/Sch/Customer", schema);

        EmbedPiiInspector.Result r = inspector.inspect("query", "/dirty.saiku", "admin", List.of());
        assertTrue(r.referencesPii);
    }

    @Test
    public void query_with_pii_level_returns_pii() {
        ds.put("/dirty.saiku", thinQueryJson("conn-1", "Cat", "Sch", "Customer"));
        AiSchema schema = cleanSchema();
        AiSchema.Dimension d = new AiSchema.Dimension("Customer", "[Customer]");
        AiSchema.Hierarchy h = new AiSchema.Hierarchy("Customer", "[Customer]");
        AiSchema.Level l = new AiSchema.Level("Email", "[Customer].[Email]");
        l.pii = true;
        h.levels.put(AiSchema.key("Email"), l);
        d.hierarchies.put(AiSchema.key("Customer"), h);
        schema.dimensions.put(AiSchema.key("Customer"), d);
        cubes.putSchema("conn-1/Cat/Sch/Customer", schema);

        EmbedPiiInspector.Result r = inspector.inspect("query", "/dirty.saiku", "admin", List.of());
        assertTrue(r.referencesPii);
    }

    @Test
    public void query_with_unreadable_file_fails_closed() {
        // Path doesn't resolve in the stub — datasourceService.getFileData
        // throws. Inspector must default to "PII present" so the embed
        // gate refuses rather than allows.
        ds.throwOn("/missing.saiku");
        EmbedPiiInspector.Result r = inspector.inspect("query", "/missing.saiku", "admin", List.of());
        assertTrue("file unreadable must fail closed", r.referencesPii);
        assertTrue(r.fellOpenForUnresolvable);
    }

    @Test
    public void query_with_garbage_json_fails_closed() {
        ds.put("/broken.saiku", "not json");
        EmbedPiiInspector.Result r = inspector.inspect("query", "/broken.saiku", "admin", List.of());
        assertTrue("unparseable resource must fail closed", r.referencesPii);
        assertTrue(r.fellOpenForUnresolvable);
    }

    @Test
    public void query_with_no_cube_fails_closed() {
        // MDX-mode ThinQuery is bound only to a connection — we can't
        // inspect a cube we don't have. Fail closed.
        ds.put("/no-cube.saiku", "{\"name\":\"q\",\"type\":\"MDX\"}");
        EmbedPiiInspector.Result r = inspector.inspect("query", "/no-cube.saiku", "admin", List.of());
        assertTrue(r.referencesPii);
        assertTrue(r.fellOpenForUnresolvable);
    }

    /* -------------------------- dashboards -------------------------- */

    @Test
    public void dashboard_with_clean_tiles_returns_no_pii() {
        ds.put(
                "/clean.saikudash",
                dashboardJson(List.of(
                        tileJson("conn-1", "Cat", "Sch", "Sales"), tileJson("conn-1", "Cat", "Sch", "Inventory"))));
        cubes.putSchema("conn-1/Cat/Sch/Sales", cleanSchema());
        cubes.putSchema("conn-1/Cat/Sch/Inventory", cleanSchema());

        EmbedPiiInspector.Result r = inspector.inspect("dashboard", "/clean.saikudash", "admin", List.of());
        assertFalse(r.referencesPii);
        assertEquals("both cubes recorded for audit", 2, r.cubeIds.size());
    }

    @Test
    public void dashboard_with_one_pii_tile_returns_pii_but_still_walks_rest() {
        // One PII cube among multiple. The inspector must (a) return PII,
        // (b) still collect the FULL cube set for audit so an admin can
        // see "which of my 3 cubes is the problem."
        ds.put(
                "/mixed.saikudash",
                dashboardJson(List.of(
                        tileJson("conn-1", "Cat", "Sch", "Sales"),
                        tileJson("conn-1", "Cat", "Sch", "CustomerPii"),
                        tileJson("conn-1", "Cat", "Sch", "Inventory"))));
        AiSchema dirty = cleanSchema();
        AiSchema.Measure ssn = new AiSchema.Measure("SSN", "[Measures].[SSN]");
        ssn.pii = true;
        dirty.measures.put(AiSchema.key("SSN"), ssn);
        cubes.putSchema("conn-1/Cat/Sch/Sales", cleanSchema());
        cubes.putSchema("conn-1/Cat/Sch/CustomerPii", dirty);
        cubes.putSchema("conn-1/Cat/Sch/Inventory", cleanSchema());

        EmbedPiiInspector.Result r = inspector.inspect("dashboard", "/mixed.saikudash", "admin", List.of());
        assertTrue(r.referencesPii);
        assertEquals(3, r.cubeIds.size());
        assertTrue(r.cubeIds.stream().anyMatch(c -> c.endsWith("/CustomerPii")));
    }

    @Test
    public void dashboard_dedupes_cubes_across_tiles() {
        // Two tiles bind the SAME cube — the inspector should only schema-
        // lookup once and the cubeIds list should have one entry.
        ds.put(
                "/dupe.saikudash",
                dashboardJson(
                        List.of(tileJson("conn-1", "Cat", "Sch", "Sales"), tileJson("conn-1", "Cat", "Sch", "Sales"))));
        cubes.putSchema("conn-1/Cat/Sch/Sales", cleanSchema());

        EmbedPiiInspector.Result r = inspector.inspect("dashboard", "/dupe.saikudash", "admin", List.of());
        assertFalse(r.referencesPii);
        assertEquals(1, r.cubeIds.size());
        assertEquals(1, cubes.lookups);
    }

    @Test
    public void dashboard_with_text_only_tiles_returns_no_pii() {
        // No cube-bound tiles at all — the dashboard is just text /
        // markdown. Nothing to inspect; the result must be permissive
        // because there IS no PII column anywhere.
        ds.put(
                "/text.saikudash",
                "{\"id\":\"d\",\"name\":\"text\",\"version\":1,\"layout\":{\"cols\":12,\"tiles\":[]}}");
        EmbedPiiInspector.Result r = inspector.inspect("dashboard", "/text.saikudash", "admin", List.of());
        assertFalse(r.referencesPii);
        assertTrue(r.cubeIds.isEmpty());
    }

    @Test
    public void dashboard_with_one_cube_schema_lookup_failure_fails_closed_for_that_cube() {
        // Schema lookup throws for one cube. Treated as PII (fail-closed)
        // so the embed gate refuses rather than allows a possibly-PII
        // cube we can't inspect.
        ds.put(
                "/partial.saikudash",
                dashboardJson(List.of(
                        tileJson("conn-1", "Cat", "Sch", "Sales"), tileJson("conn-1", "Cat", "Sch", "Broken"))));
        cubes.putSchema("conn-1/Cat/Sch/Sales", cleanSchema());
        cubes.throwOn("conn-1/Cat/Sch/Broken");

        EmbedPiiInspector.Result r = inspector.inspect("dashboard", "/partial.saikudash", "admin", List.of());
        assertTrue("unresolvable cube must fail closed", r.referencesPii);
    }

    /* -------------------------- defaults -------------------------- */

    @Test
    public void unknown_kind_fails_closed() {
        ds.put("/x.weird", "{}");
        EmbedPiiInspector.Result r = inspector.inspect("schema", "/x.weird", "admin", List.of());
        assertTrue(r.referencesPii);
        assertTrue(r.fellOpenForUnresolvable);
    }

    @Test
    public void null_or_blank_path_is_permissive() {
        // The caller's upstream validation rejects blank paths before we
        // see them — don't double-gate something that can't even be
        // identified.
        assertFalse(inspector.inspect("query", null, "admin", List.of()).referencesPii);
        assertFalse(inspector.inspect("query", "", "admin", List.of()).referencesPii);
    }

    @Test
    public void null_infrastructure_is_permissive_so_callers_can_run_without_it() {
        // No datasource / cube metadata services wired (stub / test
        // context). Inspector is a no-op; the surrounding GRANT check
        // remains the load-bearing security check.
        EmbedPiiInspector noop = new EmbedPiiInspector(null, null);
        EmbedPiiInspector.Result r = noop.inspect("query", "/anything.saiku", "admin", List.of());
        assertFalse(r.referencesPii);
    }

    /* --------------------------- helpers --------------------------- */

    private static AiSchema cleanSchema() {
        return new AiSchema("c/cat/sch/Cube", "Cube", "[Cube]");
    }

    private static String thinQueryJson(String conn, String cat, String sch, String cube) {
        return "{\"name\":\"q\",\"type\":\"QUERYMODEL\",\"cube\":{\"connection\":\"" + conn + "\",\"catalog\":\"" + cat
                + "\",\"schema\":\"" + sch + "\",\"name\":\"" + cube + "\"}}";
    }

    private static String dashboardJson(List<String> tileJsons) {
        StringBuilder sb =
                new StringBuilder("{\"id\":\"d\",\"name\":\"\",\"version\":1,\"layout\":{\"cols\":12,\"tiles\":[");
        for (int i = 0; i < tileJsons.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(tileJsons.get(i));
        }
        sb.append("]}}");
        return sb.toString();
    }

    private static String tileJson(String conn, String cat, String sch, String cube) {
        return "{\"id\":\"t\",\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"type\":\"chart\",\"cube\":{\"connectionName\":\"" + conn
                + "\",\"catalog\":\"" + cat + "\",\"schema\":\"" + sch + "\",\"cubeName\":\"" + cube + "\"}}";
    }

    /* --------------------------- stubs --------------------------- */

    private static class StubDatasourceService extends DatasourceService {
        private final Map<String, String> files = new HashMap<>();
        private final java.util.Set<String> throwPaths = new java.util.HashSet<>();

        void put(String path, String content) {
            files.put(path, content);
        }

        void throwOn(String path) {
            throwPaths.add(path);
        }

        @Override
        public String getFileData(String path, String username, List<String> roles) {
            if (throwPaths.contains(path)) throw new RuntimeException("simulated unreadable");
            return files.get(path);
        }
    }

    private static class StubCubeMetadataService implements AiCubeMetadataService {
        private final Map<String, AiSchema> schemas = new HashMap<>();
        private final java.util.Set<String> throwKeys = new java.util.HashSet<>();
        int lookups = 0;

        void putSchema(String cubeKey, AiSchema schema) {
            schemas.put(cubeKey, schema);
        }

        void throwOn(String cubeKey) {
            throwKeys.add(cubeKey);
        }

        @Override
        public AiSchema getSchema(AiCubeRef ref) {
            lookups++;
            String key =
                    ref.getConnectionName() + "/" + ref.getCatalog() + "/" + ref.getSchema() + "/" + ref.getCubeName();
            if (throwKeys.contains(key)) throw new RuntimeException("simulated cube failure");
            return schemas.get(key);
        }
    }
}
