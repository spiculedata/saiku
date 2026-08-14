/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail.trust;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One recipient's double-opt-in consent record (saiku#1811, PR3). Records a normalised address, the
 * consent {@link ConsentStatus lifecycle state}, request/confirm timestamps, the token expiry, and —
 * critically — only a <b>salted hash</b> of the consent token, never the reversible token itself.
 *
 * <p><b>Nothing sensitive is reversible on disk.</b> The stored {@link #tokenHash} is
 * {@code base64( SHA-256(salt || rawToken) )} and {@link #tokenSalt} is the per-entry random salt.
 * Confirming looks the recipient up, re-hashes the supplied raw token under the stored salt, and
 * constant-time compares — no decryption, and the file leaks no token and no enumeration oracle.
 *
 * <p>Mutable POJO (not a record) so Jackson can round-trip it from {@code mail-consent.json} without a
 * custom deserializer, mirroring {@link SuppressionEntry}. The {@code address} is always stored
 * normalised (lowercase, CRLF-stripped, RFC-validated) by {@link RecipientConsentStore}.
 *
 * <p>Unknown properties are ignored so a file written by a future version still loads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConsentEntry {

    private String address;
    private ConsentStatus status;
    private long requestedAt;
    private long confirmedAt;
    private long tokenExpiresAt;

    /** base64(SHA-256(salt || rawToken)); NEVER the raw token. Null once CONFIRMED/REVOKED clears it. */
    private String tokenHash;

    /** base64 of the per-entry random salt used to compute {@link #tokenHash}. */
    private String tokenSalt;

    public ConsentEntry() {}

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public ConsentStatus getStatus() {
        return status;
    }

    public void setStatus(ConsentStatus status) {
        this.status = status;
    }

    public long getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(long requestedAt) {
        this.requestedAt = requestedAt;
    }

    public long getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(long confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public long getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public void setTokenExpiresAt(long tokenExpiresAt) {
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getTokenSalt() {
        return tokenSalt;
    }

    public void setTokenSalt(String tokenSalt) {
        this.tokenSalt = tokenSalt;
    }
}
