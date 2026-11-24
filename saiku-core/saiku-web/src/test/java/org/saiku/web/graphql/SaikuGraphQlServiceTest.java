/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.graphql;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.ossie.OssieDiscoverService;
import org.saiku.service.ossie.OssieModelDto;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Boots the {@code SaikuGraphQlService} with a minimal fetchers bean (no wired services)
 * and asserts:
 * <ul>
 *   <li>SDL loads from classpath and the schema compiles.</li>
 *   <li>Introspection (__schema.types) works — this is what codegen clients call.</li>
 *   <li>{@code serverInfo} returns the fields declared in the SDL.</li>
 *   <li>The {@code /schema.graphql} SDL text is exposed.</li>
 * </ul>
 *
 * <p>Integration tests that actually execute a cube query live in the {@code saiku-webapp}
 * module where a live launcher is available.
 */
public class SaikuGraphQlServiceTest {

    private SaikuGraphQlService svc;

    @Before
    public void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        SaikuGraphQlFetchers fetchers = new SaikuGraphQlFetchers(mapper);
        fetchers.setServerVersion("4.6.0-test");
        // No services wired — cubes / ossieModels return empty; cube / executeMdx / executeOssie
        // throw with an "not configured" message caught as GraphQL errors.
        svc = new SaikuGraphQlService(mapper);
        svc.setFetchers(fetchers);
        svc.afterPropertiesSet();
    }

    @After
    public void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private SaikuGraphQlService serviceWithDiscover(OssieDiscoverService discover) {
        ObjectMapper mapper = new ObjectMapper();
        SaikuGraphQlFetchers fetchers = new SaikuGraphQlFetchers(mapper);
        fetchers.setServerVersion("4.6.0-test");
        fetchers.setOssieDiscoverService(discover);
        SaikuGraphQlService s = new SaikuGraphQlService(mapper);
        s.setFetchers(fetchers);
        s.afterPropertiesSet();
        return s;
    }

    private static void authenticateAs(String user, String... authorities) {
        List<SimpleGrantedAuthority> auths = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "n/a", auths));
    }

    /** A model with one unrestricted and one role-restricted field (saiku#1393 shape). */
    static final class StubDiscover extends OssieDiscoverService {
        @Override
        public OssieModelDto getModel(String connectionName) {
            OssieModelDto semantic = new OssieModelDto();
            semantic.setConnection("Geo");
            semantic.setName("GEO");
            OssieModelDto.Dataset geography = new OssieModelDto.Dataset();
            geography.setName("geography");
            OssieModelDto.Field region = new OssieModelDto.Field();
            region.setName("region");
            region.setExpression("REGION");
            geography.getFields().add(region);
            OssieModelDto.Field email = new OssieModelDto.Field();
            email.setName("email");
            email.setExpression("EMAIL_ADDRESS");
            email.setAllowRoles(List.of("ROLE_ADMIN"));
            geography.getFields().add(email);
            semantic.getDatasets().add(geography);
            return semantic;
        }
    }

    /**
     * saiku#2526 — the GraphQL {@code ossieModel} fetcher returns the caller's role-filtered
     * view: a hidden field (and with it its physical column name in {@code expression}) must not
     * be readable through GraphQL when REST GET /ai/ossie/schema hides it.
     */
    @Test
    public void ossieModelOmitsFieldsHiddenFromTheCallerRoles() {
        SaikuGraphQlService discoverSvc = serviceWithDiscover(new StubDiscover());

        authenticateAs("analyst", "ROLE_ANALYST");
        Map<String, Object> result = discoverSvc.execute("{ ossieModel(connection: \"Geo\") }", null, null);
        Map<String, Object> model = (Map<String, Object>) result.get("data");
        assertNotNull(model);
        Map<String, Object> ossie = (Map<String, Object>) model.get("ossieModel");
        assertNotNull(ossie);

        List<Map<String, Object>> datasets = (List<Map<String, Object>>) ossie.get("datasets");
        assertEquals(1, datasets.size());
        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) datasets.get(0).get("fields");
        Set<String> names = new java.util.LinkedHashSet<>();
        for (Map<String, Object> f : fields) names.add((String) f.get("name"));
        assertTrue("unrestricted field stays visible", names.contains("region"));
        assertFalse("hidden field must be omitted", names.contains("email"));
        assertFalse(
                "hidden field's physical column must not leak", ossie.toString().contains("EMAIL_ADDRESS"));

        // The admin role sees everything.
        authenticateAs("admin", "ROLE_ADMIN");
        Map<String, Object> adminResult = discoverSvc.execute("{ ossieModel(connection: \"Geo\") }", null, null);
        Map<String, Object> adminModel = (Map<String, Object>) adminResult.get("data");
        assertTrue(adminModel.get("ossieModel").toString().contains("email"));
    }

    @Test
    public void schemaCompilesWithTopLevelQueryFields() {
        assertNotNull(svc.getSchema());
        List<String> queryFieldNames = svc.getSchema().getQueryType().getFieldDefinitions().stream()
                .map(f -> f.getName())
                .toList();
        assertTrue(queryFieldNames.contains("serverInfo"));
        assertTrue(queryFieldNames.contains("cubes"));
        assertTrue(queryFieldNames.contains("cube"));
        assertTrue(queryFieldNames.contains("ossieModels"));
        assertTrue(queryFieldNames.contains("ossieModel"));
        assertTrue(queryFieldNames.contains("executeMdx"));
        assertTrue(queryFieldNames.contains("executeOssie"));
    }

    @Test
    public void introspectionQueryReturnsTypesList() {
        Map<String, Object> result = svc.execute("{ __schema { types { name } } }", null, null);
        assertNull("no errors expected on introspection", result.get("errors"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemaBlock = (Map<String, Object>) data.get("__schema");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) schemaBlock.get("types");
        boolean hasQuery = types.stream().anyMatch(t -> "Query".equals(t.get("name")));
        boolean hasServerInfo = types.stream().anyMatch(t -> "ServerInfo".equals(t.get("name")));
        assertTrue("Query type missing from introspection", hasQuery);
        assertTrue("ServerInfo type missing from introspection", hasServerInfo);
    }

    @Test
    public void serverInfoReturnsWiredVersion() {
        Map<String, Object> result = svc.execute("{ serverInfo { version mdxEnabled ossieEnabled } }", null, null);
        assertNull("no errors expected", result.get("errors"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) data.get("serverInfo");
        assertEquals("4.6.0-test", info.get("version"));
        // Without services wired both flags read false — verifies the null-check paths.
        assertEquals(Boolean.FALSE, info.get("mdxEnabled"));
        assertEquals(Boolean.FALSE, info.get("ossieEnabled"));
    }

    @Test
    public void cubesReturnsEmptyListWithoutMetadataService() {
        Map<String, Object> result = svc.execute("{ cubes { cubeName } }", null, null);
        assertNull(result.get("errors"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertNotNull(data.get("cubes"));
        assertEquals(List.of(), data.get("cubes"));
    }

    @Test
    public void ossieModelsReturnsEmptyListWithoutDiscoverService() {
        Map<String, Object> result = svc.execute("{ ossieModels { connectionName } }", null, null);
        assertNull(result.get("errors"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(List.of(), data.get("ossieModels"));
    }

    @Test
    public void sdlExposedAsPlainText() {
        String sdl = svc.getSdl();
        assertNotNull(sdl);
        assertTrue("SDL should declare the Query type", sdl.contains("type Query"));
        assertTrue("SDL should declare executeMdx", sdl.contains("executeMdx"));
    }

    @Test
    public void emptyQueryReturnsValidationError() {
        Map<String, Object> result = svc.execute("", null, null);
        assertNull(result.get("data"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        assertNotNull(errors);
        assertEquals(1, errors.size());
        assertTrue(String.valueOf(errors.get(0).get("message")).contains("required"));
    }

    @Test
    public void invalidQuerySyntaxSurfacesAsGraphQlError() {
        Map<String, Object> result = svc.execute("{ this is not valid", null, null);
        // graphql-java reports parse errors under "errors" and leaves data null.
        assertNotNull(result.get("errors"));
    }

    // ------------------------------------------------------------
    // APQ (Automatic Persisted Queries) — Apollo protocol
    // ------------------------------------------------------------

    @Test
    public void apqHashOnlyMissReturnsPersistedQueryNotFound() {
        String query = "{ serverInfo { version } }";
        String hash = PersistedQueryCache.sha256Hex(query);
        Map<String, Object> extensions = Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", hash));
        Map<String, Object> result = svc.execute(null, null, null, extensions);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> errors = (java.util.List<Map<String, Object>>) result.get("errors");
        assertNotNull(errors);
        assertEquals("PersistedQueryNotFound", errors.get(0).get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> errExtensions = (Map<String, Object>) errors.get(0).get("extensions");
        assertEquals("PERSISTED_QUERY_NOT_FOUND", errExtensions.get("code"));
    }

    @Test
    public void apqStoreThenHashOnlyLookupReturnsData() {
        String query = "{ serverInfo { version } }";
        String hash = PersistedQueryCache.sha256Hex(query);
        Map<String, Object> extensions = Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", hash));

        // First call: full query + hash — stores the mapping.
        Map<String, Object> stored = svc.execute(query, null, null, extensions);
        assertNull(stored.get("errors"));

        // Second call: hash only — cache hit, executes.
        Map<String, Object> hit = svc.execute(null, null, null, extensions);
        assertNull(hit.get("errors"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) hit.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) data.get("serverInfo");
        assertEquals("4.6.0-test", info.get("version"));
    }

    @Test
    public void apqStoreRejectsMismatchedHash() {
        String query = "{ serverInfo { version } }";
        String badHash = PersistedQueryCache.sha256Hex("something else");
        Map<String, Object> extensions = Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", badHash));

        Map<String, Object> result = svc.execute(query, null, null, extensions);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> errors = (java.util.List<Map<String, Object>>) result.get("errors");
        assertNotNull(errors);
        @SuppressWarnings("unchecked")
        Map<String, Object> errExtensions = (Map<String, Object>) errors.get(0).get("extensions");
        assertEquals("PERSISTED_QUERY_HASH_MISMATCH", errExtensions.get("code"));

        // Cache stayed empty — a subsequent hash-only lookup still misses.
        Map<String, Object> extensions2 = Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", badHash));
        Map<String, Object> miss = svc.execute(null, null, null, extensions2);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> missErrors = (java.util.List<Map<String, Object>>) miss.get("errors");
        assertEquals("PersistedQueryNotFound", missErrors.get(0).get("message"));
    }

    @Test
    public void refreshInvalidatesPersistedQueries() {
        String query = "{ serverInfo { version } }";
        String hash = PersistedQueryCache.sha256Hex(query);
        Map<String, Object> extensions = Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", hash));
        svc.execute(query, null, null, extensions);
        assertEquals(1, svc.getPersistedQueryCache().size());
        svc.refresh();
        // With no cubes wired the SDL rebuild is stable — the cache should NOT wipe on a no-op rebuild.
        assertEquals(1, svc.getPersistedQueryCache().size());
    }
}
