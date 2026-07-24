/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import org.saiku.service.olap.drillthrough.DimensionResultInfo;
import org.saiku.service.olap.drillthrough.DrillthroughUtils;
import org.saiku.service.olap.drillthrough.MeasureResultInfo;
import org.saiku.service.olap.drillthrough.ResultInfo;

/**
 * Structural invariants for {@link DrillthroughUtils#extractResultInfo(String)}, which parses a
 * comma-separated list of MDX member paths into typed {@link ResultInfo} rows. Generators are
 * constrained to well-formed tokens (bracketed segments, {@code Measures.[X]} for measures) so the
 * properties hold; the parser throws on ragged input, which is out of scope here.
 */
class DrillthroughUtilsPropertyTest {

    /** A path segment: letter-led, no dots/commas/brackets so tokenisation stays clean. */
    private static final Generator<String> SEG = fromRegex("[A-Za-z][A-Za-z0-9 ]{0,10}");

    /** Draw one well-formed token — either a measure ref or a 3-segment dimension ref. */
    private static String drawToken(TestCase tc, String tag) {
        boolean measure = tc.draw(booleans(), tag + "-isMeasure");
        if (measure) {
            String casing = tc.draw(sampledFrom("Measures", "measures", "MEASURES", "MeAsUrEs"), tag + "-casing");
            String name = tc.draw(SEG, tag + "-name");
            return casing + ".[" + name + "]";
        }
        String d = tc.draw(SEG, tag + "-d");
        tc.assume(!d.equalsIgnoreCase("Measures")); // a dim named Measures would route to measure branch
        String h = tc.draw(SEG, tag + "-h");
        String l = tc.draw(SEG, tag + "-l");
        return "[" + d + "].[" + h + "].[" + l + "]";
    }

    /** Output row count equals the number of comma-separated tokens fed in. */
    @HegelTest
    void countIsPreserved(TestCase tc) {
        int n = tc.draw(dev.hegel.Generators.integers().map(x -> 1 + Math.floorMod(x, 5)), "n");
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                joined.append(',');
            }
            joined.append(drawToken(tc, "t" + i));
        }

        List<ResultInfo> out = DrillthroughUtils.extractResultInfo(joined.toString());

        assertEquals(n, out.size(), "one result per comma-separated token");
    }

    /** No field of any produced result retains a square bracket — the parser strips them all. */
    @HegelTest
    void bracketsAreStripped(TestCase tc) {
        int n = tc.draw(dev.hegel.Generators.integers().map(x -> 1 + Math.floorMod(x, 5)), "n");
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                joined.append(',');
            }
            joined.append(drawToken(tc, "t" + i));
        }

        for (ResultInfo info : DrillthroughUtils.extractResultInfo(joined.toString())) {
            for (String field : fieldsOf(info)) {
                assertFalse(field.contains("["), "no field may contain '['");
                assertFalse(field.contains("]"), "no field may contain ']'");
            }
        }
    }

    /** A {@code Measures}-led token (any casing) becomes a MeasureResultInfo; otherwise a Dimension one. */
    @HegelTest
    void measurePrefixRoutesToMeasureResult(TestCase tc) {
        boolean measure = tc.draw(booleans(), "isMeasure");
        if (measure) {
            String casing = tc.draw(sampledFrom("Measures", "measures", "MEASURES", "MeAsUrEs"), "casing");
            String name = tc.draw(SEG, "name");

            List<ResultInfo> out = DrillthroughUtils.extractResultInfo(casing + ".[" + name + "]");

            assertEquals(1, out.size());
            assertTrue(out.get(0) instanceof MeasureResultInfo, "Measures-led token must be a MeasureResultInfo");
            assertEquals(name, ((MeasureResultInfo) out.get(0)).getName(), "measure name is the second segment");
        } else {
            String d = tc.draw(SEG, "d");
            tc.assume(!d.equalsIgnoreCase("Measures"));
            String h = tc.draw(SEG, "h");
            String l = tc.draw(SEG, "l");

            List<ResultInfo> out = DrillthroughUtils.extractResultInfo("[" + d + "].[" + h + "].[" + l + "]");

            assertEquals(1, out.size());
            assertTrue(out.get(0) instanceof DimensionResultInfo, "non-Measures token must be a DimensionResultInfo");
            DimensionResultInfo dim = (DimensionResultInfo) out.get(0);
            assertEquals(d, dim.getDimension(), "dimension is the first segment");
            assertEquals(h, dim.getHierarchy(), "hierarchy is the second segment");
            assertEquals(l, dim.getLevel(), "level is the third segment");
        }
    }

    private static List<String> fieldsOf(ResultInfo info) {
        if (info instanceof MeasureResultInfo m) {
            return List.of(m.getName());
        }
        DimensionResultInfo d = (DimensionResultInfo) info;
        return List.of(d.getDimension(), d.getHierarchy(), d.getLevel());
    }
}
