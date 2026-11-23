/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

/** The URNs and literals of the SCIM 2.0 subset Saiku speaks (RFC 7643 / RFC 7644, issue #1438). */
public final class ScimSchemas {

    private ScimSchemas() {}

    public static final String USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    public static final String GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group";
    public static final String LIST_RESPONSE = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    public static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    public static final String SEARCH_REQUEST = "urn:ietf:params:scim:api:messages:2.0:SearchRequest";
    public static final String ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";
    public static final String SERVICE_PROVIDER_CONFIG = "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig";
    public static final String RESOURCE_TYPE = "urn:ietf:params:scim:schemas:core:2.0:ResourceType";
    public static final String SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Schema";
    public static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";

    public static final String RESOURCE_TYPE_USER = "User";
    public static final String RESOURCE_TYPE_GROUP = "Group";

    /** Okta's "Test connection" probe hits this; Entra probes the base URL then Schemas. */
    public static final String BASE_PATH = "/scim/v2";
}
