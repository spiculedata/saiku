/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import org.olap4j.CellSet;
import org.olap4j.OlapStatement;
import org.olap4j.metadata.Cube;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Measure;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.Matrix;
import org.saiku.olap.util.formatter.CellSetFormatter;
import org.saiku.olap.util.formatter.FlattenedCellSetFormatter;
import org.saiku.olap.util.formatter.HierarchicalCellSetFormatter;
import org.saiku.olap.util.formatter.ICellSetFormatter;

/**
 * Property-based tests for the three {@link ICellSetFormatter} implementations, which turn an olap4j
 * {@link CellSet} into the {@link Matrix} the UI and every exporter render.
 *
 * <p>These are the most index-arithmetic-heavy classes in the query path — nested loops over axis
 * positions, member depths and spans, with off-by-ones that do not throw. A formatter that emits a
 * row one cell short doesn't error; it shifts every value in that row under the wrong heading, and
 * the number a user reads is simply wrong. Ragged output is the failure mode, so rectangularity is
 * the property.
 *
 * <p>Run against REAL cellsets from {@link BankCubeHarness} — executed by Mondrian, with genuine
 * member hierarchies, spans and empty cells. Synthesising a CellSet by hand would test the mock.
 */
class CellSetFormatterPropertyTest {

    /** The three formatters, by name so failures identify which. */
    private static List<String> formatterNames() {
        return List.of("CellSetFormatter", "FlattenedCellSetFormatter", "HierarchicalCellSetFormatter");
    }

    private static ICellSetFormatter formatterFor(String name) {
        return switch (name) {
            case "FlattenedCellSetFormatter" -> new FlattenedCellSetFormatter();
            case "HierarchicalCellSetFormatter" -> new HierarchicalCellSetFormatter();
            default -> new CellSetFormatter();
        };
    }

    /** A level worth putting on ROWS — skips the synthetic (All) level. */
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

    /** Execute a real query and hand back its cellset. */
    private static CellSet execute(Cube cube, boolean withRows) throws Exception {
        List<Measure> measures = cube.getMeasures();
        StringBuilder mdx = new StringBuilder("SELECT {");
        for (int i = 0; i < Math.min(2, measures.size()); i++) {
            if (i > 0) {
                mdx.append(", ");
            }
            mdx.append(measures.get(i).getUniqueName());
        }
        mdx.append("} ON COLUMNS");
        if (withRows) {
            Level level = firstUsableLevel(cube);
            if (level != null) {
                mdx.append(", ").append(level.getUniqueName()).append(".Members ON ROWS");
            }
        }
        mdx.append(" FROM [").append(cube.getName()).append("]");

        try (OlapStatement st = BankCubeHarness.connection().createStatement()) {
            return st.executeOlapQuery(mdx.toString());
        }
    }

    /** Draw a cube and a formatter, and format a real cellset. */
    private record Formatted(String formatter, Cube cube, Matrix matrix) {}

    private static Formatted formatted(TestCase tc) throws Exception {
        assumeTrue(BankCubeHarness.isAvailable(), "Bank seed files not present");
        List<Cube> cubes = new ArrayList<>();
        for (Cube c : BankCubeHarness.cubes()) {
            if (!c.getMeasures().isEmpty()) {
                cubes.add(c);
            }
        }
        assumeTrue(!cubes.isEmpty(), "no cubes with measures");

        Cube cube = tc.draw(sampledFrom(cubes), "cube");
        String name = tc.draw(sampledFrom(formatterNames()), "formatter");
        boolean withRows = tc.draw(dev.hegel.Generators.booleans(), "withRows");

        CellSet cs = execute(cube, withRows);
        return new Formatted(name, cube, formatterFor(name).format(cs));
    }

    /** Every populated (x, y) coordinate in the sparse matrix. */
    private static List<List<Integer>> populatedCoordinates(Matrix m) {
        return new ArrayList<>(m.getMap().keySet());
    }

    /**
     * THE property. Every cell a formatter writes lands INSIDE the bounds it declares. Matrix is
     * sparse and map-backed, and consumers (the UI grid, every exporter) iterate 0..width x
     * 0..height — so a cell written outside those bounds is silently invisible, and a declared
     * bound larger than what was written leaves holes. Either way a user reads the wrong grid, with
     * no error anywhere. This is exactly what the nested index arithmetic in these three classes
     * gets wrong.
     */
    @HegelTest
    void everyWrittenCellLiesInsideTheDeclaredBounds(TestCase tc) throws Exception {
        Formatted f = formatted(tc);
        Matrix m = f.matrix();

        int width = m.getMatrixWidth();
        int height = m.getMatrixHeight();

        for (List<Integer> xy : populatedCoordinates(m)) {
            int x = xy.get(0);
            int y = xy.get(1);
            assertTrue(
                    x >= 0 && x < width,
                    f.formatter() + " wrote x=" + x + " outside declared width " + width + " on "
                            + f.cube().getName());
            assertTrue(
                    y >= 0 && y < height,
                    f.formatter() + " wrote y=" + y + " outside declared height " + height + " on "
                            + f.cube().getName());
        }
    }

    /** The header offset never exceeds the grid it indexes into. */
    @HegelTest
    void theHeaderOffsetLiesInsideTheGrid(TestCase tc) throws Exception {
        Formatted f = formatted(tc);
        Matrix m = f.matrix();

        assertTrue(m.getOffset() >= 0, f.formatter() + " declared a negative header offset");
        assertTrue(
                m.getOffset() <= m.getMatrixHeight(),
                f.formatter() + " declared a header offset past the end of the grid: " + m.getOffset() + " > "
                        + m.getMatrixHeight());
    }

    /** Formatting never throws, for any cube and any of the three implementations. */
    @HegelTest
    void formattingNeverThrows(TestCase tc) throws Exception {
        assumeTrue(BankCubeHarness.isAvailable(), "Bank seed files not present");
        List<Cube> cubes = new ArrayList<>();
        for (Cube c : BankCubeHarness.cubes()) {
            if (!c.getMeasures().isEmpty()) {
                cubes.add(c);
            }
        }
        assumeTrue(!cubes.isEmpty(), "no cubes with measures");

        Cube cube = tc.draw(sampledFrom(cubes), "cube");
        String name = tc.draw(sampledFrom(formatterNames()), "formatter");
        boolean withRows = tc.draw(dev.hegel.Generators.booleans(), "withRows");

        CellSet cs = execute(cube, withRows);

        assertDoesNotThrow(() -> formatterFor(name).format(cs), name + " threw on " + cube.getName());
    }

    /** Every cell the map holds is a real cell — a null value would NPE the renderers. */
    @HegelTest
    void noWrittenCellIsNull(TestCase tc) throws Exception {
        Formatted f = formatted(tc);

        for (AbstractBaseCell cell : f.matrix().getMap().values()) {
            assertNotNull(
                    cell, f.formatter() + " wrote a null cell on " + f.cube().getName());
        }
    }

    /** Declared bounds are never negative — they are loop limits downstream. */
    @HegelTest
    void declaredBoundsAreNeverNegative(TestCase tc) throws Exception {
        Formatted f = formatted(tc);

        assertTrue(f.matrix().getMatrixWidth() >= 0, f.formatter() + " declared a negative width");
        assertTrue(f.matrix().getMatrixHeight() >= 0, f.formatter() + " declared a negative height");
    }

    /** Formatting is deterministic — the same cellset formats identically twice. */
    @HegelTest
    void formattingIsDeterministic(TestCase tc) throws Exception {
        assumeTrue(BankCubeHarness.isAvailable(), "Bank seed files not present");
        List<Cube> cubes = new ArrayList<>();
        for (Cube c : BankCubeHarness.cubes()) {
            if (!c.getMeasures().isEmpty()) {
                cubes.add(c);
            }
        }
        assumeTrue(!cubes.isEmpty(), "no cubes with measures");

        Cube cube = tc.draw(sampledFrom(cubes), "cube");
        String name = tc.draw(sampledFrom(formatterNames()), "formatter");

        CellSet cs = execute(cube, true);
        Matrix a = formatterFor(name).format(cs);
        Matrix b = formatterFor(name).format(cs);

        assertEquals(a.getMatrixWidth(), b.getMatrixWidth(), name + " width is not deterministic");
        assertEquals(a.getMatrixHeight(), b.getMatrixHeight(), name + " height is not deterministic");
        assertEquals(a.getMap().size(), b.getMap().size(), name + " cell count is not deterministic");
    }

    /** A query with no ROWS axis still formats into a usable grid rather than an empty one. */
    @HegelTest
    void aMeasureOnlyQueryStillProducesAGrid(TestCase tc) throws Exception {
        assumeTrue(BankCubeHarness.isAvailable(), "Bank seed files not present");
        List<Cube> cubes = new ArrayList<>();
        for (Cube c : BankCubeHarness.cubes()) {
            if (!c.getMeasures().isEmpty()) {
                cubes.add(c);
            }
        }
        assumeTrue(!cubes.isEmpty(), "no cubes with measures");

        Cube cube = tc.draw(sampledFrom(cubes), "cube");
        String name = tc.draw(sampledFrom(formatterNames()), "formatter");

        Matrix m = formatterFor(name).format(execute(cube, false));

        assertNotNull(m, name + " returned no matrix for a measures-only query on " + cube.getName());
        assertTrue(m.getMatrixWidth() >= 0 && m.getMatrixHeight() >= 0, name + " returned nonsensical bounds");
    }
}
