/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.saiku.olap.dto.SaikuMember;

/**
 * saiku#835 — the shared hidden-member filter behind the discovery endpoints
 * (measures #778, root members + member children #835). Visible-only by
 * default; {@code includeHidden=true} returns everything; null visibility is
 * treated as visible (legacy fixtures / providers without the property).
 */
public class OlapDiscoverHiddenMemberFilterTest {

    private static SaikuMember member(String name, Boolean visible) {
        SaikuMember m = new SaikuMember(
                name,
                "[Store].[Stores].[" + name + "]",
                name,
                null,
                "[Store]",
                "[Store].[Stores]",
                "[Store].[Stores].[Store Country]",
                false);
        m.setVisible(visible);
        return m;
    }

    @Test
    public void hiddenMembersDroppedByDefault() {
        List<SaikuMember> in = Arrays.asList(member("USA", true), member("Unknown", false), member("Legacy", null));
        List<SaikuMember> out = OlapDiscoverResource.filterHidden(in, false);
        assertEquals(2, out.size());
        assertEquals("USA", out.get(0).getName());
        assertEquals("null visibility treated as visible", "Legacy", out.get(1).getName());
    }

    @Test
    public void includeHiddenReturnsTheListUntouched() {
        List<SaikuMember> in = new ArrayList<>(Arrays.asList(member("USA", true), member("Unknown", false)));
        assertSame("opt-in returns the original list", in, OlapDiscoverResource.filterHidden(in, true));
    }

    @Test
    public void nullListIsNullSafe() {
        assertNull(OlapDiscoverResource.filterHidden(null, false));
        assertNull(OlapDiscoverResource.filterHidden(null, true));
    }
}
