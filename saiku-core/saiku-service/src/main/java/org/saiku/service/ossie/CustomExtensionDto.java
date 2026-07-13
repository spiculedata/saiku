/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Client-facing slice of an OSI {@code custom_extensions[]} entry.
 *
 * <p>The library ({@link bi.saiku.ossie.model.CustomExtension}) keeps the {@code data} payload as a
 * raw JSON string; here we parse it into a {@link JsonNode} so the workbench + AI schema can walk
 * the structure without a second parse. Extensions marked {@code visibility: internal} in their
 * data payload are stripped by {@link OssieDiscoverService} before this DTO is built, so anything
 * that lands here is safe to render.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomExtensionDto {
    private String vendorName;
    private JsonNode data;

    @JsonProperty("vendor_name")
    public String getVendorName() {
        return vendorName;
    }

    public void setVendorName(String v) {
        this.vendorName = v;
    }

    public JsonNode getData() {
        return data;
    }

    public void setData(JsonNode v) {
        this.data = v;
    }
}
