/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.PiiScanner;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#2330: {@code GET /ai/schema/{cubeId}/pii-suggestions} is an operator diagnostic. A caller
 * without ROLE_ADMIN must be refused with 403 before the shared schema cache is warmed or the
 * scanner findings are read.
 */
public class AiQueryResourcePiiSuggestionsAdminTest {

    private static final String CUBE_ID = "conn/cat/sch/cube";

    private final AtomicInteger schemaCalls = new AtomicInteger();
    private final AtomicInteger suggestionCalls = new AtomicInteger();
    private AiQueryResource resource;

    @Before
    public void setUp() {
        resource = new AiQueryResource();
        resource.setCubeMetadataService(new AiCubeMetadataService() {
            @Override
            public AiSchema getSchema(AiCubeRef ref) {
                schemaCalls.incrementAndGet();
                return null;
            }

            @Override
            public List<PiiScanner.Match> getPiiSuggestions(AiCubeRef ref) {
                suggestionCalls.incrementAndGet();
                return Collections.emptyList();
            }
        });
    }

    @After
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String... roles) {
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority(role));
        }
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken("u", "x", authorities));
        SecurityContextHolder.setContext(ctx);
    }

    @Test
    public void refusesNonAdminWithoutWarmingTheSchemaCache() {
        login("ROLE_USER");

        Response r = resource.getPiiSuggestions(CUBE_ID);

        assertEquals(403, r.getStatus());
        assertEquals(0, schemaCalls.get());
        assertEquals(0, suggestionCalls.get());
    }

    @Test
    public void refusesCallerWithNoSecurityContext() {
        Response r = resource.getPiiSuggestions(CUBE_ID);

        assertEquals(403, r.getStatus());
        assertEquals(0, schemaCalls.get());
    }

    @Test
    public void adminStillReceivesSuggestions() {
        login("ROLE_ADMIN");

        Response r = resource.getPiiSuggestions(CUBE_ID);

        assertEquals(200, r.getStatus());
        assertFalse(suggestionCalls.get() == 0);
    }
}
