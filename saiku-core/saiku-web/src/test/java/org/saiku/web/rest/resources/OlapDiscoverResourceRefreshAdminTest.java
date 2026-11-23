/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import jakarta.ws.rs.WebApplicationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.SaikuConnection;
import org.saiku.service.olap.OlapDiscoverService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#2332: a refresh flushes the process-wide Mondrian schema cache, so only an admin may
 * trigger it. A non-admin's cube-picker poll ({@code GET /discover/refresh}) must still answer with
 * the current connection list, but without flushing anything.
 */
public class OlapDiscoverResourceRefreshAdminTest {

    private final AtomicInteger refreshAll = new AtomicInteger();
    private final AtomicInteger refreshOne = new AtomicInteger();
    private final List<SaikuConnection> connections = new ArrayList<>();
    private OlapDiscoverResource resource;

    @Before
    public void setUp() {
        connections.add(new SaikuConnection("foodmart", Collections.emptyList()));
        resource = new OlapDiscoverResource();
        resource.setOlapDiscoverService(new OlapDiscoverService() {
            @Override
            public void refreshAllConnections() {
                refreshAll.incrementAndGet();
            }

            @Override
            public void refreshConnection(String name) {
                refreshOne.incrementAndGet();
            }

            @Override
            public List<SaikuConnection> getAllConnections() {
                return connections;
            }

            @Override
            public List<SaikuConnection> getConnection(String connectionName) {
                return connections;
            }
        });
    }

    @After
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String role) {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                "u", "x", Collections.singletonList(new SimpleGrantedAuthority(role))));
        SecurityContextHolder.setContext(ctx);
    }

    @Test
    public void nonAdminRefreshAllReturnsListWithoutFlushing() {
        login("ROLE_USER");

        List<SaikuConnection> out = resource.refreshConnections();

        assertEquals(0, refreshAll.get());
        assertEquals(1, out.size());
    }

    @Test
    public void nonAdminRefreshOneIsForbidden() {
        login("ROLE_USER");

        try {
            resource.refreshConnection("foodmart");
            fail("expected 403");
        } catch (WebApplicationException e) {
            assertEquals(403, e.getResponse().getStatus());
        }
        assertEquals(0, refreshOne.get());
    }

    @Test
    public void adminRefreshAllFlushes() {
        login("ROLE_ADMIN");

        resource.refreshConnections();

        assertEquals(1, refreshAll.get());
    }

    @Test
    public void adminRefreshOneFlushes() {
        login("ROLE_ADMIN");

        resource.refreshConnection("foodmart");

        assertEquals(1, refreshOne.get());
        assertTrue(refreshAll.get() == 0);
    }
}
