/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.share;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A persisted share-token record (issue #941, widened by issue #1108) — the
 * server-side authority for an account-free, view-only dashboard or notebook
 * link. The token string itself is an opaque random lookup key (see {@link
 * ShareTokenStore}); this record holds no secret beyond it.
 *
 * <p>Stored as {@code ${saiku.home}/share-tokens/<token>.json}. A guest request
 * is authorised purely by presenting a token that maps to a non-revoked,
 * unexpired record here; the {@link #dashboardPath} it pins is the ONLY
 * resource that token can ever view, and {@link #ownerRolesSnapshot} is the
 * data-scope the proxied queries run under (see ShareViewResource /
 * NotebookShareViewResource).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShareToken {

    /** Opaque URL-safe random id; also the store filename stem. */
    public String token;

    /** Repository path of the shared resource — a dashboard ({@code
     *  .saikudash}) or, since issue #1108, a notebook ({@code .saikunb}).
     *  Keeps its #941 name because {@link ShareTokenStore} and {@code
     *  ShareTokenAuthFilter} are generic over what path they pin; only the
     *  mint-time suffix check and the guest-facing view resource are
     *  resource-kind-aware. */
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

    /** saiku#2536 — lowercase SHA-256 hex of the shared content (as read
     *  under the minter's scope) at mint time. Binds the link to WHAT was
     *  shared, not only to its path: a later edit by a collaborator changes
     *  the hash and the guest read is refused instead of executing the new
     *  content under the minter's roles. Null on tokens minted before this
     *  binding existed — those bind to current content on first use. */
    public String contentSha256;

    /** Mint-time hashes of saved queries referenced by dashboard tiles.
     *  Null only on records created before reference binding was introduced. */
    public Map<String, String> referenceSha256;

    /** Referenced files written by someone other than the minter. */
    public Set<String> referencesChangedByOther;

    /** saiku#2536 — set once a user other than the minter has saved the
     *  shared content since minting. Refuses the guest read even if the
     *  bytes were later restored to match the pinned hash: the link must be
     *  re-minted. Cleared only by the minter writing again. */
    public boolean contentChangedByOther;

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
