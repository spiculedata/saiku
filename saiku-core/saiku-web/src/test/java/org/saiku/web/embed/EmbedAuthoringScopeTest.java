/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.embed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * saiku#1435 — the path algebra every Creator Mode write goes through. These
 * are the tests that decide whether a creator can address their own folder and
 * ONLY their own folder, so they lean adversarial: traversal spellings, Windows
 * tails, absolute paths, encoded separators, over-long names and hostile tenant
 * ids all have to fail closed.
 */
public class EmbedAuthoringScopeTest {

    private static final String OWNER = "admin";

    @Test
    public void home_is_derived_from_owner_and_tenant() {
        assertEquals("/homes/admin/embed-guest-acme", EmbedAuthoringScope.homeFor(OWNER, "acme"));
        assertEquals("/homes/admin/embed-guest-acme-eu", EmbedAuthoringScope.homeFor(OWNER, "acme-eu"));
    }

    @Test
    public void two_tenants_never_share_a_folder() {
        assertFalse(EmbedAuthoringScope.homeFor(OWNER, "a").equals(EmbedAuthoringScope.homeFor(OWNER, "b")));
    }

    @Test
    public void hostiles_tenant_ids_are_refused() {
        for (String bad : new String[] {
            null, "", " ", "..", "../etc", "a/b", "a\\b", ".hidden", "-lead", "a b", "é", "a\nb", "x".repeat(65)
        }) {
            assertFalse("tenantId must be refused: " + bad, EmbedAuthoringScope.isValidTenantId(bad));
            try {
                EmbedAuthoringScope.homeFor(OWNER, bad);
                fail("homeFor must throw for tenantId: " + bad);
            } catch (IllegalArgumentException expected) {
                // fail closed
            }
        }
    }

    @Test
    public void hostiles_owners_are_refused() {
        for (String bad : new String[] {null, "", "../admin", "a/b", "a b", "adm\\in"}) {
            try {
                EmbedAuthoringScope.homeFor(bad, "acme");
                fail("homeFor must throw for owner: " + bad);
            } catch (IllegalArgumentException expected) {
                // fail closed
            }
        }
    }

    @Test
    public void resolve_keeps_the_object_inside_the_scope() {
        String scope = EmbedAuthoringScope.homeFor(OWNER, "acme");
        String path = EmbedAuthoringScope.resolve(scope, "Q1 revenue", EmbedAuthoringScope.QUERY_EXT);
        assertEquals(scope + "/Q1 revenue.saiku", path);
        assertTrue(EmbedAuthoringScope.isWithin(scope, path));
    }

    @Test
    public void resolve_neutralises_traversal_in_the_name() {
        String scope = EmbedAuthoringScope.homeFor(OWNER, "acme");
        for (String hostile : new String[] {
            "../../etc/passwd", "..", "a/../../b", "..\\..\\windows", "/etc/passwd", "a/b", "....//....//x"
        }) {
            String path = EmbedAuthoringScope.resolve(scope, hostile, EmbedAuthoringScope.QUERY_EXT);
            assertTrue(
                    "resolved path escaped the scope for name '" + hostile + "': " + path,
                    EmbedAuthoringScope.isWithin(scope, path));
            // …and the separator count proves nothing nested was created.
            assertEquals(
                    "traversal name must not add a path segment: " + path,
                    4,
                    path.split("/").length - 1);
        }
    }

    @Test
    public void resolve_refuses_a_name_with_nothing_usable() {
        String scope = EmbedAuthoringScope.homeFor(OWNER, "acme");
        for (String blank : new String[] {null, "", "   ", "///", "...", "%%%"}) {
            try {
                EmbedAuthoringScope.resolve(scope, blank, EmbedAuthoringScope.QUERY_EXT);
                fail("resolve must throw for name: " + blank);
            } catch (IllegalArgumentException expected) {
                // fail closed
            }
        }
    }

    @Test
    public void resolve_caps_the_name_length() {
        String scope = EmbedAuthoringScope.homeFor(OWNER, "acme");
        String path = EmbedAuthoringScope.resolve(scope, "x".repeat(500), EmbedAuthoringScope.QUERY_EXT);
        assertTrue(path.length() <= scope.length() + EmbedAuthoringScope.MAX_NAME_LENGTH + 8);
    }

    @Test
    public void resolve_refuses_a_non_plain_extension() {
        String scope = EmbedAuthoringScope.homeFor(OWNER, "acme");
        for (String ext : new String[] {null, "", "saiku", "/etc/passwd", "..", ".saiku/../x"}) {
            try {
                EmbedAuthoringScope.resolve(scope, "ok", ext);
                fail("resolve must throw for extension: " + ext);
            } catch (IllegalArgumentException expected) {
                // fail closed
            }
        }
    }

    @Test
    public void is_within_rejects_sibling_and_escaping_paths() {
        String scope = EmbedAuthoringScope.homeFor(OWNER, "acme");
        assertTrue(EmbedAuthoringScope.isWithin(scope, scope + "/a.saiku"));
        assertTrue("the scope itself is within the scope", EmbedAuthoringScope.isWithin(scope, scope));
        assertFalse(EmbedAuthoringScope.isWithin(scope, "/homes/admin/embed-guest-acmeevil/a.saiku"));
        assertFalse(EmbedAuthoringScope.isWithin(scope, "/homes/admin/embed-guest-other/a.saiku"));
        assertFalse(EmbedAuthoringScope.isWithin(scope, "/homes/admin/a.saiku"));
        assertFalse(EmbedAuthoringScope.isWithin(scope, scope + "/../escape.saiku"));
        assertFalse(EmbedAuthoringScope.isWithin(scope, null));
        assertFalse(EmbedAuthoringScope.isWithin(null, scope + "/a.saiku"));
    }

    @Test
    public void normalise_folds_separators_and_resolves_dot_segments() {
        assertEquals("homes/admin/x.saiku", EmbedAuthoringScope.normalize("/homes//admin/./x.saiku"));
        assertEquals("homes/admin/x.saiku", EmbedAuthoringScope.normalize("homes/admin/other/../x.saiku"));
        assertEquals("homes/admin", EmbedAuthoringScope.normalize("homes\\admin"));
        try {
            EmbedAuthoringScope.normalize("../outside");
            fail("a path climbing above the root must be refused");
        } catch (IllegalArgumentException expected) {
            // fail closed
        }
    }
}
