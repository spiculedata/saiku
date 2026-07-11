/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.graphql;

import static org.junit.Assert.*;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeSummary;
import org.saiku.service.olap.ai.AiSchema;

/**
 * Property-based fuzz tests for {@link CubeTypeGenerator}.
 *
 * <p>The core invariant: <em>whatever hostile cube / measure / level names the metadata
 * service serves up, the generated SDL fragment always parses through graphql-java's
 * {@link SchemaParser}.</em> If a specific input produces an SDL fragment that fails to
 * parse, the whole GraphQL surface goes down on schema rebuild — a real production risk
 * given cube schema authors sometimes use punctuation, unicode, or accidental empties.
 *
 * <p>Seed is fixed. When a failure surfaces, the failing input is printed with enough
 * detail to reproduce.
 */
public class CubeTypeGeneratorFuzzTest {

    private static final long SEED = 0xC0FFEE_DEADL;

    private static final Pattern GRAPHQL_NAME = Pattern.compile("[_A-Za-z][_0-9A-Za-z]*");

    /**
     * Fuzz sanitisers directly: for every hostile input, screamingSnake / camelCase / pascalCase
     * must return either the empty string OR a GraphQL-legal identifier. GraphQL requires names
     * to match {@code [_A-Za-z][_0-9A-Za-z]&#42;}.
     */
    @Test
    public void sanitisersAlwaysEmitGraphQlLegalIdentifiersOrEmpty() {
        Random rng = new Random(SEED);
        for (int i = 0; i < 5000; i++) {
            String hostile = GraphQlFuzzUtil.hostileName(rng);
            String snake = CubeTypeGenerator.screamingSnake(hostile);
            String camel = CubeTypeGenerator.camelCase(hostile);
            String pascal = CubeTypeGenerator.pascalCase(hostile);
            for (String s : new String[] {snake, camel, pascal}) {
                assertNotNull("null identifier from input '" + hostile + "'", s);
                if (s.isEmpty()) continue;
                if (!GRAPHQL_NAME.matcher(s).matches()) {
                    fail("illegal GraphQL identifier '" + s + "' produced from '" + hostile + "'");
                }
            }
        }
    }

    /**
     * The load-bearing invariant. Generate cubes with hostile names + hostile measure/level
     * shapes; assert every non-empty SDL fragment parses cleanly. A regression here would take
     * out the whole GraphQL schema on rebuild.
     */
    @Test
    public void generatedSdlFragmentAlwaysParses() {
        Random rng = new Random(SEED + 1);
        SchemaParser parser = new SchemaParser();
        int emitted = 0, tested = 0;
        for (int i = 0; i < 2000; i++) {
            AiCubeSummary summary = randomSummary(rng);
            AiSchema schema = randomSchema(rng, summary.getCubeName());
            String fieldName = CubeTypeGenerator.camelCase(summary.getCubeName());
            if (fieldName.isEmpty()) fieldName = "cube" + i;
            CubeTypeGenerator gen;
            try {
                gen = new CubeTypeGenerator(summary, schema, fieldName);
            } catch (RuntimeException e) {
                fail("constructor threw on input " + describe(summary) + ": " + e);
                return;
            }
            String fragment = gen.toSdl();
            if (fragment.isEmpty()) continue;
            emitted++;
            // The fragment uses `extend type Query` which requires a base type — wrap with one.
            String probe = "scalar JSON\ntype Query { _placeholder: Int }\n\n" + fragment;
            try {
                TypeDefinitionRegistry reg = parser.parse(probe);
                assertNotNull(reg);
                tested++;
            } catch (Exception parseErr) {
                fail("SDL failed to parse for cube " + describe(summary) + "\n----- SDL -----\n" + fragment
                        + "\n----- error -----\n" + parseErr);
            }
        }
        assertTrue("at least some fragments should have been emitted", emitted > 0);
        assertEquals("all emitted fragments should have parsed", emitted, tested);
    }

    /**
     * Round-trip: for every generator instance, every emitted measure enum value must resolve
     * back to its canonical name via {@link CubeTypeGenerator#measureEnumToCanonical(String)},
     * and every emitted level enum value must resolve via {@link CubeTypeGenerator#levelEnumToAxis(String)}.
     *
     * <p>This catches bugs where sanitisation would emit an enum value the generator can't
     * subsequently map back — the fetcher would then throw {@code unknown measure enum} on
     * a name the SDL advertises as legal.
     */
    @Test
    public void emittedEnumsAlwaysRoundTrip() {
        Random rng = new Random(SEED + 2);
        for (int i = 0; i < 1000; i++) {
            AiCubeSummary summary = randomSummary(rng);
            AiSchema schema = randomSchema(rng, summary.getCubeName());
            String fieldName = CubeTypeGenerator.camelCase(summary.getCubeName());
            if (fieldName.isEmpty()) fieldName = "cube" + i;
            CubeTypeGenerator gen = new CubeTypeGenerator(summary, schema, fieldName);
            String sdl = gen.toSdl();
            if (sdl.isEmpty()) continue;
            // Extract every enum value from the fragment and assert it resolves.
            java.util.List<String> measureEnums =
                    extractEnumValues(sdl, CubeTypeGenerator.pascalCase(fieldName) + "Measure");
            for (String enumValue : measureEnums) {
                assertNotNull(
                        "measure enum '" + enumValue + "' didn't round-trip for cube " + describe(summary),
                        gen.measureEnumToCanonical(enumValue));
            }
            java.util.List<String> levelEnums =
                    extractEnumValues(sdl, CubeTypeGenerator.pascalCase(fieldName) + "Level");
            for (String enumValue : levelEnums) {
                assertNotNull(
                        "level enum '" + enumValue + "' didn't round-trip for cube " + describe(summary),
                        gen.levelEnumToAxis(enumValue));
            }
        }
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    private static AiCubeSummary randomSummary(Random rng) {
        return GraphQlFuzzUtil.summary(
                GraphQlFuzzUtil.hostileName(rng),
                GraphQlFuzzUtil.hostileName(rng),
                GraphQlFuzzUtil.hostileName(rng),
                pickCubeName(rng));
    }

    /** Cube name must be nonempty enough to produce a legal GraphQL identifier — bias here. */
    private static String pickCubeName(Random rng) {
        String[] pool = {"Sales", "HR", "Inventory", "Orders", "Store Sales", "PRODUCT-FAM", "café"};
        return pool[rng.nextInt(pool.length)];
    }

    private static AiSchema randomSchema(Random rng, String cubeId) {
        AiSchema schema = GraphQlFuzzUtil.schema(cubeId == null ? "cube" : cubeId);
        int measureCount = rng.nextInt(8) + 1;
        for (int i = 0; i < measureCount; i++) {
            GraphQlFuzzUtil.addMeasure(schema, GraphQlFuzzUtil.hostileName(rng));
        }
        int dimCount = rng.nextInt(5);
        for (int d = 0; d < dimCount; d++) {
            String dimName = GraphQlFuzzUtil.hostileName(rng);
            int hierCount = rng.nextInt(2) + 1;
            for (int h = 0; h < hierCount; h++) {
                String hierName = h == 0 ? dimName : GraphQlFuzzUtil.hostileName(rng);
                int levelCount = rng.nextInt(4) + 1;
                for (int l = 0; l < levelCount; l++) {
                    GraphQlFuzzUtil.addLevel(schema, dimName, hierName, GraphQlFuzzUtil.hostileName(rng));
                }
            }
        }
        return schema;
    }

    private static java.util.List<String> extractEnumValues(String sdl, String enumName) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int start = sdl.indexOf("enum " + enumName);
        if (start < 0) return out;
        int open = sdl.indexOf('{', start);
        int close = sdl.indexOf('}', open);
        if (open < 0 || close < 0) return out;
        String body = sdl.substring(open + 1, close);
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private static String describe(AiCubeSummary s) {
        return "conn='" + s.getConnectionName() + "' cat='" + s.getCatalog() + "' schema='" + s.getSchema() + "' cube='"
                + s.getCubeName() + "'";
    }
}
