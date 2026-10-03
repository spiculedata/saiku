/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.digest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.schedule.alert.MeasureValueReader;

/** The period-over-period read path of the insight digest (saiku#1119). */
public class InsightDigestBuilderTest {

    /** Records every read so the test can assert what was asked of the cube, and when. */
    private static final class RecordingReader implements MeasureValueReader {
        final List<AiFilterSelection> seen = new ArrayList<>();
        final List<String> measures = new ArrayList<>();
        Map<String, Double> current = Map.of();
        Map<String, Double> previous = Map.of();
        String failMeasure;

        @Override
        public double readMeasure(AiCubeRef cube, String measure, List<AiFilterSelection> filters) throws Exception {
            if (measure.equals(failMeasure)) {
                throw new IllegalStateException("cube unavailable");
            }
            measures.add(measure);
            seen.add(filters.get(filters.size() - 1));
            boolean isPrevious = PeriodSpec.PREVIOUS_PRESET.equals(
                    filters.get(filters.size() - 1).getValue());
            return isPrevious ? previous.getOrDefault(measure, 0d) : current.getOrDefault(measure, 0d);
        }
    }

    private static Map<String, Object> measure(String name, String label, boolean withPeriod) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cube", "conn/cat/schema/Sales");
        m.put("measure", name);
        if (label != null) {
            m.put("label", label);
        }
        if (withPeriod) {
            Map<String, Object> period = new LinkedHashMap<>();
            period.put("dimension", "Time");
            period.put("hierarchy", "Time");
            period.put("level", "Quarter");
            m.put("period", period);
        }
        return m;
    }

    private static DashboardDigestSpec spec(Map<String, Object> insight, Map<String, Object>... measures) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> dash = new LinkedHashMap<>();
        dash.put("title", "Exec Overview");
        payload.put("dashboard", dash);
        payload.put("measures", new ArrayList<Object>(List.of(measures)));
        if (insight != null) {
            payload.put("insight", insight);
        }
        return DashboardDigestSpec.fromPayload(payload);
    }

    private static RecordingReader reader() {
        RecordingReader r = new RecordingReader();
        r.current = Map.of("Unit Sales", 1200d);
        r.previous = Map.of("Unit Sales", 1000d);
        return r;
    }

    @Test
    public void readsEachMeasureTwiceAndDifferencesIt() throws Exception {
        RecordingReader r = reader();
        InsightDigestBuilder builder = new InsightDigestBuilder(r, new TemplateDigestNarrator());
        InsightDigestBuilder.InsightDigest digest = builder.build(spec(Map.of(), measure("Unit Sales", "Units", true)));

        assertEquals(2, r.measures.size());
        assertEquals(1, digest.deltas().size());
        MeasureDelta d = digest.deltas().get(0);
        assertEquals("Units", d.label());
        assertEquals(1200d, d.current(), 1e-9);
        assertEquals(1000d, d.previous(), 1e-9);
        assertEquals(200d, d.absoluteChange(), 1e-9);
        assertEquals(1, digest.bullets().size());
    }

    @Test
    public void theTwoReadsDifferOnlyByThePeriodPreset() throws Exception {
        RecordingReader r = reader();
        new InsightDigestBuilder(r, new TemplateDigestNarrator())
                .build(spec(Map.of(), measure("Unit Sales", "Units", true)));
        AiFilterSelection first = r.seen.get(0);
        AiFilterSelection second = r.seen.get(1);
        assertEquals("qtd", first.getValue());
        assertEquals(PeriodSpec.PREVIOUS_PRESET, second.getValue());
        assertEquals(first.getLevel(), second.getLevel());
        assertEquals(first.getHierarchy(), second.getHierarchy());
        assertEquals(first.getDimension(), second.getDimension());
    }

    @Test
    public void aMeasureWithoutAPeriodContributesNoDelta() throws Exception {
        RecordingReader r = reader();
        InsightDigestBuilder builder = new InsightDigestBuilder(r, new TemplateDigestNarrator());
        InsightDigestBuilder.InsightDigest digest =
                builder.build(spec(Map.of(), measure("Unit Sales", "Units", false)));
        assertTrue(digest.deltas().isEmpty());
        // No period slicer was ever sent to the cube.
        assertTrue(r.seen.isEmpty());
    }

    @Test
    public void aStaticSlicererOnTheSameAxisIsSupersededByThePeriod() throws Exception {
        RecordingReader r = reader();
        Map<String, Object> m = measure("Unit Sales", "Units", true);
        List<Object> filters = new ArrayList<>();
        Map<String, Object> timeSlice = new LinkedHashMap<>();
        timeSlice.put("dimension", "Time");
        timeSlice.put("hierarchy", "Time");
        timeSlice.put("level", "Quarter");
        timeSlice.put("members", List.of("[Time].[Q1]"));
        filters.add(timeSlice);
        Map<String, Object> storeSlice = new LinkedHashMap<>();
        storeSlice.put("dimension", "Store");
        storeSlice.put("hierarchy", "Store");
        storeSlice.put("level", "Store Country");
        storeSlice.put("members", List.of("[USA]"));
        filters.add(storeSlice);
        m.put("filters", filters);

        List<AiFilterSelection> composed = InsightDigestBuilder.withPeriod(
                org.saiku.service.schedule.alert.PayloadParsing.readFilters(filters),
                new PeriodSpec("Time", "Time", "Quarter", "qtd").currentFilter());

        assertEquals(2, composed.size());
        assertEquals("Store", composed.get(0).getHierarchy());
        assertEquals("Time", composed.get(1).getHierarchy());
    }

    @Test
    public void oneFailingMeasureDoesNotSinkTheDigest() throws Exception {
        RecordingReader r = reader();
        r.current = Map.of("Unit Sales", 1200d);
        r.previous = Map.of("Unit Sales", 1000d);
        r.failMeasure = "Store Count";
        DashboardDigestSpec s =
                spec(Map.of(), measure("Unit Sales", "Units", true), measure("Store Count", "Stores", true));
        InsightDigestBuilder.InsightDigest digest = new InsightDigestBuilder(r, new TemplateDigestNarrator()).build(s);
        assertEquals(1, digest.deltas().size());
        assertEquals("Units", digest.deltas().get(0).label());
    }

    @Test
    public void everyMeasureFailingIsAFailedRunNotAnEmptyEmail() {
        RecordingReader r = reader();
        r.failMeasure = "Unit Sales";
        DashboardDigestSpec s = spec(Map.of(), measure("Unit Sales", "Units", true));
        try {
            new InsightDigestBuilder(r, new TemplateDigestNarrator()).build(s);
            fail("expected DashboardDigestDeliveryException");
        } catch (Exception e) {
            assertTrue(e instanceof DashboardDigestDeliveryException);
            assertTrue(e.getMessage().contains("no digest measure could be read"));
        }
    }

    @Test
    public void theNarratorReceivesTheTitleDeltasAndBulletCap() throws Exception {
        List<String> call = new ArrayList<>();
        DigestNarrator spy = (title, deltas, maxBullets) -> {
            call.add(title + "|" + deltas.size() + "|" + maxBullets);
            return List.of("a bullet");
        };
        InsightDigestBuilder.InsightDigest digest = new InsightDigestBuilder(reader(), spy)
                .build(spec(Map.of("maxBullets", 5), measure("Unit Sales", "Units", true)));
        assertEquals(List.of("Exec Overview|1|5"), call);
        assertEquals(List.of("a bullet"), digest.bullets());
    }
}
