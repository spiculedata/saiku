/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.digest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.saiku.repository.RepositoryException;
import org.saiku.service.datasource.IDatasourceManager;

/**
 * The per-user digest opt-out (saiku#1119). Locks the three states that matter: absent preferences (the
 * state of every account that never touched the setting) means "send"; an explicit flag means
 * "suppress"; and an unreadable document does NOT silently stop every digest on the instance.
 */
public class DigestOptOutTest {

    private static final String PREF_PATH = "/homes/ada/.preferences.json";

    /**
     * A stand-in repository: only {@code getInternalFileData} is exercised, and an unknown path throws
     * exactly as the real one does for an absent file (which is how every fresh account looks).
     */
    private static IDatasourceManager repo(Map<String, String> files) {
        return (IDatasourceManager) Proxy.newProxyInstance(
                DigestOptOutTest.class.getClassLoader(),
                new Class<?>[] {IDatasourceManager.class},
                (proxy, method, args) -> {
                    if ("getInternalFileData".equals(method.getName())) {
                        String data = files.get(String.valueOf(args[0]));
                        if (data == null) {
                            throw new RepositoryException("no such file: " + args[0]);
                        }
                        return data;
                    }
                    if ("toString".equals(method.getName())) {
                        return "stubRepository";
                    }
                    throw new UnsupportedOperationException("not used by the opt-out check: " + method.getName());
                });
    }

    @Test
    public void anAccountWithNoPreferencesHasNotOptedOut() {
        assertFalse(DigestOptOut.isOptedOut(repo(new HashMap<>()), "ada"));
    }

    @Test
    public void anExplicitFlagOptsOut() {
        Map<String, String> files = Map.of(PREF_PATH, "{\"dashboardDigestOptOut\":true}");
        assertTrue(DigestOptOut.isOptedOut(repo(files), "ada"));
    }

    @Test
    public void anExplicitFalseDoesNotOptOut() {
        Map<String, String> files = Map.of(PREF_PATH, "{\"dashboardDigestOptOut\":false}");
        assertFalse(DigestOptOut.isOptedOut(repo(files), "ada"));
    }

    @Test
    public void anUnrelatedPreferenceDoesNotOptOut() {
        Map<String, String> files = Map.of(PREF_PATH, "{\"onboardingSeen\":true}");
        assertFalse(DigestOptOut.isOptedOut(repo(files), "ada"));
    }

    @Test
    public void thePreferenceKeyIsTheOneTheApiShares() {
        assertEquals("dashboardDigestOptOut", DigestOptOut.KEY);
    }

    @Test
    public void aCorruptDocumentDoesNotStopEveryDigest() {
        Map<String, String> files = Map.of(PREF_PATH, "{{{ not json");
        assertFalse(DigestOptOut.isOptedOut(repo(files), "ada"));
    }

    @Test
    public void anUnusableUsernameCannotAddressAnyone() {
        Map<String, String> files = Map.of(PREF_PATH, "{\"dashboardDigestOptOut\":true}");
        assertFalse(DigestOptOut.isOptedOut(repo(files), "///"));
        assertFalse(DigestOptOut.isOptedOut(repo(files), ""));
        assertFalse(DigestOptOut.isOptedOut(repo(files), null));
    }

    @Test
    public void withNoRepositoryThereIsNothingToOptOutOf() {
        assertFalse(DigestOptOut.isOptedOut(null, "ada"));
    }

    @Test
    public void withNoSecurityPrincipalThereIsNobodyToOptOut() {
        assertFalse(DigestOptOut.isOptedOut(repo(new HashMap<>()), (String) null));
    }

    @Test
    public void theLookupTargetsTheCallersOwnDocumentOnly() {
        // Only "ada"'s document carries the flag; asking about another user must not see it.
        Map<String, String> files = new HashMap<>();
        files.put(PREF_PATH, "{\"dashboardDigestOptOut\":true}");
        IDatasourceManager dm = repo(files);
        assertTrue(DigestOptOut.isOptedOut(dm, "ada"));
        assertFalse(DigestOptOut.isOptedOut(dm, "grace"));
    }
}
