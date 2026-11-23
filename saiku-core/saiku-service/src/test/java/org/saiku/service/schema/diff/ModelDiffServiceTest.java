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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * saiku#1434 — end-to-end behaviour, and the measurable half of the acceptance criteria:
 *
 * <ul>
 *   <li><b>Exhaustive</b> — every saved query / dashboard referencing a removed member is reported.
 *   <li><b>&lt; 5% false positives</b>, measured rather than asserted by hand: a FoodMart-shaped
 *       repository is filled with queries that all resolve, a set of <em>intentional renames</em>
 *       is applied to the model, and every reported reference is checked to see whether it really
 *       no longer resolves. The false-positive rate is computed as
 *       (reported ∧ still-resolvable) / reported and asserted below the budget. The measurement
 *       runs over many random rename sets, because a hand-picked rename set is exactly how a
 *       false-positive budget gets quietly missed.
 * </ul>
 */
class ModelDiffServiceTest {

    @TempDir
    Path work;

    private final ModelDiffService service = new ModelDiffService();

    private static final String SCHEMA_V1 = "<Schema name='FoodMart'><Cube name='Sales'>"
            + "<Measure name='Unit Sales' column='unit_sales' aggregator='sum'/>"
            + "<Measure name='Store Sales' column='store_sales' aggregator='sum'/>"
            + "<Measure name='Store Cost' column='store_cost' aggregator='sum'/>"
            + "<Measure name='Profit' column='profit' aggregator='sum'/>"
            + "<Dimension name='Product'><Hierarchy><Level name='Product Family' column='pf'/>"
            + "<Level name='Product Name' column='pn'/></Hierarchy></Dimension>"
            + "<Dimension name='Store'><Hierarchy><Level name='Store City' column='sc'/>"
            + "<Level name='Store Country' column='sn'/></Hierarchy></Dimension>"
            + "<Dimension name='Time'><Hierarchy><Level name='Quarter' column='q'/>"
            + "<Level name='Month' column='m'/></Hierarchy></Dimension>"
            + "</Cube></Schema>";

    private static final List<String> MEASURES = List.of("Unit Sales", "Store Sales", "Store Cost", "Profit");
    private static final List<String> DIMENSIONS = List.of("Product", "Store", "Time");
    private static final List<String> LEVELS =
            List.of("Product Family", "Product Name", "Store City", "Store Country", "Quarter", "Month");

    private Path repository;

    @BeforeEach
    void setUp() throws IOException {
        repository = Files.createDirectories(work.resolve("repository/data"));
    }

    /** Write a repository of saved queries and dashboards that all resolve against the model. */
    private void writeHealthyRepository() throws IOException {
        for (int i = 0; i < MEASURES.size(); i++) {
            write(
                    "homes/admin/query" + i + ".saiku",
                    "{\"name\":\"q" + i + "\",\"cube\":{\"name\":\"Sales\"},"
                            + "\"mdx\":\"SELECT {" + measuresOf(MEASURES.get(i)) + "} ON COLUMNS, "
                            + dimensionLevelsOf(
                                    DIMENSIONS.get(i % DIMENSIONS.size()), LEVELS.get((i % DIMENSIONS.size()) * 2))
                            + " ON ROWS FROM [Sales]\"}");
        }
        for (int i = 0; i < DIMENSIONS.size(); i++) {
            write(
                    "dashboards/dash" + i + ".saikudash",
                    "{\"layout\":{\"tiles\":[{\"id\":\"tile-" + i + "\","
                            + "\"type\":\"chart\",\"cube\":{\"cubeName\":\"Sales\"},\"query\":{\"kind\":\"inline\","
                            + "\"body\":{\"cube\":{\"cubeName\":\"Sales\"},"
                            + "\"measures\":[{\"name\":\"" + MEASURES.get(i) + "\"}],"
                            + "\"rows\":[{\"dimension\":\"" + DIMENSIONS.get(i) + "\",\"level\":\""
                            + LEVELS.get(i * 2) + "\"}]}}}]}}");
            write(
                    "homes/admin/app" + i + ".saikuapp",
                    "{\"pages\":[{\"tiles\":[{\"id\":\"kpi-" + i + "\","
                            + "\"cube\":{\"cubeName\":\"Sales\"},\"kpi\":{\"measure\":\"" + MEASURES.get(i)
                            + "\"}}]}]}");
        }
    }

    private static String measuresOf(String measure) {
        return "[Measures].[" + measure + "]";
    }

    private static String dimensionLevelsOf(String dimension, String level) {
        return "{" + dimension + "}.[" + level + "]";
    }

    private void write(String relative, String content) throws IOException {
        Path file = repository.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void anUnchangedModelOverAHealthyRepositoryIsCompletelyClean() throws IOException {
        writeHealthyRepository();
        ModelDiffReport report = service.diffStrings(SCHEMA_V1, SCHEMA_V1);
        assertTrue(report.isClean(), () -> "expected a clean report, got:\n" + report.markdown());
        assertEquals(0, report.affectedFileCount());
    }

    @Test
    void aRemovedMeasureBreaksEveryFileThatReferencesIt() throws IOException {
        writeHealthyRepository();
        String after = SCHEMA_V1.replace("<Measure name='Store Cost' column='store_cost' aggregator='sum'/>", "");
        ModelDiffReport report =
                service.diff(service.parse(SCHEMA_V1, "a.xml"), service.parse(after, "b.xml"), repository);
        assertTrue(report.hasBreakingChanges());
        // Every binding style is exhaustive: the MDX saved query, the dashboard tile and the
        // App Builder KPI tile all reference Store Cost and all three must be reported.
        List<String> files = report.brokenReferences().stream()
                .map(BrokenReference::file)
                .sorted()
                .toList();
        assertEquals(
                List.of("dashboards/dash2.saikudash", "homes/admin/app2.saikuapp", "homes/admin/query2.saiku"), files);
        assertTrue(report.markdown().contains("[Measures].[Store Cost]"));
    }

    @Test
    void aRenamedMeasureIsReportedAsARenameAndStillFlagsItsOldReferences() throws IOException {
        writeHealthyRepository();
        String after = SCHEMA_V1.replace("name='Store Cost'", "name='Cost of Goods'");
        ModelDiffReport report =
                service.diff(service.parse(SCHEMA_V1, "a.xml"), service.parse(after, "b.xml"), repository);
        assertTrue(report.changes().stream()
                .anyMatch(c -> c.type() == ModelChange.Type.RENAMED && "Cost of Goods".equals(c.toName())));
        // The rename is only half the story: the MDX saved query, the dashboard tile and the KPI
        // tile all still name Store Cost, and all three are broken.
        assertEquals(3, report.brokenReferences().size());
    }

    @Test
    void theMarkdownReportMatchesTheDocumentedShape() throws IOException {
        write(
                "homes/admin/exec-dashboard.saikudash",
                "{\"layout\":{\"tiles\":[{\"id\":\"Family Chart\","
                        + "\"cube\":{\"cubeName\":\"Sales\"},\"query\":{\"body\":{\"rows\":[{\"dimension\":\"Product\","
                        + "\"level\":\"Product Family\"}]}}}]}}");
        String after = SCHEMA_V1
                .replace("name='Store Cost'", "name='Cost of Goods'")
                .replace("name='Product Family'", "name='Product Line'");
        ModelDiffReport report =
                service.diff(service.parse(SCHEMA_V1, "a.xml"), service.parse(after, "b.xml"), repository);
        String markdown = report.markdown();
        assertTrue(markdown.contains("## FoodMart / Sales"), markdown);
        assertTrue(markdown.contains("- Renamed measure: Store Cost → Cost of Goods"), markdown);
        assertTrue(markdown.contains("- Renamed level: Product Family → Product Line"), markdown);
        assertTrue(markdown.contains("## Broken references (1 file affected)"), markdown);
        // The file still selects the OLD level name — that is the whole point of the report.
        assertTrue(
                markdown.contains("homes/admin/exec-dashboard.saikudash — Family Chart — [Product].[Product Family]"),
                markdown);
    }

    @Test
    void anEmptyDiffRendersAReadableReport() {
        String markdown = service.diffStrings(SCHEMA_V1, SCHEMA_V1).markdown();
        assertTrue(markdown.contains("No model changes and no broken references."), markdown);
    }

    @Test
    void theFalsePositiveRateStaysUnderFivePercentAcrossRandomIntentionalRenames() throws IOException {
        // Measured, not asserted by hand: a false-positive budget is only meaningful if the
        // measurement is automated, over enough rename sets to be worth something.
        writeHealthyRepository();
        Random random = new Random(20260930L);
        int reported = 0;
        int falsePositives = 0;
        int rounds = 60;
        for (int round = 0; round < rounds; round++) {
            String after = SCHEMA_V1;
            List<String> renamed = new ArrayList<>();
            after = renameOne(after, pick(MEASURES, random), "Measure " + round, renamed, true);
            after = renameOne(after, pick(LEVELS, random), "Level " + round, renamed, true);
            ModelSnapshot afterModel = service.parse(after, "after.xml");
            ModelDiffReport report = service.diff(service.parse(SCHEMA_V1, "before.xml"), afterModel, repository);
            for (BrokenReference reference : report.brokenReferences()) {
                reported++;
                // A false positive is a report the after-model would have resolved.
                if (reference.kind() == ModelElementKind.MEASURE) {
                    if (afterModel.hasMeasure(reference.cube(), reference.name())) {
                        falsePositives++;
                    }
                } else if (reference.kind() == ModelElementKind.LEVEL) {
                    if (afterModel.hasLevel(reference.cube(), reference.dimension(), reference.name())) {
                        falsePositives++;
                    }
                } else if (reference.kind() == ModelElementKind.DIMENSION) {
                    if (afterModel.hasDimension(reference.cube(), reference.name())) {
                        falsePositives++;
                    }
                } else if (afterModel.hasCube(reference.name())) {
                    falsePositives++;
                }
            }
        }
        assertTrue(reported > 0, "the fixture must actually reference the renamed members");
        double rate = (double) falsePositives / reported;
        assertTrue(
                rate < 0.05,
                "false-positive rate " + rate + " over " + reported + " reports across " + rounds + " rename rounds");
    }

    @Test
    void aRenameTheAuthorAlsoPropagatedProducesNoFindingsAtAll() throws IOException {
        // The happy path: the PR renames the measure AND updates every query in the same commit.
        write(
                "homes/admin/q.saiku",
                "{\"name\":\"q\",\"cube\":{\"name\":\"Sales\"},"
                        + "\"mdx\":\"SELECT {[Measures].[Cost of Goods]} ON COLUMNS FROM [Sales]\"}");
        String after = SCHEMA_V1.replace("name='Store Cost'", "name='Cost of Goods'");
        ModelDiffReport report =
                service.diff(service.parse(SCHEMA_V1, "a.xml"), service.parse(after, "b.xml"), repository);
        assertTrue(report.isClean(), report::markdown);
        assertEquals(1, report.filesScanned());
    }

    @Test
    void ossieModelsDiffAndReportTheSameWay() {
        String before = "semantic_model:\n- name: Flights\n  datasets:\n  - name: flight\n    fields:\n"
                + "    - name: dep_delay_min\n  metrics:\n  - name: flight_count\n";
        String after = before.replace("name: dep_delay_min", "name: departure_delay");
        ModelDiffReport report = service.diffStrings(before, after);
        assertEquals(ModelFormat.OSSIE_YAML, report.format());
        assertEquals(1, report.changes().size());
        assertEquals(ModelChange.Type.RENAMED, report.changes().get(0).type());
        assertTrue(report.markdown().contains("Renamed level: dep_delay_min → departure_delay"), report.markdown());
    }

    @Test
    void filesAreDiffedAndAMissingOneIsReportedNotIgnored() throws IOException {
        Path before = work.resolve("before.xml");
        Path after = work.resolve("after.xml");
        Files.writeString(before, SCHEMA_V1);
        Files.writeString(after, SCHEMA_V1);
        assertTrue(service.diffFiles(before, after, repository).isClean());

        Files.writeString(after, SCHEMA_V1.replace("name='Profit'", "name='Margin'"));
        ModelDiffReport report = service.diffFiles(before, after, repository);
        assertEquals(1, report.changes().size());
        assertEquals(ModelChange.Type.RENAMED, report.changes().get(0).type());

        ModelDiffException e = org.junit.jupiter.api.Assertions.assertThrows(
                ModelDiffException.class, () -> service.diffFiles(before, work.resolve("nope.xml"), repository));
        assertEquals(ModelDiffException.Reason.MALFORMED, e.reason());
    }

    @Test
    void reportSummariesAgreeWithTheirContents() {
        ModelDiffReport empty = new ModelDiffReport(ModelFormat.MONDRIAN_XML, "a", "b", List.of(), List.of(), 0);
        assertFalse(empty.hasBreakingChanges());
        assertTrue(empty.isClean());

        ModelElement measure = new ModelElement(ModelElementKind.MEASURE, "Sales", null, "Unit Sales", "");
        ModelDiffReport breaking = new ModelDiffReport(
                ModelFormat.MONDRIAN_XML, "a", "b", List.of(ModelChange.removed(measure)), List.of(), 3);
        assertTrue(breaking.hasBreakingChanges());
        assertEquals(1, breaking.breakingChangeCount());
        // A rename is breaking by definition even when every reference was updated in the same
        // commit, so "clean" is about orphaned references, not about the absence of churn.
        assertTrue(breaking.isClean(), "nothing in the repository is broken here");
    }

    private static String pick(List<String> candidates, Random random) {
        return candidates.get(random.nextInt(candidates.size()));
    }

    private static String renameOne(String schema, String from, String to, List<String> renamed, boolean unused) {
        if (schema.contains("name='" + from + "'")) {
            renamed.add(from);
            return schema.replace("name='" + from + "'", "name='" + to + "'");
        }
        return schema;
    }

    @Test
    void renameHelperIsARoundTripOnTheFixture() {
        // Guards the helper the false-positive measurement depends on: if the rename it applies
        // silently no-ops, the measurement would pass by asserting nothing.
        List<String> renamed = new ArrayList<>();
        String after = renameOne(SCHEMA_V1, "Store Cost", "Cost of Goods", renamed, true);
        assertEquals(List.of("Store Cost"), renamed);
        assertTrue(after.toLowerCase(Locale.ROOT).contains("cost of goods"));
        assertFalse(after.contains("Store Cost"));
    }
}
