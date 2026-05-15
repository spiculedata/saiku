/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import org.saiku.olap.query2.ThinQuery;

/**
 * Property-based tests for {@link AiSchemaConverter} (Phase 3.C / #4).
 *
 * <p>Generates random valid {@link AiQueryRequest} bodies from the
 * {@link QuirksTestFixture} schema and asserts converter-level
 * properties that should hold for every shape in the input space:
 *
 * <ol>
 *   <li><b>convert-doesn't-throw</b>: every valid body the generator
 *       produces converts to a ThinQuery without RuntimeException.</li>
 *   <li><b>preview-replay stability</b>: calling convert twice on the
 *       same body yields byte-identical MDX. Catches accidental
 *       non-determinism (UUIDs, current-time, set iteration order).</li>
 *   <li><b>measure echo</b>: every selected measure's uniqueName
 *       appears in the emitted MDX. Catches the saiku#796-class bug
 *       where duplicates or wrong-cased lookups drop a measure.</li>
 *   <li><b>cube echo</b>: the canonical cube name appears in the FROM
 *       clause, regardless of agent-supplied case. Cross-validates
 *       saiku#811 at scale.</li>
 * </ol>
 *
 * <p>Hand-rolled rather than jqwik to avoid pulling in JUnit-5
 * platform-engine wiring just for this one test class. Seed is fixed
 * for reproducibility; bump it when you want fresh shapes.
 *
 * <p>Generator avoids the closure hier
 * ({@code Employee$Manager Id$Parent}) since that one's a documented
 * saiku#810 trap — the converter accepts it shape-wise but Mondrian
 * would reject the MDX at runtime. Property tests focus on
 * converter-level invariants, not Mondrian's tolerance.
 */
public class AiQueryRequestPropertyTest {

    private static final long SEED = 42L;
    private static final int CASES = 200;

    private final AiSchemaConverter converter = new AiSchemaConverter();
    private final AiSchema schema = QuirksTestFixture.directSchema();

    @Test
    public void convertNeverThrowsOnGeneratedValidBodies() {
        Random rng = new Random(SEED);
        int succeeded = 0;
        for (int i = 0; i < CASES; i++) {
            AiQueryRequest req = generate(rng);
            try {
                ThinQuery tq = converter.convert(req, schema);
                assertNotNull(tq);
                assertNotNull("emitted MDX must be non-null on case " + i, tq.getMdx());
                succeeded++;
            } catch (RuntimeException e) {
                fail("Case " + i + " threw " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\nBody: "
                        + describe(req));
            }
        }
        assertTrue("at least 100 cases must run; got " + succeeded, succeeded >= 100);
    }

    @Test
    public void convertIsDeterministicForSameBody() {
        Random rng = new Random(SEED);
        for (int i = 0; i < 50; i++) {
            AiQueryRequest req = generate(rng);
            ThinQuery a = converter.convert(req, schema);
            ThinQuery b = converter.convert(req, schema);
            assertEquals(
                    "MDX must be deterministic on case " + i + " — body: " + describe(req), a.getMdx(), b.getMdx());
        }
    }

    @Test
    public void everySelectedMeasureAppearsInEmittedMdx() {
        Random rng = new Random(SEED);
        for (int i = 0; i < CASES; i++) {
            AiQueryRequest req = generate(rng);
            ThinQuery tq = converter.convert(req, schema);
            String mdx = tq.getMdx();
            for (AiMeasureSelection m : req.getMeasures()) {
                AiSchema.Measure resolved = schema.measures.get(AiSchema.key(m.getName()));
                assertNotNull("measure must resolve in fixture: " + m.getName(), resolved);
                assertTrue(
                        "MDX must reference measure " + resolved.uniqueName + " — case " + i + "\nMDX: " + mdx,
                        mdx.contains(resolved.uniqueName));
            }
        }
    }

    @Test
    public void canonicalCubeNameAppearsInFromClause() {
        Random rng = new Random(SEED);
        String expectedFrom = "FROM [" + QuirksTestFixture.CUBE + "]";
        for (int i = 0; i < CASES; i++) {
            AiQueryRequest req = generate(rng);
            ThinQuery tq = converter.convert(req, schema);
            assertTrue(
                    "emitted MDX must end with FROM [Quirks] — case " + i + "\nMDX: " + tq.getMdx(),
                    tq.getMdx().contains(expectedFrom));
        }
    }

    /* ----------------------- generator helpers ----------------------- */

    private AiQueryRequest generate(Random rng) {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(QuirksTestFixture.cubeRef());

        List<String> measureNames = new ArrayList<>();
        for (AiSchema.Measure m : schema.measures.values()) measureNames.add(m.name);
        int measureCount = 1 + rng.nextInt(2);
        java.util.Set<String> picked = new java.util.LinkedHashSet<>();
        while (picked.size() < measureCount) {
            picked.add(measureNames.get(rng.nextInt(measureNames.size())));
        }
        for (String name : picked) {
            AiMeasureSelection ms = new AiMeasureSelection();
            ms.setName(name);
            req.getMeasures().add(ms);
        }

        int rowCount = rng.nextInt(3);
        List<int[]> usedHiers = new ArrayList<>();
        List<AiSchema.Dimension> dims = new ArrayList<>(schema.dimensions.values());
        for (int r = 0; r < rowCount; r++) {
            for (int tries = 0; tries < 10; tries++) {
                int di = rng.nextInt(dims.size());
                AiSchema.Dimension dim = dims.get(di);
                List<AiSchema.Hierarchy> hiers = new ArrayList<>(dim.hierarchies.values());
                if (hiers.isEmpty()) continue;
                int hi = rng.nextInt(hiers.size());
                AiSchema.Hierarchy hier = hiers.get(hi);
                if (hier.name.contains("$")) continue;
                if (alreadyUsed(usedHiers, di, hi)) continue;

                AiSchema.Level level = null;
                for (AiSchema.Level l : hier.levels.values()) {
                    if (l.name != null && !l.name.equalsIgnoreCase("(All)")) {
                        level = l;
                        break;
                    }
                }
                if (level == null) continue;

                AiAxisSelection a = new AiAxisSelection();
                a.setDimension(dim.name);
                a.setHierarchy(hier.name);
                a.setLevel(level.name);
                req.getRows().add(a);
                usedHiers.add(new int[] {di, hi});
                break;
            }
        }
        return req;
    }

    private static boolean alreadyUsed(List<int[]> usedHiers, int di, int hi) {
        for (int[] u : usedHiers) {
            if (u[0] == di && u[1] == hi) return true;
        }
        return false;
    }

    private static String describe(AiQueryRequest req) {
        StringBuilder s = new StringBuilder();
        s.append("measures=[");
        for (int i = 0; i < req.getMeasures().size(); i++) {
            if (i > 0) s.append(", ");
            s.append(req.getMeasures().get(i).getName());
        }
        s.append("], rows=[");
        for (int i = 0; i < req.getRows().size(); i++) {
            if (i > 0) s.append(", ");
            AiAxisSelection a = req.getRows().get(i);
            s.append(a.getDimension())
                    .append("/")
                    .append(a.getHierarchy())
                    .append("/")
                    .append(a.getLevel());
        }
        s.append("]");
        return s.toString();
    }
}
