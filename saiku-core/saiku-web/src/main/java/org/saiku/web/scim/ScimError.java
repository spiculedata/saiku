/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** SCIM {@code Error} response body (RFC 7644 §3.12) with the {@code scimType} hint. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScimError {

    @JsonProperty("schemas")
    public List<String> schemas = List.of(ScimSchemas.ERROR);

    @JsonProperty("status")
    public String status;

    @JsonProperty("scimType")
    public String scimType;

    @JsonProperty("detail")
    public String detail;

    public ScimError() {}

    public ScimError(String status, String scimType, String detail) {
        this.status = status;
        this.scimType = scimType;
        this.detail = detail;
    }
}
