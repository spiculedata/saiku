/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.share;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A persisted share-token record (issue #941) — the server-side authority for
 * an account-free, view-only dashboard link. The token string itself is an
 * opaque random lookup key (see {@link ShareTokenStore}); this record holds no
 * secret beyond it.
 *
 * <p>Stored as {@code ${saiku.home}/share-tokens/<token>.json}. A guest request
 * is authorised purely by presenting a token that maps to a non-revoked,
 * unexpired record here; the {@link #dashboardPath} it pins is the ONLY
 * dashboard that token can ever view, and {@link #ownerRolesSnapshot} is the
 * data-scope the proxied queries run under (see ShareViewResource).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShareToken {

    /** Opaque URL-safe random id; also the store filename stem. */
    public String token;

    /** Repository path of the shared dashboard (ends {@code .saikudash}). */
    public String dashboardPath;

    /** Username that minted the link (must have had GRANT on the dashboard). */
    public String createdBy;

    /** Roles the minter held at mint time — the data-scope guest queries run
     *  under, so a delegated share shows exactly what the owner authorised
     *  rather than the (often empty) anonymous default role. */
    public List<String> ownerRolesSnapshot;

    /** Epoch millis when minted. */
    public long createdAt;

    /** Epoch millis when the link stops working. */
    public long expiresAt;

    /** Soft revocation — a revoked token fails validation on the next request. */
    public boolean revoked;

    /** Optional human label shown in the owner's share list. */
    public String label;

    public ShareToken() {}

    /** True when the token is past its expiry relative to {@code nowMs}. */
    public boolean isExpired(long nowMs) {
        return expiresAt > 0 && nowMs >= expiresAt;
    }

    /** Usable = neither revoked nor expired. */
    public boolean isValid(long nowMs) {
        return !revoked && !isExpired(nowMs);
    }
}
