/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.Test;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.util.security.MondrianRolePolicy.Access;
import org.saiku.service.util.security.MondrianRolePolicy.Mode;

/** saiku#779 — the shared Spring-authority → Mondrian-role resolution. */
public class MondrianRolePolicyTest {

    private static SaikuDatasource ds(String enabled, String type, String mapping) {
        Properties p = new Properties();
        if (enabled != null) {
            p.setProperty("security.enabled", enabled);
        }
        if (type != null) {
            p.setProperty("security.type", type);
        }
        if (mapping != null) {
            p.setProperty("security.mapping", mapping);
        }
        return new SaikuDatasource("foodmart", SaikuDatasource.Type.OLAP, p);
    }

    @Test
    public void modeOf_readsEnabledAndType() {
        assertEquals(Mode.DISABLED, MondrianRolePolicy.modeOf(ds(null, "lookup", null)));
        assertEquals(Mode.DISABLED, MondrianRolePolicy.modeOf(ds("false", "lookup", null)));
        assertEquals(Mode.ONE2ONE, MondrianRolePolicy.modeOf(ds("true", "one2one", null)));
        assertEquals(Mode.LOOKUP, MondrianRolePolicy.modeOf(ds("true", "lookup", null)));
        assertEquals(Mode.PASSTHROUGH, MondrianRolePolicy.modeOf(ds("true", "passthrough", null)));
        assertEquals(Mode.UNKNOWN, MondrianRolePolicy.modeOf(ds("true", "bogus", null)));
        assertEquals(Mode.UNKNOWN, MondrianRolePolicy.modeOf(ds("true", null, null)));
        assertEquals(Mode.DISABLED, MondrianRolePolicy.modeOf(null));
    }

    @Test
    public void parseMapping_groupsRepeatedSpringRolesAndSkipsMalformedEntries() {
        Map<String, List<String>> m =
                MondrianRolePolicy.parseMapping("ROLE_SALES=Sales;ROLE_SALES=Region West;junk;a=b=c;ROLE_HR=HR");
        assertEquals(List.of("Sales", "Region West"), m.get("ROLE_SALES"));
        assertEquals(List.of("HR"), m.get("ROLE_HR"));
        assertEquals(2, m.size());
    }

    @Test
    public void formatMapping_roundTrips() {
        String wire = "ROLE_SALES=Sales;ROLE_SALES=Region West;ROLE_HR=HR";
        assertEquals(wire, MondrianRolePolicy.formatMapping(MondrianRolePolicy.parseMapping(wire)));
    }

    @Test
    public void withGrants_replacesDedupesAndRemovesOnEmpty() {
        Map<String, List<String>> m = MondrianRolePolicy.parseMapping("ROLE_SALES=Sales;ROLE_HR=HR");
        Map<String, List<String>> replaced =
                MondrianRolePolicy.withGrants(m, "ROLE_SALES", List.of("West", "West", "East"));
        assertEquals(List.of("West", "East"), replaced.get("ROLE_SALES"));
        assertEquals(List.of("Sales"), m.get("ROLE_SALES")); // input untouched

        Map<String, List<String>> removed = MondrianRolePolicy.withGrants(m, "ROLE_HR", List.of());
        assertFalse(removed.containsKey("ROLE_HR"));
        assertEquals("ROLE_SALES=Sales", MondrianRolePolicy.formatMapping(removed));
    }

    @Test
    public void isValidRoleName_rejectsDelimitersAndBlank() {
        assertTrue(MondrianRolePolicy.isValidRoleName("ROLE_SALES"));
        assertTrue(MondrianRolePolicy.isValidRoleName("Region West"));
        assertFalse(MondrianRolePolicy.isValidRoleName(null));
        assertFalse(MondrianRolePolicy.isValidRoleName(""));
        assertFalse(MondrianRolePolicy.isValidRoleName(" padded"));
        assertFalse(MondrianRolePolicy.isValidRoleName("a;b"));
        assertFalse(MondrianRolePolicy.isValidRoleName("a=b"));
        assertFalse(MondrianRolePolicy.isValidRoleName("a,b"));
        assertFalse(MondrianRolePolicy.isValidRoleName("a\nb"));
    }

    @Test
    public void resolve_one2one_intersectsAvailableRolesInAuthorityOrder() {
        List<String> r = MondrianRolePolicy.resolveMondrianRoles(
                Mode.ONE2ONE, List.of("ROLE_USER", "B", "A"), List.of("A", "B", "C"), Map.of());
        assertEquals(List.of("B", "A"), r);
    }

    @Test
    public void resolve_lookup_unionsMappedRolesWithoutDuplicates() {
        Map<String, List<String>> m = MondrianRolePolicy.parseMapping("R1=X;R1=Y;R2=Y;R2=Z");
        assertEquals(
                List.of("X", "Y", "Z"),
                MondrianRolePolicy.resolveMondrianRoles(Mode.LOOKUP, List.of("R1", "R2", "R3"), null, m));
    }

    @Test
    public void resolve_isEmptyForModesWithoutMondrianRoles() {
        assertTrue(MondrianRolePolicy.resolveMondrianRoles(Mode.DISABLED, List.of("A"), List.of("A"), Map.of())
                .isEmpty());
        assertTrue(MondrianRolePolicy.resolveMondrianRoles(Mode.PASSTHROUGH, List.of("A"), List.of("A"), Map.of())
                .isEmpty());
        assertTrue(MondrianRolePolicy.resolveMondrianRoles(Mode.ONE2ONE, null, List.of("A"), Map.of())
                .isEmpty());
    }

    @Test
    public void preview_scopedWhenARoleResolves() {
        MondrianRolePolicy.Resolution r = MondrianRolePolicy.preview(
                ds("true", "lookup", "ROLE_SALES=Sales"), List.of("ROLE_SALES"), null, false);
        assertEquals(Access.SCOPED, r.getAccess());
        assertEquals(List.of("Sales"), r.getMondrianRoles());
    }

    /** Mirrors saiku#1968: no role resolved is DENIED for a non-admin, root for an admin. */
    @Test
    public void preview_noResolvedRole_isDeniedUnlessAdmin() {
        SaikuDatasource d = ds("true", "one2one", null);
        assertEquals(
                Access.DENIED,
                MondrianRolePolicy.preview(d, List.of("ROLE_USER"), List.of("Sales"), false)
                        .getAccess());
        assertEquals(
                Access.FULL_ADMIN,
                MondrianRolePolicy.preview(d, List.of("ROLE_ADMIN"), List.of("Sales"), true)
                        .getAccess());
    }

    /**
     * Enforcement denies when a resolved role can't be applied (the schema doesn't declare it), so
     * the preview must say DENIED too — for an admin as well. When the schema's roles are unknown
     * (null), the preview can only report the mapping.
     */
    @Test
    public void preview_lookupGrantToUndeclaredRole_isDenied() {
        SaikuDatasource d = ds("true", "lookup", "ROLE_USER=Ghost");

        assertEquals(
                MondrianRolePolicy.Access.DENIED,
                MondrianRolePolicy.preview(d, List.of("ROLE_USER"), List.of("Sales"), false)
                        .getAccess());
        assertEquals(
                MondrianRolePolicy.Access.DENIED,
                MondrianRolePolicy.preview(d, List.of("ROLE_USER"), List.of("Sales"), true)
                        .getAccess());
        assertEquals(
                MondrianRolePolicy.Access.SCOPED,
                MondrianRolePolicy.preview(d, List.of("ROLE_USER"), null, false).getAccess());
    }

    @Test
    public void preview_nonRoleModes() {
        assertEquals(
                Access.UNSECURED,
                MondrianRolePolicy.preview(ds(null, null, null), List.of(), null, false)
                        .getAccess());
        assertEquals(
                Access.PASSTHROUGH,
                MondrianRolePolicy.preview(ds("true", "passthrough", null), List.of(), null, false)
                        .getAccess());
        assertEquals(
                Access.UNKNOWN,
                MondrianRolePolicy.preview(ds("true", "weird", null), List.of(), null, false)
                        .getAccess());
    }
}
