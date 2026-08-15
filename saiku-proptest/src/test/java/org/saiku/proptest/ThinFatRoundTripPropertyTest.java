/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.olap4j.impl.NamedListImpl;
import org.olap4j.metadata.Cube;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Measure;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinAxis;
import org.saiku.olap.query2.ThinDetails;
import org.saiku.olap.query2.ThinHierarchy;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinMeasure;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.olap.query2.util.Fat;
import org.saiku.olap.query2.util.Thin;

/**
 * Round-trip properties for {@link Fat} / {@link Thin} — the conversion between Saiku's wire-side
 * {@link ThinQuery} and the olap4j-side {@code Query} that actually executes.
 *
 * <p>This pair is the spine of the query path. Every saved query, every dashboard tile and every
 * agent-built query crosses it at least twice per execution ({@code ThinQueryService.updateQuery}
 * converts Fat then Thin again to regenerate the MDX). A field dropped in either direction doesn't
 * error — it silently produces a DIFFERENT query from the one the user asked for, which is the
 * failure mode hardest to notice and hardest to attribute.
 *
 * <p>Tested against a REAL Mondrian 4 cube ({@link BankCubeHarness}), because {@code Fat.convert}
 * resolves every name against live olap4j metadata; a mock would only prove the mock agrees with
 * itself.
 *
 * <p>The properties are shaped as <b>normalise-then-idempotence</b>: convert once to get a
 * model the pipeline considers canonical, then assert a further round trip changes nothing. That
 * sidesteps having to generate a valid-by-construction {@link ThinQuery} — the hard part — while
 * still catching any field that is dropped, reordered or mangled in either direction.
 */
class ThinFatRoundTripPropertyTest {

    private static SaikuCube saikuCube(Cube cube) {
        return new SaikuCube(
                "bank",
                cube.getUniqueName(),
                cube.getName(),
                cube.getCaption(),
                cube.getSchema().getCatalog().getName(),
                cube.getSchema().getName());
    }

    /** First non-measure dimension carrying a hierarchy with a usable level. */
    private static Level firstUsableLevel(Cube cube) throws Exception {
        for (Dimension d : cube.getDimensions()) {
            if (d.getDimensionType() == Dimension.Type.MEASURE) {
                continue;
            }
            for (Hierarchy h : d.getHierarchies()) {
                for (Level l : h.getLevels()) {
                    if (!l.getName().equals("(All)")) {
                        return l;
                    }
                }
            }
        }
        return null;
    }

    /** Build a ThinQuery selecting the given measures, with one level on ROWS. */
    private static ThinQuery seedQuery(Cube cube, List<Measure> measures, Level level) {
        List<ThinMeasure> tms = new ArrayList<>();
        for (Measure m : measures) {
            tms.add(new ThinMeasure(m.getName(), m.getUniqueName(), m.getCaption(), ThinMeasure.Type.EXACT));
        }

        Map<String, ThinLevel> levels = new LinkedHashMap<>();
        levels.put(level.getName(), new ThinLevel(level.getName(), level.getCaption(), null, null));

        Hierarchy hier = level.getHierarchy();
        NamedListImpl<ThinHierarchy> hierarchies = new NamedListImpl<>();
        hierarchies.add(new ThinHierarchy(
                hier.getUniqueName(), hier.getCaption(), hier.getDimension().getName(), levels));

        Map<ThinQueryModel.AxisLocation, ThinAxis> axes = new LinkedHashMap<>();
        axes.put(
                ThinQueryModel.AxisLocation.ROWS,
                new ThinAxis(ThinQueryModel.AxisLocation.ROWS, hierarchies, false, null));

        ThinQueryModel model = new ThinQueryModel();
        model.setAxes(axes);
        model.setDetails(new ThinDetails(ThinQueryModel.AxisLocation.COLUMNS, ThinDetails.Location.BOTTOM, tms));

        return new ThinQuery("roundtrip", saikuCube(cube), model);
    }

    /** Draw a cube that can carry a query, plus a level and a measure subset from its real metadata. */
    private record Fixture(Cube cube, ThinQuery seed) {}

    private static Fixture fixture(TestCase tc) throws Exception {
        assumeTrue(BankCubeHarness.isAvailable(), "Bank seed files not present");
        List<Cube> cubes = BankCubeHarness.queryableCubes();
        assumeTrue(!cubes.isEmpty(), "no queryable cubes in the Bank schema");

        Cube cube = tc.draw(sampledFrom(cubes), "cube");
        Level level = firstUsableLevel(cube);
        assumeTrue(level != null, "no usable level on " + cube.getName());

        List<Measure> all = cube.getMeasures();
        assumeTrue(!all.isEmpty(), "no measures on " + cube.getName());
        int count = tc.draw(integers().min(1).max(all.size()), "measureCount");
        List<Measure> chosen = all.subList(0, count);

        return new Fixture(cube, seedQuery(cube, chosen, level));
    }

    /** Measure unique names on a converted model, in order. */
    private static List<String> measureNames(ThinQuery tq) {
        List<String> out = new ArrayList<>();
        for (ThinMeasure m : tq.getQueryModel().getDetails().getMeasures()) {
            out.add(m.getUniqueName());
        }
        return out;
    }

    /** Hierarchy unique names per axis, in order. */
    private static List<String> hierarchyNames(ThinQuery tq) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<ThinQueryModel.AxisLocation, ThinAxis> e :
                tq.getQueryModel().getAxes().entrySet()) {
            for (ThinHierarchy h : e.getValue().getHierarchies()) {
                out.add(e.getKey() + ":" + h.getName());
            }
        }
        return out;
    }

    /** One normalisation pass: Thin -> Fat -> Thin. */
    private static ThinQuery normalise(ThinQuery tq, Cube cube) throws Exception {
        return Thin.convert(Fat.convert(tq, cube), tq.getCube());
    }

    /**
     * THE round trip. After one normalisation pass the model is canonical, so a further pass must
     * change nothing. Any field dropped or mangled by either direction shows up as drift here.
     */
    @HegelTest
    void aFurtherRoundTripChangesNothing(TestCase tc) throws Exception {
        Fixture f = fixture(tc);

        ThinQuery once = normalise(f.seed(), f.cube());
        ThinQuery twice = normalise(once, f.cube());

        assertEquals(measureNames(once), measureNames(twice), "measures drifted on re-conversion");
        assertEquals(hierarchyNames(once), hierarchyNames(twice), "axis hierarchies drifted on re-conversion");
        assertEquals(once.getMdx(), twice.getMdx(), "generated MDX drifted on re-conversion");
    }

    /** The measures the caller asked for survive the trip, in the order they asked for them. */
    @HegelTest
    void everySelectedMeasureSurvivesTheRoundTrip(TestCase tc) throws Exception {
        Fixture f = fixture(tc);

        List<String> wanted = measureNames(f.seed());
        ThinQuery converted = normalise(f.seed(), f.cube());

        assertEquals(wanted, measureNames(converted), "measure selection changed crossing Fat/Thin");
    }

    /** The axis hierarchy survives — losing it silently changes what the query returns. */
    @HegelTest
    void theRowsHierarchySurvivesTheRoundTrip(TestCase tc) throws Exception {
        Fixture f = fixture(tc);

        ThinQuery converted = normalise(f.seed(), f.cube());

        ThinAxis rows = converted.getQueryModel().getAxes().get(ThinQueryModel.AxisLocation.ROWS);
        assertNotNull(rows, "the ROWS axis vanished");
        assertFalse(rows.getHierarchies().isEmpty(), "the ROWS hierarchy vanished");
    }

    /** Conversion always yields executable MDX naming the cube. */
    @HegelTest
    void conversionProducesMdxForTheRightCube(TestCase tc) throws Exception {
        Fixture f = fixture(tc);

        ThinQuery converted = normalise(f.seed(), f.cube());
        String mdx = converted.getMdx();

        assertNotNull(mdx, "no MDX was generated");
        assertFalse(mdx.isBlank(), "generated MDX was blank");
        assertTrue(
                mdx.contains(f.cube().getName()),
                "MDX does not reference " + f.cube().getName() + ": " + mdx);
    }

    /** The cube coordinates ride along unchanged — they route the execution. */
    @HegelTest
    void theCubeCoordinatesSurviveTheRoundTrip(TestCase tc) throws Exception {
        Fixture f = fixture(tc);

        SaikuCube before = f.seed().getCube();
        SaikuCube after = normalise(f.seed(), f.cube()).getCube();

        assertEquals(before.getConnection(), after.getConnection());
        assertEquals(before.getCatalog(), after.getCatalog());
        assertEquals(before.getSchema(), after.getSchema());
        assertEquals(before.getName(), after.getName());
    }

    /** Conversion is deterministic — the same input twice yields the same MDX. */
    @HegelTest
    void conversionIsDeterministic(TestCase tc) throws Exception {
        Fixture f = fixture(tc);

        assertEquals(
                normalise(f.seed(), f.cube()).getMdx(),
                normalise(f.seed(), f.cube()).getMdx(),
                "conversion is not deterministic");
    }
}
