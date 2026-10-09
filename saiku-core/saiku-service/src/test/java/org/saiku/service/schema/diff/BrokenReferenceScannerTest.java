/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * saiku#1434 — the reference walker. Two properties matter and pull in opposite directions:
 *
 * <ul>
 *   <li><b>Exhaustive</b> — every saved query, dashboard and app that references a gone member is
 *       found. A miss here is a broken dashboard shipping to production.
 *   <li><b>Quiet</b> — a reference that still resolves is never reported, whatever the
 *       serialisation, letter case, or which of several schemas the file belongs to. The
 *       acceptance criterion is a &lt; 5% false-positive rate against intentional renames.
 * </ul>
 */
class BrokenReferenceScannerTest {

    @TempDir
    Path repository;

    private final ModelDiffService service = new ModelDiffService();

    private static final String SCHEMA = "<Schema name='FoodMart'><Cube name='Sales'>"
            + "<Measure name='Unit Sales' column='unit_sales' aggregator='sum'/>"
            + "<Measure name='Store Sales' column='store_sales' aggregator='sum'/>"
            + "<Dimension name='Product'><Hierarchy><Level name='Product Family' column='pf'/>"
            + "<Level name='Product Name' column='pn'/></Hierarchy></Dimension>"
            + "<Dimension name='Store'><Hierarchy><Level name='Store City' column='sc'/></Hierarchy></Dimension>"
            + "</Cube></Schema>";

    private ModelSnapshot model;

    @BeforeEach
    void setUp() {
        model = service.parse(SCHEMA, "FoodMart.xml");
    }

    private void write(String relative, String content) throws IOException {
        Path file = repository.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void aCleanRepositoryReportsNothing() throws IOException {
        write(
                "homes/admin/sales.saiku",
                "{\"name\":\"sales\",\"cube\":{\"name\":\"Sales\"},"
                        + "\"mdx\":\"SELECT {[Measures].[Unit Sales]} ON COLUMNS FROM [Sales]\"}");
        write(
                "homes/admin/dash.saikudash",
                "{\"layout\":{\"tiles\":[{\"id\":\"t1\",\"type\":\"kpi\","
                        + "\"cube\":{\"cubeName\":\"Sales\"},\"kpi\":{\"measure\":\"Unit Sales\"}}]}}");
        assertTrue(service.validate(repository, model).isEmpty());
    }

    @Test
    void findsARemovedMeasureInAnMdxSavedQuery() throws IOException {
        write(
                "homes/admin/FoodMartTrend.saiku",
                "{\"name\":\"trend\",\"cube\":{\"name\":\"Sales\"},"
                        + "\"mdx\":\"SELECT {[Measures].[Unit Sales], [Measures].[Gross Margin]} ON COLUMNS FROM [Sales]\"}");
        List<BrokenReference> found = service.validate(repository, model);
        assertEquals(1, found.size());
        BrokenReference reference = found.get(0);
        assertEquals("homes/admin/FoodMartTrend.saiku", reference.file());
        assertEquals(ModelElementKind.MEASURE, reference.kind());
        assertEquals("Gross Margin", reference.name());
        assertEquals(BrokenReference.MISSING_MEASURE, reference.reason());
    }

    @Test
    void findsARemovedLevelInADashboardTile() throws IOException {
        write(
                "homes/admin/exec.saikudash",
                "{\"layout\":{\"tiles\":[{\"id\":\"family-chart\","
                        + "\"cube\":{\"cubeName\":\"Sales\"},\"query\":{\"kind\":\"inline\",\"body\":{"
                        + "\"measures\":[{\"name\":\"Unit Sales\"}],"
                        + "\"rows\":[{\"dimension\":\"Product\",\"level\":\"Product Line\"}]}}}]}}");
        List<BrokenReference> found = service.validate(repository, model);
        assertEquals(1, found.size());
        assertEquals(ModelElementKind.LEVEL, found.get(0).kind());
        assertEquals("Product", found.get(0).dimension());
        assertEquals("Product Line", found.get(0).name());
        assertEquals("family-chart", found.get(0).location());
    }

    @Test
    void scansEveryFileTypeThatBindsToACube() throws IOException {
        write(
                "homes/admin/q.saiku",
                "{\"cube\":{\"name\":\"Sales\"}," + "\"mdx\":\"SELECT {[Measures].[Ghost]} ON COLUMNS FROM [Sales]\"}");
        write(
                "dashboards/d.saikudash",
                "{\"tiles\":[{\"id\":\"a\",\"cube\":{\"cubeName\":\"Sales\"},"
                        + "\"kpi\":{\"measure\":\"Ghost KPI\"}}]}");
        write(
                "homes/admin/app.saikuapp",
                "{\"pages\":[{\"tiles\":[{\"id\":\"b\",\"cube\":{\"cubeName\":\"Sales\"},"
                        + "\"kpi\":{\"measure\":\"Ghost App\"}}]}]}");
        List<BrokenReference> found = service.validate(repository, model);
        assertEquals(3, found.size());
        assertEquals(
                List.of("Ghost", "Ghost App", "Ghost KPI"),
                found.stream().map(BrokenReference::name).sorted().toList());
    }

    @Test
    void aMissingDimensionIsReportedOnceRatherThanPerLevel() throws IOException {
        write(
                "d.saikudash",
                "{\"tiles\":[{\"id\":\"t\",\"cube\":{\"cubeName\":\"Sales\"},"
                        + "\"query\":{\"body\":{\"rows\":[{\"dimension\":\"Ghost Dim\",\"level\":\"L1\"},"
                        + "{\"dimension\":\"Ghost Dim\",\"level\":\"L2\"}]}}}]}");
        List<BrokenReference> found = service.validate(repository, model);
        assertEquals(1, found.size());
        assertEquals(ModelElementKind.DIMENSION, found.get(0).kind());
    }

    @Test
    void anUnknownCubeIsReportedButItsMembersAreNotCascaded() throws IOException {
        // A repository routinely holds several schemas. Flagging every member of a cube that
        // belongs to a different datasource would be noise, not a finding.
        write(
                "homes/admin/other.saiku",
                "{\"cube\":{\"name\":\"Warehouse\"},"
                        + "\"mdx\":\"SELECT {[Measures].[Store Cost]} ON COLUMNS FROM [Warehouse]\"}");
        List<BrokenReference> found = service.validate(repository, model);
        assertEquals(1, found.size());
        assertEquals(BrokenReference.UNKNOWN_CUBE, found.get(0).reason());
        assertEquals("Warehouse", found.get(0).name());
    }

    @Test
    void lookupIsCaseInsensitiveLikeMondrian() throws IOException {
        write(
                "q.saiku",
                "{\"cube\":{\"name\":\"sales\"},"
                        + "\"mdx\":\"select {[measures].[unit sales]} on columns from [sales]\"}");
        assertTrue(service.validate(repository, model).isEmpty());
    }

    @Test
    void aLevelReachableThroughADimensionUsageResolves() throws IOException {
        String withUsage = "<Schema name='FoodMart'>"
                + "<Dimension name='Product'><Hierarchy><Level name='Product Family' column='pf'/>"
                + "</Hierarchy></Dimension>"
                + "<Cube name='Sales'><DimensionUsage name='Product' source='Product'/>"
                + "<Measure name='Unit Sales' column='us' aggregator='sum'/></Cube></Schema>";
        ModelSnapshot usageModel = service.parse(withUsage, "FoodMart.xml");
        write(
                "q.saiku",
                "{\"cube\":{\"name\":\"Sales\"},"
                        + "\"mdx\":\"SELECT {[Product].[Product Family]} ON ROWS FROM [Sales]\"}");
        assertTrue(service.validate(repository, usageModel).isEmpty());
    }

    @Test
    void nonJsonFilesAreSkippedRatherThanReported() throws IOException {
        write("homes/admin/broken.saiku", "{ this is not json");
        assertTrue(service.validate(repository, model).isEmpty());
    }

    @Test
    void anEmptyOrMissingRepositoryScansCleanly() throws IOException {
        assertTrue(service.validate(repository, model).isEmpty());
        assertTrue(service.validate(repository.resolve("nope"), model).isEmpty());
        assertTrue(service.validate(null, model).isEmpty());
        Files.createDirectories(repository.resolve("homes/empty"));
        assertTrue(service.validate(repository, model).isEmpty());
    }

    @Test
    void duplicateReferencesInOneFileAreReportedOnce() throws IOException {
        write(
                "d.saikudash",
                "{\"tiles\":["
                        + "{\"id\":\"t1\",\"cube\":{\"cubeName\":\"Sales\"},\"kpi\":{\"measure\":\"Ghost\"}},"
                        + "{\"id\":\"t2\",\"cube\":{\"cubeName\":\"Sales\"},\"query\":{\"body\":{\"measures\":[{\"name\":\"Ghost\"}]}}}"
                        + "]}");
        List<BrokenReference> found = service.validate(repository, model);
        assertEquals(2, found.size(), "one per tile, not one per mention");
        assertEquals(
                List.of("t1", "t2"),
                found.stream().map(BrokenReference::location).toList());
    }

    @Test
    void aFilterMemberReferenceIsChecked() throws IOException {
        write(
                "d.saikudash",
                "{\"tiles\":[{\"id\":\"t\",\"cube\":{\"cubeName\":\"Sales\"},"
                        + "\"query\":{\"body\":{\"filters\":[{\"dimension\":\"Store\",\"member\":"
                        + "\"[Store].[Store Ghost]\"}]}}}]}");
        List<BrokenReference> found = service.validate(repository, model);
        assertEquals(1, found.size());
        assertEquals("Store Ghost", found.get(0).name());
    }

    @Test
    void mdxFormRendersTheGreppableReference() {
        assertEquals(
                "[Measures].[Unit Sales]",
                new BrokenReference("f", "l", ModelElementKind.MEASURE, "Sales", null, "Unit Sales", "r").mdxForm());
        assertEquals(
                "[Product].[Product Family]",
                new BrokenReference("f", "l", ModelElementKind.LEVEL, "Sales", "Product", "Product Family", "r")
                        .mdxForm());
        assertFalse(new BrokenReference("f", "l", ModelElementKind.MEASURE, "Sales", null, "x", "r")
                .describe()
                .isBlank());
    }
}
