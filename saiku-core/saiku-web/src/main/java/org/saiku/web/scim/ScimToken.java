/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A minted SCIM connector credential's metadata.
 *
 * <p>The secret itself is <b>never</b> held in this record: {@link ScimTokenStore} persists
 * {@code sha256(secret)} as the file name and the plaintext is returned to the caller exactly
 * once, at mint time. That is the same posture as an API key in a password manager — a leaked
 * {@code scim-tokens/} directory must not yield usable bearer credentials.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScimToken {

    /** {@code sha256(secret)} in lowercase hex — the lookup key, not a secret. */
    @JsonProperty("id")
    public String id;

    /** Operator-chosen name for the connector, e.g. {@code "Okta production"}. */
    @JsonProperty("label")
    public String label;

    /** The IdP this connector belongs to, e.g. {@code "Okta"}, {@code "Microsoft Entra ID"}. */
    @JsonProperty("idp")
    public String idp;

    @JsonProperty("createdBy")
    public String createdBy;

    @JsonProperty("createdAt")
    public long createdAt;

    @JsonProperty("lastUsedAt")
    public long lastUsedAt;

    @JsonProperty("revoked")
    public boolean revoked;

    public boolean isValid() {
        return !revoked;
    }
}
