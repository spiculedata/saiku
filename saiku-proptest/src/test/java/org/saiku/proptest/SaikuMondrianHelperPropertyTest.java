/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import mondrian.olap4j.SaikuMondrianHelper;
import org.olap4j.metadata.Cube;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Measure;

/**
 * Property-based tests for {@link SaikuMondrianHelper} — the only part of {@code saiku-olap-util}
 * with an invariant worth stating. The module is otherwise live-object glue (unwrapping olap4j
 * handles, reading Mondrian internals), which is why its coverage was zero.
 *
 * <p>{@code isMondrianDrillthrough} decides whether a statement is routed down the DRILLTHROUGH
 * path, which returns RAW FACT ROWS rather than aggregates. Misclassifying is not cosmetic: a
 * drillthrough treated as a regular query fails confusingly, and the reverse sends a plain SELECT
 * into the row-returning path. It is asserted here against a REAL Mondrian connection
 * ({@link BankCubeHarness}), because the classification is Mondrian's own parser, not a string
 * check.
 */
class SaikuMondrianHelperPropertyTest {

    /** Build a valid SELECT for a cube, plus its DRILLTHROUGH counterpart. */
    private record Pair(String select, String drillthrough) {}

    private static Pair statementsFor(Cube cube) throws Exception {
        List<Measure> measures = cube.getMeasures();
        String measure = measures.get(0).getUniqueName();
        String select = "SELECT {" + measure + "} ON COLUMNS FROM [" + cube.getName() + "]";
        return new Pair(select, "DRILLTHROUGH " + select);
    }

    private static List<Cube> usableCubes() throws Exception {
        List<Cube> out = new ArrayList<>();
        for (Cube c : BankCubeHarness.cubes()) {
            if (!c.getMeasures().isEmpty()) {
                out.add(c);
            }
        }
        return out;
    }

    /** A DRILLTHROUGH statement is always recognised as one. */
    @HegelTest
    void drillthroughStatementsAreRecognised(TestCase tc) throws Exception {
        assumeTrue(BankCubeHarness.isAvailable(), "Bank seed files not present");
        List<Cube> cubes = usableCubes();
        assumeTrue(!cubes.isEmpty(), "no cubes with measures");

        Cube cube = tc.draw(sampledFrom(cubes), "cube");
        Pair p = statementsFor(cube);
        tc.note(p.drillthrough());

        assertTrue(
                SaikuMondrianHelper.isMondrianDrillthrough(BankCubeHarness.connection(), p.drillthrough()),
                "not recognised as drillthrough: " + p.drillthrough());
    }

    /** A plain SELECT is never mistaken for a drillthrough. */
    @HegelTest
    void plainSelectsAreNotDrillthrough(TestCase tc) throws Exception {
        assumeTrue(BankCubeHarness.isAvailable(), "Bank seed files not present");
        List<Cube> cubes = usableCubes();
        assumeTrue(!cubes.isEmpty(), "no cubes with measures");

        Cube cube = tc.draw(sampledFrom(cubes), "cube");
        Pair p = statementsFor(cube);
        tc.note(p.select());

        assertFalse(
                SaikuMondrianHelper.isMondrianDrillthrough(BankCubeHarness.connection(), p.select()),
                "a plain SELECT was routed to drillthrough: " + p.select());
    }

    /** The two classifications never agree for the same underlying query. */
    @HegelTest
    void aQueryAndItsDrillthroughNeverClassifyAlike(TestCase tc) throws Exception {
        assumeTrue(BankCubeHarness.isAvailable(), "Bank seed files not present");
        List<Cube> cubes = usableCubes();
        assumeTrue(!cubes.isEmpty(), "no cubes with measures");

        Cube cube = tc.draw(sampledFrom(cubes), "cube");
        Pair p = statementsFor(cube);

        boolean a = SaikuMondrianHelper.isMondrianDrillthrough(BankCubeHarness.connection(), p.select());
        boolean b = SaikuMondrianHelper.isMondrianDrillthrough(BankCubeHarness.connection(), p.drillthrough());

        assertFalse(a == b, "SELECT and DRILLTHROUGH classified identically for " + cube.getName());
    }

    /** A live Mondrian connection is recognised as one — the gate every helper here sits behind. */
    @HegelTest
    void aLiveMondrianConnectionIsRecognised(TestCase tc) throws Exception {
        assumeTrue(BankCubeHarness.isAvailable(), "Bank seed files not present");

        assertTrue(SaikuMondrianHelper.isMondrianConnection(BankCubeHarness.connection()));
    }

    /** Annotation lookup is false for keys the schema doesn't carry, and never throws. */
    @HegelTest
    void annotationLookupIsFalseForAbsentKeys(TestCase tc) throws Exception {
        assumeTrue(BankCubeHarness.isAvailable(), "Bank seed files not present");

        List<Level> levels = new ArrayList<>();
        for (Cube c : BankCubeHarness.cubes()) {
            for (Dimension d : c.getDimensions()) {
                for (Hierarchy h : d.getHierarchies()) {
                    levels.addAll(h.getLevels());
                }
            }
        }
        assumeTrue(!levels.isEmpty(), "no levels in the Bank schema");

        Level level = tc.draw(sampledFrom(levels), "level");
        String absentKey = tc.draw(
                sampledFrom(List.of("saiku.semantic.definitely_absent", "no.such.key", "saiku.semantic.pii.nope")),
                "absentKey");

        assertFalse(
                SaikuMondrianHelper.hasAnnotation(level, absentKey),
                "reported an annotation the schema does not declare: " + absentKey);
    }
}
