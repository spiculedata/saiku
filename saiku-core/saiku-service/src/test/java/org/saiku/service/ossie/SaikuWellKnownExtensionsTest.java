/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import bi.saiku.ossie.model.CustomExtension;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Parsing behaviour + backward-compatibility guarantees for the SAIKU vendor well-knowns
 * (saiku#1409): {@code display}, {@code roles}, and the graded {@code pii} shape.
 */
public class SaikuWellKnownExtensionsTest {

    @Test
    public void nullOrEmptyReturnsEmpty() {
        assertTrue(SaikuWellKnownExtensions.read(null).isEmpty());
        assertTrue(SaikuWellKnownExtensions.read(List.of()).isEmpty());
    }

    @Test
    public void nonSaikuVendorReturnsEmpty() {
        SaikuWellKnownExtensions.Parsed w =
                SaikuWellKnownExtensions.read(List.of(saiku("BIRST", "{\"display\":{\"caption\":\"X\"}}")));
        assertTrue(w.isEmpty());
    }

    @Test
    public void malformedJsonReturnsEmpty() {
        SaikuWellKnownExtensions.Parsed w = SaikuWellKnownExtensions.read(List.of(saiku("SAIKU", "not json at all")));
        assertTrue(w.isEmpty());
    }

    @Test
    public void parsesDisplay() {
        SaikuWellKnownExtensions.Parsed w = SaikuWellKnownExtensions.read(
                List.of(
                        saiku(
                                "SAIKU",
                                "{\"display\":{\"caption\":\"Net Revenue\",\"format\":\"$#,##0.00\",\"unit\":\"USD\",\"hidden\":false}}")));
        assertNotNull(w.display());
        assertEquals("Net Revenue", w.display().caption());
        assertEquals("$#,##0.00", w.display().format());
        assertEquals("USD", w.display().unit());
        assertFalse(w.display().hidden());
    }

    @Test
    public void parsesHiddenOnly() {
        SaikuWellKnownExtensions.Parsed w =
                SaikuWellKnownExtensions.read(List.of(saiku("SAIKU", "{\"display\":{\"hidden\":true}}")));
        assertNotNull(w.display());
        assertTrue(w.display().hidden());
        assertNull(w.display().caption());
    }

    @Test
    public void displayWithOnlyDefaultsReturnsNull() {
        // `hidden:false` with no other fields is functionally empty — no overlay to apply.
        SaikuWellKnownExtensions.Parsed w =
                SaikuWellKnownExtensions.read(List.of(saiku("SAIKU", "{\"display\":{\"hidden\":false}}")));
        assertNull(w.display());
    }

    @Test
    public void parsesRoles() {
        SaikuWellKnownExtensions.Parsed w = SaikuWellKnownExtensions.read(List.of(
                saiku("SAIKU", "{\"roles\":{\"allow\":[\"ROLE_SALES\",\"ROLE_ANALYST\"],\"deny\":[\"ROLE_EMBED\"]}}")));
        assertNotNull(w.roles());
        assertEquals(Set.of("ROLE_SALES", "ROLE_ANALYST"), w.roles().allow());
        assertEquals(Set.of("ROLE_EMBED"), w.roles().deny());
    }

    @Test
    public void rolesEmptyBothReturnsNull() {
        SaikuWellKnownExtensions.Parsed w =
                SaikuWellKnownExtensions.read(List.of(saiku("SAIKU", "{\"roles\":{\"allow\":[],\"deny\":[]}}")));
        assertNull(w.roles());
    }

    @Test
    public void rolesPermits() {
        SaikuWellKnownExtensions.Roles r =
                new SaikuWellKnownExtensions.Roles(Set.of("ROLE_SALES", "ROLE_ANALYST"), Set.of("ROLE_EMBED"));

        assertTrue(r.permits(Set.of("ROLE_SALES")));
        assertTrue(r.permits(Set.of("ROLE_ANALYST", "ROLE_ADMIN")));
        assertFalse("no allowed role", r.permits(Set.of("ROLE_ADMIN")));
        assertFalse("deny overrides allow", r.permits(Set.of("ROLE_SALES", "ROLE_EMBED")));
        assertFalse("null caller = no roles", r.permits(null));
    }

    @Test
    public void emptyAllowMeansAllowAll() {
        SaikuWellKnownExtensions.Roles r = new SaikuWellKnownExtensions.Roles(Set.of(), Set.of("ROLE_EMBED"));
        assertTrue(r.permits(Set.of("ROLE_ANYTHING")));
        assertTrue(r.permits(Set.of()));
        assertFalse(r.permits(Set.of("ROLE_EMBED", "ROLE_OTHER")));
    }

    @Test
    public void parsesLegacyPiiBoolean() {
        // Backwards compat: `pii: true` still means REDACT.
        SaikuWellKnownExtensions.Parsed w = SaikuWellKnownExtensions.read(List.of(saiku("SAIKU", "{\"pii\":true}")));
        assertEquals(SaikuWellKnownExtensions.PiiLevel.REDACT, w.pii());
    }

    @Test
    public void piiFalseMeansAbsent() {
        SaikuWellKnownExtensions.Parsed w = SaikuWellKnownExtensions.read(List.of(saiku("SAIKU", "{\"pii\":false}")));
        assertNull(w.pii());
    }

    @Test
    public void parsesGradedPiiLevel() {
        assertEquals(
                SaikuWellKnownExtensions.PiiLevel.MASK,
                SaikuWellKnownExtensions.read(List.of(saiku("SAIKU", "{\"pii\":{\"level\":\"mask\"}}")))
                        .pii());
        assertEquals(
                SaikuWellKnownExtensions.PiiLevel.HASH,
                SaikuWellKnownExtensions.read(List.of(saiku("SAIKU", "{\"pii\":{\"level\":\"HASH\"}}")))
                        .pii());
        assertEquals(
                SaikuWellKnownExtensions.PiiLevel.REDACT,
                SaikuWellKnownExtensions.read(List.of(saiku("SAIKU", "{\"pii\":{\"level\":\"redact\"}}")))
                        .pii());
    }

    @Test
    public void unknownPiiLevelReturnsNull() {
        assertNull(SaikuWellKnownExtensions.read(List.of(saiku("SAIKU", "{\"pii\":{\"level\":\"invisible\"}}")))
                .pii());
    }

    @Test
    public void allThreeWellKnownsCoexist() {
        SaikuWellKnownExtensions.Parsed w = SaikuWellKnownExtensions.read(List.of(saiku(
                "SAIKU",
                "{\"display\":{\"caption\":\"Rev\"},"
                        + "\"roles\":{\"allow\":[\"ROLE_A\"]},"
                        + "\"pii\":{\"level\":\"mask\"}}")));
        assertNotNull(w.display());
        assertNotNull(w.roles());
        assertNotNull(w.pii());
        assertFalse(w.isEmpty());
    }

    @Test
    public void firstSaikuEntryWins() {
        // Two SAIKU entries — the first one is authoritative. Downstream code doesn't need to
        // merge across entries; that would create ordering ambiguity.
        SaikuWellKnownExtensions.Parsed w = SaikuWellKnownExtensions.read(List.of(
                saiku("SAIKU", "{\"display\":{\"caption\":\"First\"}}"),
                saiku("SAIKU", "{\"display\":{\"caption\":\"Second\"}}")));
        assertEquals("First", w.display().caption());
    }

    @Test
    public void unknownKeysInSaikuBlobAreIgnored() {
        // Unknown keys under SAIKU shouldn't crash parsing — the annotation namespace is
        // deliberately extensible.
        SaikuWellKnownExtensions.Parsed w = SaikuWellKnownExtensions.read(
                List.of(saiku("SAIKU", "{\"display\":{\"caption\":\"X\"},\"future_extension\":42}")));
        assertNotNull(w.display());
        assertEquals("X", w.display().caption());
    }

    private static CustomExtension saiku(String vendor, String data) {
        CustomExtension ext = new CustomExtension();
        ext.setVendorName(vendor);
        ext.setData(data);
        return ext;
    }
}
