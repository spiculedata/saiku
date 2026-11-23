/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.scim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.web.scim.ScimException;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#1438 — the resource-layer auth guard.
 *
 * <p>{@code ScimAuthFilter} is the boundary and 401s an absent or unknown bearer long before
 * Jersey runs, so reaching a resource method without a SCIM principal means the chain was
 * misrouted. This asserts the resources still fail closed in that case: a user directory must
 * never be served to a request the filter did not authenticate.
 */
public class ScimUsersResourceTest {

    private ScimUsersResource resource;

    @Before
    public void setUp() {
        SecurityContextHolder.clearContext();
        // No scimService is wired on purpose — the guard must reject before it is ever consulted.
        resource = new ScimUsersResource();
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void listWithoutAScimPrincipalIs401() {
        try {
            resource.list(null, 1, 10);
            fail("an unauthenticated request must not reach the user directory");
        } catch (ScimException e) {
            assertEquals(401, e.getResponse().getStatus());
        }
    }

    @Test
    public void readWithoutAScimPrincipalIs401() {
        try {
            resource.get("1");
            fail("an unauthenticated request must not reach the user directory");
        } catch (ScimException e) {
            assertEquals(401, e.getResponse().getStatus());
        }
    }

    @Test
    public void createWithoutAScimPrincipalIs401() {
        try {
            resource.create(new org.saiku.web.scim.ScimUser());
            fail("an unauthenticated request must not create a user");
        } catch (ScimException e) {
            assertEquals(401, e.getResponse().getStatus());
        }
    }

    @Test
    public void deleteWithoutAScimPrincipalIs401() {
        try {
            resource.deactivate("1");
            fail("an unauthenticated request must not deactivate a user");
        } catch (ScimException e) {
            assertEquals(401, e.getResponse().getStatus());
        }
    }
}
