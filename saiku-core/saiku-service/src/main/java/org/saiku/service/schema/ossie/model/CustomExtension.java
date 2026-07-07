/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.ossie.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Ossie {@code CustomExtension} — vendor-specific escape hatch. All of Saiku's non-core annotations
 * ({@code pii}, {@code cardinality}, {@code grain}, {@code aggregation_kind}, {@code
 * required_filters}) ride here under {@code vendor_name="SAIKU"} with a JSON string payload.
 *
 * <p>Per the Ossie spec {@code data} is a JSON string (not a nested object) so different vendors
 * can attach arbitrary structure without polluting the core schema. We serialise Saiku's payload
 * with Jackson before assignment.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomExtension {
    /** Well-known label the Saiku exporter uses for its extension payloads. */
    public static final String VENDOR_SAIKU = "SAIKU";

    private String vendorName;
    private String data;

    public CustomExtension() {}

    public CustomExtension(String vendorName, String data) {
        this.vendorName = vendorName;
        this.data = data;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("vendor_name")
    public String getVendorName() {
        return vendorName;
    }

    public void setVendorName(String v) {
        this.vendorName = v;
    }

    public String getData() {
        return data;
    }

    public void setData(String v) {
        this.data = v;
    }
}
