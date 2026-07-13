/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.function.Function;
import org.junit.Test;

/** saiku#903 — unit coverage for the AI data-exposure policy + guard. */
public class AiPolicyGuardTest {

    private static Function<String, String> map(String... kv) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m::get;
    }

    /* ----------------------------- parse ----------------------------- */

    @Test
    public void parse_accepts_all_forms_case_and_separator_insensitive() {
        assertEquals(AiPolicy.SCHEMA_ONLY, AiPolicy.parse("schema-only"));
        assertEquals(AiPolicy.SCHEMA_ONLY, AiPolicy.parse("schema_only"));
        assertEquals(AiPolicy.SCHEMA_ONLY, AiPolicy.parse("  SCHEMA-ONLY "));
        assertEquals(AiPolicy.AGGREGATED, AiPolicy.parse("aggregated"));
        assertEquals(AiPolicy.AGGREGATED, AiPolicy.parse("AGGREGATED"));
        assertEquals(AiPolicy.FULL, AiPolicy.parse("full"));
    }

    @Test
    public void parse_blank_or_null_is_default_schema_only() {
        assertEquals(AiPolicy.SCHEMA_ONLY, AiPolicy.parse(null));
        assertEquals(AiPolicy.SCHEMA_ONLY, AiPolicy.parse(""));
        assertEquals(AiPolicy.SCHEMA_ONLY, AiPolicy.parse("   "));
        assertEquals(AiPolicy.SCHEMA_ONLY, AiPolicy.DEFAULT);
    }

    @Test
    public void parse_invalid_value_fails_fast() {
        assertThrows(IllegalArgumentException.class, () -> AiPolicy.parse("garbage"));
        assertThrows(IllegalArgumentException.class, () -> AiPolicy.parse("raw"));
    }

    /* ---------------------------- resolve ---------------------------- */

    @Test
    public void resolve_env_beats_property_beats_default() {
        // env wins
        assertEquals(AiPolicy.FULL, AiPolicy.resolve(map(AiPolicy.ENV, "full"), map(AiPolicy.PROP, "aggregated")));
        // property used when env absent
        assertEquals(AiPolicy.AGGREGATED, AiPolicy.resolve(map(), map(AiPolicy.PROP, "aggregated")));
        // default when neither
        assertEquals(AiPolicy.SCHEMA_ONLY, AiPolicy.resolve(map(), map()));
        // blank env falls through to property
        assertEquals(AiPolicy.AGGREGATED, AiPolicy.resolve(map(AiPolicy.ENV, "  "), map(AiPolicy.PROP, "aggregated")));
    }

    @Test
    public void resolve_invalid_configured_value_throws() {
        assertThrows(IllegalArgumentException.class, () -> AiPolicy.resolve(map(AiPolicy.ENV, "nope"), map()));
    }

    /* -------------------------- assertCanSend ------------------------ */

    @Test
    public void schema_only_permits_only_metadata_and_samples() {
        AiPolicyGuard g = new AiPolicyGuard(AiPolicy.SCHEMA_ONLY);
        g.assertCanSend(AiDataKind.SCHEMA_METADATA); // no throw
        g.assertCanSend(AiDataKind.SAMPLE_MEMBERS); // no throw
        assertThrows(AiPolicyViolation.class, () -> g.assertCanSend(AiDataKind.AGGREGATED_RESULT_VALUES));
        assertThrows(AiPolicyViolation.class, () -> g.assertCanSend(AiDataKind.RAW_ROW_DATA));
        assertTrue(g.canSend(AiDataKind.SCHEMA_METADATA));
        assertFalse(g.canSend(AiDataKind.AGGREGATED_RESULT_VALUES));
    }

    @Test
    public void aggregated_permits_values_but_not_raw_rows() {
        AiPolicyGuard g = new AiPolicyGuard(AiPolicy.AGGREGATED);
        g.assertCanSend(AiDataKind.SCHEMA_METADATA);
        g.assertCanSend(AiDataKind.AGGREGATED_RESULT_VALUES); // no throw
        assertThrows(AiPolicyViolation.class, () -> g.assertCanSend(AiDataKind.RAW_ROW_DATA));
    }

    @Test
    public void full_permits_everything() {
        AiPolicyGuard g = new AiPolicyGuard(AiPolicy.FULL);
        for (AiDataKind k : AiDataKind.values()) {
            g.assertCanSend(k); // none throw
            assertTrue(g.canSend(k));
        }
    }

    @Test
    public void violation_carries_kind_current_and_required() {
        AiPolicyGuard g = new AiPolicyGuard(AiPolicy.SCHEMA_ONLY);
        AiPolicyViolation v = assertThrows(AiPolicyViolation.class, () -> g.assertCanSend(AiDataKind.RAW_ROW_DATA));
        assertEquals(AiDataKind.RAW_ROW_DATA, v.getKind());
        assertEquals(AiPolicy.SCHEMA_ONLY, v.getCurrent());
        assertEquals(AiPolicy.FULL, v.getRequired());
    }

    @Test
    public void from_resolves_and_null_policy_defaults() {
        assertEquals(
                AiPolicy.AGGREGATED,
                AiPolicyGuard.from(map(AiPolicy.ENV, "aggregated"), map()).current());
        assertEquals(AiPolicy.SCHEMA_ONLY, new AiPolicyGuard((AiPolicy) null).current());
    }
}
