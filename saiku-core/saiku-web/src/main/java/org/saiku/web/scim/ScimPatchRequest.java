/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * SCIM {@code PATCH} request body (RFC 7644 §3.5.2).
 *
 * <p>Deliberately a thin envelope over {@code JsonNode} values: the shape of an operation's
 * {@code value} depends on its {@code path} ({@code active} carries a boolean, {@code members}
 * carries an array, a path-less op carries an object of attribute paths), and modelling every
 * combination up front buys nothing that {@link ScimPatch} doesn't already do defensively.
 */
public class ScimPatchRequest {

    @JsonProperty("schemas")
    public List<String> schemas = List.of(ScimSchemas.PATCH_OP);

    @JsonProperty("Operations")
    public List<Operation> operations;

    public static class Operation {
        @JsonProperty("op")
        public String op;

        @JsonProperty("path")
        public String path;

        @JsonProperty("value")
        public com.fasterxml.jackson.databind.JsonNode value;
    }
}
