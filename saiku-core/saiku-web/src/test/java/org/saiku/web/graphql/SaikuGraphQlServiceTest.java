/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.graphql;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

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
}
