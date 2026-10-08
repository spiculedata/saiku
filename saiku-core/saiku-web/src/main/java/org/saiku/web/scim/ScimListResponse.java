/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** SCIM {@code ListResponse} envelope (RFC 7644 §3.4.2) — every collection read returns one. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScimListResponse {

    @JsonProperty("schemas")
    public List<String> schemas = List.of(ScimSchemas.LIST_RESPONSE);

    @JsonProperty("totalResults")
    public int totalResults;

    @JsonProperty("startIndex")
    public int startIndex;

    @JsonProperty("itemsPerPage")
    public int itemsPerPage;

    @JsonProperty("Resources")
    public List<?> resources;

    public ScimListResponse() {}

    public ScimListResponse(List<?> resources, int startIndex) {
        this(resources, startIndex, resources == null ? 0 : resources.size(), 0);
    }

    public ScimListResponse(List<?> resources, int startIndex, int totalResults, int itemsPerPage) {
        this.resources = resources;
        this.startIndex = startIndex;
        this.totalResults = totalResults;
        this.itemsPerPage = itemsPerPage;
    }
}
