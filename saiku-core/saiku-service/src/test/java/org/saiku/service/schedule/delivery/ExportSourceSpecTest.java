/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.delivery;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** Payload parsing for the {@code EXPORT_DELIVERY} job (saiku#1987). */
public class ExportSourceSpecTest {

    private static Map<String, Object> payload(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void parsesASavedQueryCsvSource() {
        ExportSourceSpec spec = ExportSourceSpec.fromPayload(
                payload("type", "saved_query_csv", "savedQuery", " /sales/q1.sai ", "fileName", "weekly.csv"));
        assertEquals("SAVED_QUERY_CSV", spec.type());
        assertEquals("/sales/q1.sai", spec.savedQueryPath());
        assertEquals("weekly.csv", spec.fileName());
    }

    @Test
    public void anAbsentFileNameMeansDeriveItFromTheQuery() {
        ExportSourceSpec spec =
                ExportSourceSpec.fromPayload(payload("type", "SAVED_QUERY_CSV", "savedQuery", "/sales/q1.sai"));
        assertNull(spec.fileName());
    }

    @Test
    public void rejectsAMissingOrNonObjectSource() {
        for (Object bad : new Object[] {null, "a string", 42, Map.of()}) {
            try {
                ExportSourceSpec.fromPayload(bad);
                fail("expected a rejection of " + bad);
            } catch (IllegalArgumentException expected) {
                // correct
            }
        }
    }

    @Test
    public void rejectsAMissingOrUnknownType() {
        try {
            ExportSourceSpec.fromPayload(payload("savedQuery", "/sales/q1.sai"));
            fail("expected a rejection of the missing type");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("payload.source.type"));
        }
        try {
            ExportSourceSpec.fromPayload(payload("type", "DASHBOARD_PDF", "savedQuery", "/sales/q1.sai"));
            fail("expected a rejection of the unknown type");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("DASHBOARD_PDF"));
        }
    }

    @Test
    public void rejectsAMissingQueryPath() {
        try {
            ExportSourceSpec.fromPayload(payload("type", "SAVED_QUERY_CSV"));
            fail("expected a rejection of the missing query path");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("savedQuery"));
        }
    }

    @Test
    public void ignoresANonStringQueryPath() {
        try {
            ExportSourceSpec.fromPayload(payload("type", "SAVED_QUERY_CSV", "savedQuery", 7));
            fail("expected a rejection of the non-string query path");
        } catch (IllegalArgumentException expected) {
            // correct
        }
    }
}
