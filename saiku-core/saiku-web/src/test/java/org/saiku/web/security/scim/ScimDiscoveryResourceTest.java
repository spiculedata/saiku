/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.web.security.scim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.web.scim.ScimException;
import org.saiku.web.scim.ScimResponses;
import org.saiku.web.scim.ScimToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link org.saiku.web.rest.resources.scim.ScimDiscoveryResource}. Lives in this
 * package so it can build the package-private {@link ScimAuthFilter.ScimPrincipal}.
 */
public class ScimDiscoveryResourceTest {

    private org.saiku.web.rest.resources.scim.ScimDiscoveryResource resource;

    @Before
    public void setUp() {
        resource = new org.saiku.web.rest.resources.scim.ScimDiscoveryResource();
        resource.setScimService(null);
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void asScimPrincipal() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("scim", "secret", "ROLE_SCIM");
        auth.setDetails(new ScimAuthFilter.ScimPrincipal(new ScimToken()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    public void without_a_principal_every_endpoint_fails_closed() {
        // requireScimPrincipal throws rather than returning a response.
        assertUnauthorized(() -> resource.serviceProviderConfig());
        assertUnauthorized(() -> resource.resourceTypes());
        assertUnauthorized(() -> resource.schemas());
        assertUnauthorized(() -> resource.schema("urn:ietf:params:scim:schemas:core:2.0:User"));
    }

    private interface Call {
        Response run();
    }

    private static void assertUnauthorized(Call call) {
        try {
            call.run();
            fail("expected ScimException");
        } catch (ScimException e) {
            assertEquals(401, e.getResponse().getStatus());
        }
    }

    @Test
    public void service_provider_config_advertises_bearer_auth() {
        asScimPrincipal();
        Response response = resource.serviceProviderConfig();
        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals(true, ((Map<String, Object>) body.get("patch")).get("supported"));
        assertEquals(false, ((Map<String, Object>) body.get("bulk")).get("supported"));
        assertEquals(false, ((Map<String, Object>) body.get("changePassword")).get("supported"));
    }

    @Test
    public void service_provider_config_uses_the_service_max_page_size() {
        // A null service falls back to the 200 default; a configured service is
        // exercised through ScimServiceTest's own coverage.
        asScimPrincipal();
        Response response = resource.serviceProviderConfig();
        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> filter = (Map<String, Object>) ((Map<String, Object>) response.getEntity()).get("filter");
        assertEquals(200, filter.get("maxResults"));
    }

    @Test
    public void resource_types_lists_user_and_group() {
        asScimPrincipal();
        Response response = resource.resourceTypes();
        assertEquals(200, response.getStatus());
        assertEquals(
                ScimResponses.SCIM_JSON,
                response.getMetadata().getFirst("Content-Type").toString());
    }

    @Test
    public void schemas_lists_the_user_and_group_schemas() {
        asScimPrincipal();
        assertEquals(200, resource.schemas().getStatus());
    }

    @Test
    public void a_single_schema_is_served_by_id() {
        asScimPrincipal();
        assertEquals(
                200,
                resource.schema("urn:ietf:params:scim:schemas:core:2.0:User").getStatus());
        assertEquals(
                200,
                resource.schema("urn:ietf:params:scim:schemas:core:2.0:Group").getStatus());
    }

    @Test
    public void an_unknown_schema_id_is_not_found() {
        asScimPrincipal();
        try {
            resource.schema("urn:example:unknown");
            fail("expected ScimException");
        } catch (ScimException e) {
            assertEquals(404, e.getResponse().getStatus());
        }
    }
}
