/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail.trust;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One suppressed recipient (saiku#1811, PR2). Records a normalised address, a coarse reason and the
 * epoch-millis timestamp the suppression was recorded.
 *
 * <p>Mutable POJO (not a record) so Jackson can round-trip it from {@code mail-suppression.json}
 * without a custom deserializer, mirroring {@link RecipientTrustFile}. The {@code address} is always
 * stored normalised (lowercase, CRLF-stripped, RFC-validated) by {@link SuppressionStore}; the
 * {@code reason} is a short opaque tag (e.g. {@code "unsubscribe"}), never free-form user text.
 *
 * <p>Unknown properties are ignored so a file written by a future version still loads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SuppressionEntry {

    private String address;
    private String reason;
    private long timestamp;

    public SuppressionEntry() {}

    public SuppressionEntry(String address, String reason, long timestamp) {
        this.address = address;
        this.reason = reason;
        this.timestamp = timestamp;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
