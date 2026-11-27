/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * saiku#2618 — unit coverage for the feature-toggle registry: the restriction-
 * only grant semantics (absent = unrestricted, empty = admins only, listed =
 * those roles plus admins), the admin superuser rule, the atomic sorted save,
 * the mtime rescan and the never-widen posture of an unparseable file.
 */
public class FeatureTogglesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path file;

    @Before
    public void setUp() throws Exception {
        file = tmp.getRoot().toPath().resolve("feature-toggles.json");
    }

    private static final Set<String> USER = Set.of("ROLE_USER");
    private static final Set<String> ANALYST = Set.of("ROLE_USER", "ROLE_ANALYST");
    private static final Set<String> ADMIN = Set.of("ROLE_USER", "ROLE_ADMIN");

    @Test
    public void absent_config_means_every_feature_unrestricted() {
        FeatureToggles toggles = new FeatureToggles(file);
        for (FeatureCatalogue feature : FeatureCatalogue.values()) {
            assertNull("absent config -> null grant", toggles.grantOf(feature.key));
            assertTrue("everyone sees an unrestricted feature", toggles.isEnabled(feature.key, USER));
            assertTrue(toggles.isEnabled(feature.key, Set.of()));
        }
    }

    @Test
    public void empty_grant_is_admins_only() throws Exception {
        FeatureToggles toggles = new FeatureToggles(file);
        toggles.save(Map.of("notebooks", List.of()));
        assertTrue("admins always pass", toggles.isEnabled("notebooks", ADMIN));
        assertFalse("an ungranted user is denied", toggles.isEnabled("notebooks", USER));
        assertFalse("no authorities at all is denied", toggles.isEnabled("notebooks", Set.of()));
        assertTrue("other features stay unrestricted", toggles.isEnabled("dashboards", USER));
    }

    @Test
    public void adminSuperuserRuleLivesInIsEnabledItself() throws Exception {
        // Not just the gate filter's concern: the session endpoint computes the
        // SPA's feature map straight from isEnabled, so the ROLE_ADMIN superuser
        // rule must hold here — an admin's map is all-true even for features
        // granted to other roles.
        FeatureToggles toggles = new FeatureToggles(file);
        toggles.save(Map.of("notebooks", List.of("ROLE_SOMEONE_ELSE")));
        assertTrue(toggles.isEnabled("notebooks", Set.of("ROLE_ADMIN")));
        assertTrue(toggles.isEnabled("notebooks", ADMIN));
    }

    @Test
    public void listed_grant_is_those_roles_plus_admins() throws Exception {
        FeatureToggles toggles = new FeatureToggles(file);
        toggles.save(Map.of("notebooks", List.of("ROLE_NOTEBOOKS")));
        assertTrue(toggles.isEnabled("notebooks", ADMIN));
        assertTrue("a granted role passes", toggles.isEnabled("notebooks", Set.of("ROLE_NOTEBOOKS")));
        assertTrue("a granted role among others passes", toggles.isEnabled("notebooks", ANALYST));
        assertFalse("an ungranted role is denied", toggles.isEnabled("notebooks", USER));
    }

    @Test
    public void unknown_key_never_restricts_anything() throws Exception {
        FeatureToggles toggles = new FeatureToggles(file);
        toggles.save(Map.of("not-a-feature", List.of()));
        assertTrue(toggles.isEnabled("notebooks", USER));
        assertTrue(toggles.isEnabled("not-a-feature", USER));
    }

    @Test
    public void save_is_sorted_and_drops_nulls() throws Exception {
        FeatureToggles toggles = new FeatureToggles(file);
        Map<String, List<String>> grants = new LinkedHashMap<>();
        grants.put("notebooks", List.of("ROLE_Z", "ROLE_A"));
        grants.put("apps", null);
        Map<String, List<String>> stored = toggles.save(grants);
        assertEquals(List.of("ROLE_A", "ROLE_Z"), stored.get("notebooks"));
        assertFalse("null grants are dropped, not stored as admins-only", stored.containsKey("apps"));
        assertTrue(Files.exists(file));
        String raw = Files.readString(file);
        assertTrue(raw.contains("\"notebooks\""));
        assertFalse("an unrestricted feature leaves no dead config", raw.contains("\"apps\""));
    }

    @Test
    public void reset_to_unrestricted_removes_the_key() throws Exception {
        FeatureToggles toggles = new FeatureToggles(file);
        toggles.save(Map.of("notebooks", List.of("ROLE_NOTEBOOKS")));
        Map<String, List<String>> current = new LinkedHashMap<>(toggles.allGrants());
        current.remove("notebooks");
        toggles.save(current);
        assertNull("reset restores unrestricted", toggles.grantOf("notebooks"));
        assertTrue(toggles.isEnabled("notebooks", USER));
    }

    @Test
    public void rescans_when_the_file_changes_underneath() throws Exception {
        FeatureToggles toggles = new FeatureToggles(file);
        toggles.save(Map.of("notebooks", List.of("ROLE_NOTEBOOKS")));
        assertTrue(toggles.isEnabled("notebooks", Set.of("ROLE_NOTEBOOKS")));
        // Simulate an operator editing the file directly (the mtime signature the
        // registry re-checks on every read).
        Thread.sleep(1100); // mtime granularity can be coarse; a full second is safe
        toggles.save(Map.of("notebooks", List.of()));
        assertFalse("the rescan picks up the new grant", toggles.isEnabled("notebooks", Set.of("ROLE_NOTEBOOKS")));
        assertTrue("admins still pass", toggles.isEnabled("notebooks", ADMIN));
    }

    @Test
    public void unparseable_file_degrades_to_unrestricted_and_never_throws() throws Exception {
        FeatureToggles toggles = new FeatureToggles(file);
        toggles.save(Map.of("notebooks", List.of("ROLE_NOTEBOOKS")));
        Files.writeString(file, "{ this is not json");
        // grantOf/isEnabled read the file fresh; a broken file must not throw.
        assertNull("a broken file reads as unrestricted", toggles.grantOf("notebooks"));
        assertTrue(toggles.isEnabled("notebooks", USER));
        assertNotNull(toggles.allGrants());
    }

    @Test
    public void catalogue_covers_the_main_ui_features() {
        assertNotNull(FeatureCatalogue.of("dashboards"));
        assertNotNull(FeatureCatalogue.of("apps"));
        assertNotNull(FeatureCatalogue.of("notebooks"));
        assertNotNull(FeatureCatalogue.of("workbench"));
        assertNotNull(FeatureCatalogue.of("sql-workbench"));
        assertNull(FeatureCatalogue.of("admin"));
        assertNull(FeatureCatalogue.of(null));
        // The MDX workbench shares the query API with the workspace — its
        // enforcement is UI-level only, and the catalogue must say so honestly.
        assertFalse(FeatureCatalogue.of("workbench").serverEnforced);
        assertTrue("a UI-only feature names no REST prefixes", FeatureCatalogue.of("workbench").restPrefixes.isEmpty());
        for (FeatureCatalogue f : FeatureCatalogue.values()) {
            if (f.serverEnforced) {
                assertFalse("a server-enforced feature names its REST prefixes", f.restPrefixes.isEmpty());
            }
        }
    }
}
