/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#1868 — per-user preferences. The two things that matter: a caller can only ever reach their
 * OWN document, and concurrent writes of different keys must not discard each other.
 */
public class UserPreferencesResourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** In-memory stand-in for the repository, keyed by path so cross-user leakage is visible. */
    private final Map<String, String> store = new HashMap<>();

    private UserPreferencesResource resource;

    @Before
    public void setUp() {
        store.clear();
        resource = new UserPreferencesResource();
        resource.setDatasourceManager(stubManager());
    }

    /**
     * IDatasourceManager has 48 methods and this resource uses exactly two, so a proxy keeps the
     * test about preferences rather than about stubbing. Reads of an absent path THROW, which is
     * what the real repository does — that behaviour is the reason readFor() has a catch at all.
     */
    private org.saiku.service.datasource.IDatasourceManager stubManager() {
        return (org.saiku.service.datasource.IDatasourceManager) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {org.saiku.service.datasource.IDatasourceManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInternalFileData" -> {
                        String v = store.get((String) args[0]);
                        if (v == null) {
                            throw new org.saiku.repository.RepositoryException("not found: " + args[0]);
                        }
                        yield v;
                    }
                    case "saveInternalFile" -> {
                        store.put((String) args[0], String.valueOf(args[1]));
                        yield null;
                    }
                    default -> null;
                });
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(username, "x", java.util.List.of()));
    }

    @Test
    public void anUnauthenticatedCallerGetsNothing() {
        assertEquals(401, resource.get().getStatus());
        assertEquals(401, resource.put("{\"a\":1}").getStatus());
    }

    /** A brand-new account has no document, and that is normal — an empty object, not a 404. */
    @Test
    public void anAccountWithNoPreferencesReadsAsAnEmptyObject() {
        loginAs("admin");

        Response r = resource.get();

        assertEquals(200, r.getStatus());
        assertEquals("{}", r.getEntity());
    }

    @Test
    public void aWrittenPreferenceIsReadBack() throws Exception {
        loginAs("admin");

        resource.put("{\"tourDone\":true}");

        assertTrue(MAPPER.readTree((String) resource.get().getEntity())
                .path("tourDone")
                .asBoolean());
    }

    /**
     * THE security property. Two accounts must never see each other's preferences — the username
     * comes from the security context, so there is no request field to tamper with, but the storage
     * path has to actually separate them.
     */
    @Test
    public void oneUserCannotSeeAnothersPreferences() throws Exception {
        loginAs("alice");
        resource.put("{\"secret\":\"alice-only\"}");

        loginAs("bob");
        String bobs = (String) resource.get().getEntity();

        assertEquals("{}", bobs);
        assertFalse("bob could read alice's preferences", bobs.contains("alice-only"));
    }

    /** A merge, not a replace: two tabs writing different keys must not clobber each other. */
    @Test
    public void writingOneKeyLeavesTheOthersAlone() throws Exception {
        loginAs("admin");
        resource.put("{\"tourDone\":true}");

        resource.put("{\"theme\":\"dark\"}");

        var doc = MAPPER.readTree((String) resource.get().getEntity());
        assertTrue(
                "the first key was clobbered by the second",
                doc.path("tourDone").asBoolean());
        assertEquals("dark", doc.path("theme").asText());
    }

    /** Setting a key to null is the only way a client can delete one. */
    @Test
    public void aNullValueRemovesTheKey() throws Exception {
        loginAs("admin");
        resource.put("{\"tourDone\":true,\"theme\":\"dark\"}");

        resource.put("{\"tourDone\":null}");

        var doc = MAPPER.readTree((String) resource.get().getEntity());
        assertTrue("tourDone should have been removed", doc.path("tourDone").isMissingNode());
        assertEquals("dark", doc.path("theme").asText());
    }

    @Test
    public void aNonObjectBodyIsRejected() {
        loginAs("admin");

        assertEquals(400, resource.put("[1,2,3]").getStatus());
        assertEquals(400, resource.put("\"just a string\"").getStatus());
        assertEquals(400, resource.put("not json at all").getStatus());
    }

    /** A corrupt stored document must not lock a user out of their own preferences forever. */
    @Test
    public void acorruptStoredDocumentIsRecoveredFromRatherThanFatal() throws Exception {
        loginAs("admin");
        store.put(org.saiku.service.user.UserPreferences.pathFor("admin"), "}{ not json");

        Response r = resource.put("{\"tourDone\":true}");

        assertEquals(200, r.getStatus());
        assertTrue(MAPPER.readTree((String) r.getEntity()).path("tourDone").asBoolean());
    }

    /** This is a preferences bag, not free storage. */
    @Test
    public void anOversizedDocumentIsRefused() {
        loginAs("admin");
        String big = "{\"blob\":\"" + "x".repeat(org.saiku.service.user.UserPreferences.MAX_BYTES + 1) + "\"}";

        assertEquals(413, resource.put(big).getStatus());
    }
}
