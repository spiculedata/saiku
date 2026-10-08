/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.scim;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.saiku.web.scim.ScimResponses;
import org.saiku.web.scim.ScimSchemas;
import org.saiku.web.scim.ScimService;

/**
 * SCIM 2.0 discovery endpoints (issue #1438) at {@code /rest/scim/v2}.
 *
 * <p>Not optional in practice: Okta's "Test connection" button and Entra ID's connector
 * validation both fetch {@code /ServiceProviderConfig} (and Entra then
 * {@code /ResourceTypes} and {@code /Schemas}) before they will attempt a single create. A server
 * that 404s these is reported by the IdP as an invalid base URL, so the payloads are the entry
 * point of the whole integration.
 */
@Path(ScimSchemas.BASE_PATH)
@Produces(ScimResponses.SCIM_JSON)
public class ScimDiscoveryResource {

    private ScimService scimService;

    public void setScimService(ScimService scimService) {
        this.scimService = scimService;
    }

    @GET
    @Path("ServiceProviderConfig")
    public Response serviceProviderConfig() {
        ScimResponses.requireScimPrincipal();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("schemas", List.of(ScimSchemas.SERVICE_PROVIDER_CONFIG));
        config.put("documentationUri", "https://spiculedata.github.io/saiku/");
        config.put("patch", Map.of("supported", true));
        config.put("bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0));
        // Saiku applies its own per-token rate limit (saiku.scim.rate-limit.per-minute, default
        // 100/min) and returns 429 with a Retry-After header rather than a SCIM error, so
        // advertising "bulk not supported" is honest and connectors fall back to single writes.
        config.put(
                "filter",
                Map.of("supported", true, "maxResults", scimService == null ? 200 : scimService.maxPageSize()));
        config.put("changePassword", Map.of("supported", false));
        config.put("sort", Map.of("supported", false));
        config.put("etag", Map.of("supported", false));
        config.put("authenticationSchemes", List.of(bearerScheme()));
        return ScimResponses.ok(config);
    }

    @GET
    @Path("ResourceTypes")
    public Response resourceTypes() {
        ScimResponses.requireScimPrincipal();
        List<Map<String, Object>> types = new ArrayList<>();
        types.add(resourceType(ScimSchemas.RESOURCE_TYPE_USER, ScimSchemas.USER, "/Users", "User"));
        types.add(resourceType(ScimSchemas.RESOURCE_TYPE_GROUP, ScimSchemas.GROUP, "/Groups", "Group"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of(ScimSchemas.LIST_RESPONSE));
        body.put("totalResults", types.size());
        body.put("startIndex", 1);
        body.put("itemsPerPage", types.size());
        body.put("Resources", types);
        return ScimResponses.list(new org.saiku.web.scim.ScimListResponse(types, 1, types.size(), types.size()));
    }

    @GET
    @Path("Schemas")
    public Response schemas() {
        ScimResponses.requireScimPrincipal();
        List<Map<String, Object>> schemas = new ArrayList<>();
        schemas.add(userSchema());
        schemas.add(groupSchema());
        return ScimResponses.list(new org.saiku.web.scim.ScimListResponse(schemas, 1, schemas.size(), schemas.size()));
    }

    @GET
    @Path("Schemas/{id}")
    public Response schema(@PathParam("id") String id) {
        ScimResponses.requireScimPrincipal();
        String wanted = id == null ? "" : id.toLowerCase(java.util.Locale.ROOT);
        if (ScimSchemas.USER.toLowerCase(java.util.Locale.ROOT).equals(wanted)) {
            return ScimResponses.ok(userSchema());
        }
        if (ScimSchemas.GROUP.toLowerCase(java.util.Locale.ROOT).equals(wanted)) {
            return ScimResponses.ok(groupSchema());
        }
        throw org.saiku.web.scim.ScimException.notFound("Unknown schema: " + id);
    }

    private static Map<String, Object> bearerScheme() {
        Map<String, Object> bearer = new LinkedHashMap<>();
        bearer.put("type", "oauthbearertoken");
        bearer.put("name", "OAuth Bearer Token");
        bearer.put("description", "Authentication using the OAuth Bearer Token standard");
        bearer.put("specUri", "http://www.rfc-editor.org/info/rfc6750");
        bearer.put("primary", true);
        return bearer;
    }

    private static Map<String, Object> resourceType(String id, String schema, String endpoint, String name) {
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("schemas", List.of(ScimSchemas.RESOURCE_TYPE));
        type.put("id", id);
        type.put("name", name);
        type.put("endpoint", "/" + endpoint);
        type.put("description", "Saiku " + name.toLowerCase(java.util.Locale.ROOT) + " endpoint");
        type.put("schema", schema);
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("patch", Map.of("supported", true));
        ext.put("filter", Map.of("supported", true));
        ext.put("bulk", Map.of("supported", false));
        type.put("schemaExtensions", List.of(ext));
        type.put(
                "meta",
                Map.of(
                        "resourceType",
                        ScimSchemas.RESOURCE_TYPE,
                        "location",
                        ScimSchemas.BASE_PATH + "/ResourceTypes/" + id));
        return type;
    }

    private static Map<String, Object> userSchema() {
        Map<String, Object> schema = baseSchema(ScimSchemas.USER, "User", "User Account");
        schema.put(
                "attributes",
                List.of(
                        simpleString("userName", true),
                        complex(
                                "name",
                                "complex",
                                false,
                                List.of(
                                        simpleString("givenName", false),
                                        simpleString("familyName", false),
                                        simpleString("formatted", false))),
                        simpleString("displayName", false),
                        complex(
                                "emails",
                                "complex",
                                false,
                                List.of(
                                        simpleString("value", false),
                                        simpleString("type", false),
                                        attribute("primary", "boolean", false))),
                        attribute("active", "boolean", false),
                        complex(
                                "groups",
                                "complex",
                                false,
                                List.of(
                                        simpleString("value", false),
                                        simpleString("display", false),
                                        simpleString("type", false)))));
        return schema;
    }

    private static Map<String, Object> groupSchema() {
        Map<String, Object> schema = baseSchema(ScimSchemas.GROUP, "Group", "Group");
        schema.put(
                "attributes",
                List.of(
                        simpleString("displayName", true),
                        complex(
                                "members",
                                "complex",
                                false,
                                List.of(
                                        simpleString("value", false),
                                        simpleString("display", false),
                                        simpleString("type", false)))));
        return schema;
    }

    private static Map<String, Object> baseSchema(String id, String name, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("schemas", List.of(ScimSchemas.SCHEMA));
        schema.put("id", id);
        schema.put("name", name);
        schema.put("description", description + " as served by Saiku");
        schema.put("attributes", List.of());
        schema.put("meta", Map.of("resourceType", "Schema", "location", ScimSchemas.BASE_PATH + "/Schemas/" + id));
        return schema;
    }

    private static Map<String, Object> simpleString(String name, boolean required) {
        return attribute(name, "string", required);
    }

    private static Map<String, Object> attribute(String name, String type, boolean required) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", name);
        a.put("type", type);
        a.put("multiValued", false);
        a.put("required", required);
        a.put("caseExact", false);
        a.put("mutability", "readWrite");
        a.put("returned", "default");
        a.put("uniqueness", "none");
        return a;
    }

    private static Map<String, Object> complex(
            String name, String type, boolean required, List<Map<String, Object>> subAttributes) {
        Map<String, Object> a = attribute(name, type, required);
        a.put("multiValued", true);
        a.put("subAttributes", subAttributes);
        return a;
    }
}
