/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.lineage;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.database.dto.MondrianSchema;
import org.saiku.repository.IRepositoryObject;
import org.saiku.repository.RepositoryFileObject;
import org.saiku.service.datasource.DatasourceService;

/** Unit tests for {@link MeasureLineageService}, see saiku#1120. */
public class MeasureLineageServiceTest {

    private StubDatasourceService stub;
    private MeasureLineageService service;

    @Before
    public void setUp() {
        stub = new StubDatasourceService();
        service = new MeasureLineageService();
        service.setDatasourceService(stub);
    }

    private List<LineageDependent> find(String measure) {
        return service.findDependents(measure, "admin", List.of("ROLE_ADMIN"));
    }

    /** Issue test plan: "A measure used in 3 dashboards + 2 saved queries lists all 5". */
    @Test
    public void findsAcrossThreeDashboardsAndTwoSavedQueries() {
        stub.putFile("/queries/q1.saiku", savedQueryJson("q1", "[Measures].[Store Sales]"), 1000L);
        stub.putFile("/queries/q2.saiku", savedQueryJson("q2", "[Measures].[Store Sales]"), 2000L);
        // Noise: a saved query that does NOT reference the target measure.
        stub.putFile("/queries/q3.saiku", savedQueryJson("q3", "[Measures].[Unit Sales]"), 3000L);

        stub.putFile("/dashboards/d1.saikudash", kpiDashboard("d1", "Dash One", "Store Sales"), 4000L);
        stub.putFile("/dashboards/d2.saikudash", kpiDashboard("d2", "Dash Two", "Store Sales"), 5000L);
        stub.putFile("/dashboards/d3.saikudash", inlineMeasureDashboard("d3", "Dash Three", "Store Sales"), 6000L);
        // Noise: a dashboard that does NOT reference the target measure.
        stub.putFile("/dashboards/d4.saikudash", kpiDashboard("d4", "Dash Four", "Unit Sales"), 7000L);

        List<LineageDependent> found = find("[Measures].[Store Sales]");

        assertEquals(5, found.size());
        assertEquals(2, countKind(found, "saved-query"));
        assertEquals(3, countKind(found, "dashboard"));
    }

    @Test
    public void noMatchesReturnsEmptyList() {
        stub.putFile("/queries/q1.saiku", savedQueryJson("q1", "[Measures].[Unit Sales]"), 1000L);
        assertTrue(find("[Measures].[Store Sales]").isEmpty());
    }

    @Test
    public void legacyXmlSavedQueryMatchesOnRawMdxText() {
        stub.putFile(
                "/queries/legacy.saiku",
                "<?xml version=\"1.0\"?><Query name=\"x\" type=\"MDX\"><MDX>SELECT {[Measures].[Store Sales]} ON COLUMNS FROM [Sales]</MDX></Query>",
                1000L);
        List<LineageDependent> found = find("[Measures].[Store Sales]");
        assertEquals(1, found.size());
        assertEquals("saved-query", found.get(0).kind);
    }

    @Test
    public void dashboardReferenceTileResolvesReferencedSavedQuery() {
        stub.putFile("/queries/referenced.saiku", savedQueryJson("referenced", "[Measures].[Store Sales]"), 1000L);
        String dash = "{\"id\":\"d1\",\"name\":\"Ref Dash\",\"layout\":{\"cols\":12,\"tiles\":["
                + "{\"id\":\"t1\",\"x\":0,\"y\":0,\"w\":4,\"h\":4,\"type\":\"chart\","
                + "\"query\":{\"kind\":\"reference\",\"path\":\"/queries/referenced.saiku\"}}" + "]}}";
        stub.putFile("/dashboards/refdash.saikudash", dash, 2000L);

        List<LineageDependent> found = find("[Measures].[Store Sales]");

        // The referenced saved query counts once, and the dashboard that points at it counts once.
        assertEquals(2, found.size());
        assertEquals(1, countKind(found, "saved-query"));
        assertEquals(1, countKind(found, "dashboard"));
    }

    @Test
    public void calculatedMemberFormulaReferencingTargetIsADependent() {
        String schemaXml = "<Schema name='FoodMart'>"
                + "<Cube name='Sales'>"
                + "<CalculatedMember name='Profit' dimension='Measures'>"
                + "<Formula>[Measures].[Store Sales] - [Measures].[Store Cost]</Formula>"
                + "</CalculatedMember>"
                + "</Cube></Schema>";
        stub.putSchema("FoodMart", "/datasources/FoodMart.xml", schemaXml);

        List<LineageDependent> found = find("[Measures].[Store Sales]");

        assertEquals(1, found.size());
        assertEquals("calc-measure", found.get(0).kind);
        assertEquals("Profit", found.get(0).name);
    }

    @Test
    public void calculatedMemberDoesNotListItselfAsADependent() {
        String schemaXml = "<Schema name='FoodMart'>"
                + "<Cube name='Sales'>"
                + "<CalculatedMember name='Store Sales' dimension='Measures'>"
                + "<Formula>[Measures].[Store Cost] * 1.1</Formula>"
                + "</CalculatedMember>"
                + "</Cube></Schema>";
        stub.putSchema("FoodMart", "/datasources/FoodMart.xml", schemaXml);

        List<LineageDependent> found = find("[Measures].[Store Sales]");

        assertTrue(found.isEmpty());
    }

    /** Issue test plan: "Levels / dimensions lineage works the same way". */
    @Test
    public void dimensionLineageMatchesAnyHierarchyOrLevelUnderIt() {
        stub.putFile(
                "/dashboards/d1.saikudash",
                inlineAxisDashboard("d1", "Store dash", "Store", "Stores", "Store City"),
                1000L);

        List<LineageDependent> found = find("[Store]");

        assertEquals(1, found.size());
        assertEquals("dashboard", found.get(0).kind);
    }

    @Test
    public void levelLineageRequiresExactDimensionHierarchyAndLevel() {
        stub.putFile(
                "/dashboards/d1.saikudash",
                inlineAxisDashboard("d1", "Store dash", "Store", "Stores", "Store City"),
                1000L);
        stub.putFile(
                "/dashboards/d2.saikudash",
                inlineAxisDashboard("d2", "Store dash 2", "Store", "Stores", "Store Country"),
                2000L);

        List<LineageDependent> found = find("[Store].[Stores].[Store City]");

        assertEquals(1, found.size());
        assertEquals("/dashboards/d1.saikudash", found.get(0).path);
    }

    @Test
    public void hierarchyLineageMatchesAnyLevelUnderIt() {
        stub.putFile(
                "/dashboards/d1.saikudash",
                inlineAxisDashboard("d1", "Store dash", "Store", "Stores", "Store City"),
                1000L);
        stub.putFile(
                "/dashboards/d2.saikudash", inlineAxisDashboard("d2", "Time dash", "Time", "Time", "Month"), 2000L);

        List<LineageDependent> found = find("[Store].[Stores]");

        assertEquals(1, found.size());
    }

    @Test
    public void rebuildsFromScratchOnEveryCall() {
        // "Index rebuilds on save (not just on cron)" — there is no cache, so a file added between
        // two calls is picked up immediately.
        assertTrue(find("[Measures].[Store Sales]").isEmpty());

        stub.putFile("/queries/new.saiku", savedQueryJson("new", "[Measures].[Store Sales]"), 1000L);

        assertEquals(1, find("[Measures].[Store Sales]").size());
    }

    private static long countKind(List<LineageDependent> found, String kind) {
        return found.stream().filter(d -> kind.equals(d.kind)).count();
    }

    private static String savedQueryJson(String name, String measureUniqueName) {
        return "{\"name\":\"" + name + "\",\"cube\":{\"name\":\"Sales\"},\"queryType\":\"OLAP\",\"type\":\"MDX\","
                + "\"mdx\":\"SELECT {" + measureUniqueName + "} ON COLUMNS FROM [Sales]\"}";
    }

    private static String kpiDashboard(String id, String name, String measureCaption) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"layout\":{\"cols\":12,\"tiles\":["
                + "{\"id\":\"k1\",\"x\":0,\"y\":0,\"w\":3,\"h\":3,\"type\":\"kpi\","
                + "\"kpi\":{\"measure\":\"" + measureCaption + "\",\"measureCaption\":\"" + measureCaption + "\"}}"
                + "]}}";
    }

    private static String inlineMeasureDashboard(String id, String name, String measureName) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"layout\":{\"cols\":12,\"tiles\":["
                + "{\"id\":\"c1\",\"x\":0,\"y\":0,\"w\":6,\"h\":6,\"type\":\"chart\","
                + "\"query\":{\"kind\":\"inline\",\"body\":{\"measures\":[{\"name\":\"" + measureName + "\"}],"
                + "\"rows\":[],\"columns\":[],\"filters\":[]}}}" + "]}}";
    }

    private static String inlineAxisDashboard(
            String id, String name, String dimension, String hierarchy, String level) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"layout\":{\"cols\":12,\"tiles\":["
                + "{\"id\":\"c1\",\"x\":0,\"y\":0,\"w\":6,\"h\":6,\"type\":\"chart\","
                + "\"query\":{\"kind\":\"inline\",\"body\":{\"measures\":[{\"name\":\"Store Sales\"}],"
                + "\"rows\":[{\"dimension\":\"" + dimension + "\",\"hierarchy\":\"" + hierarchy + "\",\"level\":\""
                + level + "\"}],\"columns\":[],\"filters\":[]}}}" + "]}}";
    }

    private static final class StubDatasourceService extends DatasourceService {
        final Map<String, String> files = new LinkedHashMap<>();
        final Map<String, Long> modified = new HashMap<>();
        final List<MondrianSchema> schemas = new ArrayList<>();
        final Map<String, String> schemaContent = new HashMap<>();

        void putFile(String path, String content, long modifiedAt) {
            files.put(path, content);
            modified.put(path, modifiedAt);
        }

        void putSchema(String name, String path, String xml) {
            MondrianSchema s = new MondrianSchema();
            s.setName(name);
            s.setPath(path);
            schemas.add(s);
            schemaContent.put(path, xml);
        }

        @Override
        public List<IRepositoryObject> getFiles(List<String> type, String username, List<String> roles) {
            List<IRepositoryObject> out = new ArrayList<>();
            for (String path : files.keySet()) {
                for (String ft : type) {
                    if (path.endsWith(ft)) {
                        String filename = path.substring(path.lastIndexOf('/') + 1);
                        out.add(new RepositoryFileObject(
                                filename, "#" + path, ft, path, List.of(), null, modified.getOrDefault(path, 0L)));
                        break;
                    }
                }
            }
            return out;
        }

        @Override
        public String getFileData(String path, String username, List<String> roles) {
            return files.get(path);
        }

        @Override
        public List<MondrianSchema> getAvailableSchema() {
            return schemas;
        }

        @Override
        public String getInternalFileData(String path) {
            return schemaContent.get(path);
        }
    }
}
