/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.integers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import org.saiku.service.olap.ai.AiSchema;

/**
 * Property-based tests for {@link AiSchema#toAgentView()} — the redaction that decides what the
 * agent-facing {@code /ai/schema} response reveals about columns flagged
 * {@code saiku.semantic.pii=true} (saiku#902).
 *
 * <p>This is the highest-consequence uncovered function in the AI surface, and the gap already cost
 * something: saiku#1848's design rationale asserted that the describe endpoint "redacts" PII
 * structure, and the drillthrough gate was left open on that basis. It does not redact the
 * IDENTIFIER — {@code name} and {@code uniqueName} deliberately survive so the schema keeps its
 * shape — and nothing had ever asserted which of the two it was.
 *
 * <p>So these properties pin the contract in BOTH directions, because both halves matter:
 *
 * <ul>
 *   <li>what must be STRIPPED — descriptions, synonyms, display names, sample-member captions, and
 *       any alias that would let a lookup reach a redacted slot by another name;
 *   <li>what must SURVIVE — the structural skeleton, so the agent can still see that a column
 *       exists and not hallucinate around the hole.
 * </ul>
 */
class AiSchemaRedactionPropertyTest {

    /** Text that would be a disclosure if it escaped redaction. */
    private static final String SECRET = "SECRET-PII-MARKER";

    /** Build a schema where every PII-flagged slot carries {@link #SECRET} in every free-text field. */
    private static AiSchema schemaWith(int measureCount, int levelCount, boolean piiMeasures, boolean piiLevels) {
        AiSchema s = new AiSchema("conn/cat/sch/Sales", "Sales", "[Sales]");

        for (int i = 0; i < measureCount; i++) {
            boolean pii = piiMeasures && i % 2 == 0;
            AiSchema.Measure m = new AiSchema.Measure("Measure" + i, "[Measures].[Measure" + i + "]");
            m.pii = pii;
            m.displayName = pii ? SECRET : "Display" + i;
            m.description = pii ? SECRET : "Description" + i;
            m.synonyms = List.of(pii ? SECRET : "syn" + i);
            m.unit = pii ? SECRET : "unit";
            m.currency = pii ? SECRET : "GBP";
            s.measures.put(AiSchema.key(m.name), m);
            // An alias pointing at the measure — must not survive for a PII target.
            s.measureAliases.put(AiSchema.key("alias" + i), AiSchema.key(m.name));
        }

        AiSchema.Dimension dim = new AiSchema.Dimension("Customer", "[Customer]");
        AiSchema.Hierarchy hier = new AiSchema.Hierarchy("Customer", "[Customer].[Customer]");
        for (int i = 0; i < levelCount; i++) {
            boolean pii = piiLevels && i % 2 == 0;
            AiSchema.Level l = new AiSchema.Level("Level" + i, "[Customer].[Customer].[Level" + i + "]");
            l.pii = pii;
            l.displayName = pii ? SECRET : "Display" + i;
            l.description = pii ? SECRET : "Description" + i;
            l.synonyms = List.of(pii ? SECRET : "syn" + i);
            l.sampleMembers = List.of(new AiSchema.MemberSample(pii ? SECRET : "Member" + i, "[m" + i + "]"));
            hier.levels.put(AiSchema.key(l.name), l);
            hier.levelAliases.put(AiSchema.key("lalias" + i), AiSchema.key(l.name));
        }
        dim.hierarchies.put(AiSchema.key(hier.name), hier);
        s.dimensions.put(AiSchema.key(dim.name), dim);
        return s;
    }

    /** Every free-text field reachable in a view, flattened for scanning. */
    private static List<String> freeTextOf(AiSchema view) {
        List<String> out = new ArrayList<>();
        for (AiSchema.Measure m : view.measures.values()) {
            out.add(m.displayName);
            out.add(m.description);
            out.add(m.unit);
            out.add(m.currency);
            if (m.synonyms != null) {
                out.addAll(m.synonyms);
            }
        }
        for (AiSchema.Dimension d : view.dimensions.values()) {
            for (AiSchema.Hierarchy h : d.hierarchies.values()) {
                for (AiSchema.Level l : h.levels.values()) {
                    out.add(l.displayName);
                    out.add(l.description);
                    if (l.synonyms != null) {
                        out.addAll(l.synonyms);
                    }
                    if (l.sampleMembers != null) {
                        for (AiSchema.MemberSample ms : l.sampleMembers) {
                            out.add(ms.caption);
                            out.add(ms.uniqueName);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static AiSchema drawView(TestCase tc) {
        int measures = tc.draw(integers().min(1).max(5), "measures");
        int levels = tc.draw(integers().min(1).max(5), "levels");
        boolean piiMeasures = tc.draw(booleans(), "piiMeasures");
        boolean piiLevels = tc.draw(booleans(), "piiLevels");
        return schemaWith(measures, levels, piiMeasures, piiLevels).toAgentView();
    }

    // --- what must be stripped -------------------------------------------------

    /**
     * THE property. No free-text field anywhere in the agent view carries text that only ever
     * appeared on a PII-flagged slot. One assertion covering every field at once, so a NEW field
     * added to Measure or Level without a redaction rule is caught by this test rather than by an
     * incident.
     */
    @HegelTest
    void noPiiFreeTextEverReachesTheAgentView(TestCase tc) {
        AiSchema view = drawView(tc);

        for (String text : freeTextOf(view)) {
            assertFalse(SECRET.equals(text), "redaction leaked PII free text into the agent view");
        }
    }

    /** A PII measure keeps no descriptive metadata at all. */
    @HegelTest
    void piiMeasuresKeepNoDescriptiveMetadata(TestCase tc) {
        AiSchema view = drawView(tc);

        for (AiSchema.Measure m : view.measures.values()) {
            if (!m.pii) {
                continue;
            }
            assertNull(m.displayName, "PII measure kept a displayName");
            assertNull(m.description, "PII measure kept a description");
            assertTrue(m.synonyms == null || m.synonyms.isEmpty(), "PII measure kept synonyms");
            assertNull(m.unit, "PII measure kept a unit");
            assertNull(m.currency, "PII measure kept a currency");
        }
    }

    /** A PII level's sample members are replaced by the sentinel — never real captions. */
    @HegelTest
    void piiLevelSampleMembersAreReplacedBySentinel(TestCase tc) {
        AiSchema view = drawView(tc);

        for (AiSchema.Dimension d : view.dimensions.values()) {
            for (AiSchema.Hierarchy h : d.hierarchies.values()) {
                for (AiSchema.Level l : h.levels.values()) {
                    if (!l.pii) {
                        continue;
                    }
                    assertNotNull(l.sampleMembers, "PII level lost its sample-member shape entirely");
                    for (AiSchema.MemberSample ms : l.sampleMembers) {
                        assertEquals("[REDACTED]", ms.caption, "a real member caption survived on a PII level");
                    }
                }
            }
        }
    }

    /**
     * Aliases that resolve to a PII slot are dropped. Leaving one is a full bypass: the agent looks
     * up "social_security_number" and reaches the redacted level under another name.
     */
    @HegelTest
    void noAliasResolvesToARedactedSlot(TestCase tc) {
        AiSchema view = drawView(tc);

        for (String target : view.measureAliases.values()) {
            AiSchema.Measure m = view.measures.get(target);
            assertFalse(m != null && m.pii, "a measure alias still resolves to a PII measure");
        }
        for (AiSchema.Dimension d : view.dimensions.values()) {
            for (AiSchema.Hierarchy h : d.hierarchies.values()) {
                for (String target : h.levelAliases.values()) {
                    AiSchema.Level l = h.levels.get(target);
                    assertFalse(l != null && l.pii, "a level alias still resolves to a PII level");
                }
            }
        }
    }

    // --- what must survive -----------------------------------------------------

    /**
     * The structural skeleton survives. This is the DELIBERATE half of the contract — the agent
     * must still see that a column exists, or it invents one. It is also the fact saiku#1848's
     * rationale got wrong, so it is asserted explicitly rather than assumed either way.
     */
    @HegelTest
    void identifiersSurviveRedactionByDesign(TestCase tc) {
        AiSchema view = drawView(tc);

        for (AiSchema.Measure m : view.measures.values()) {
            assertNotNull(m.name, "a measure lost its name");
            assertNotNull(m.uniqueName, "a measure lost its uniqueName — including PII ones, by design");
        }
        for (AiSchema.Dimension d : view.dimensions.values()) {
            for (AiSchema.Hierarchy h : d.hierarchies.values()) {
                for (AiSchema.Level l : h.levels.values()) {
                    assertNotNull(l.name, "a level lost its name");
                    assertNotNull(l.uniqueName, "a level lost its uniqueName — including PII ones, by design");
                }
            }
        }
    }

    /** Redaction never loses a slot: the agent view has the same shape as the source. */
    @HegelTest
    void redactionPreservesTheSchemaShape(TestCase tc) {
        int measures = tc.draw(integers().min(1).max(5), "measures");
        int levels = tc.draw(integers().min(1).max(5), "levels");
        AiSchema source = schemaWith(measures, levels, true, true);

        AiSchema view = source.toAgentView();

        assertEquals(source.measures.size(), view.measures.size(), "a measure vanished from the agent view");
        assertEquals(source.dimensions.size(), view.dimensions.size(), "a dimension vanished");
        for (String dk : source.dimensions.keySet()) {
            assertNotNull(view.dimensions.get(dk), "dimension " + dk + " vanished");
        }
    }

    /** Non-PII slots are untouched — redaction must not degrade the ordinary case. */
    @HegelTest
    void nonPiiSlotsKeepEverything(TestCase tc) {
        AiSchema view = drawView(tc);

        for (AiSchema.Measure m : view.measures.values()) {
            if (m.pii) {
                continue;
            }
            assertNotNull(m.displayName, "a clean measure lost its displayName");
            assertNotNull(m.description, "a clean measure lost its description");
        }
    }

    /** The caller's schema is never mutated — redaction returns a new view. */
    @HegelTest
    void theSourceSchemaIsNeverMutated(TestCase tc) {
        int measures = tc.draw(integers().min(1).max(4), "measures");
        AiSchema source = schemaWith(measures, 2, true, true);

        List<String> before = freeTextOf(source);
        source.toAgentView();
        List<String> after = freeTextOf(source);

        assertEquals(before, after, "toAgentView mutated the schema it was asked to project");
    }

    /** Redaction is idempotent — projecting an already-projected view changes nothing. */
    @HegelTest
    void redactionIsIdempotent(TestCase tc) {
        AiSchema once = drawView(tc);
        AiSchema twice = once.toAgentView();

        assertEquals(freeTextOf(once), freeTextOf(twice), "a second projection changed the view");
    }

    /** Cube identity rides along — the view has to say which cube it describes. */
    @HegelTest
    void cubeIdentitySurvives(TestCase tc) {
        String cubeName = tc.draw(fromRegex("[A-Za-z ]{1,12}"), "cubeName");
        AiSchema source = new AiSchema("conn/cat/sch/" + cubeName, cubeName, "[" + cubeName + "]");
        AiSchema.Measure m = new AiSchema.Measure("M", "[Measures].[M]");
        source.measures.put(AiSchema.key(m.name), m);

        AiSchema view = source.toAgentView();

        assertEquals(source.getCubeName(), view.getCubeName());
        assertEquals(source.getCubeUniqueName(), view.getCubeUniqueName());
        assertEquals(source.getCubeId(), view.getCubeId());
    }
}
