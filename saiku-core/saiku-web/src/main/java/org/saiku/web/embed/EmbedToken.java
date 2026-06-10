/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.embed;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A persisted embed-token record — the server-side authority for a
 * {@code <saiku-embed>} Web Component dropped into a third-party page.
 * The token string itself is an opaque random lookup key (see
 * {@link EmbedTokenStore}); this record holds no secret beyond it.
 *
 * <p>Stored as {@code ${saiku.home}/embed-tokens/<token>.json}. A request to
 * {@code /rest/saiku/api/embed/*} is authorised by presenting a token that maps
 * to a non-revoked, unexpired record here; the {@link #resourcePath} +
 * {@link #resourceKind} it pins is the ONLY resource that token can ever read,
 * and {@link #ownerRolesSnapshot} is the data-scope the proxied queries run
 * under (see {@code EmbedViewResource}).
 *
 * <p>Twin of {@code ShareToken} (saiku#941) — the embed flow keeps a parallel
 * store rather than retrofitting share because (a) embed supports queries as
 * well as dashboards, (b) the read endpoints render data inline in a host page
 * via a Web Component rather than serving the chromeless Saiku UI in an
 * iframe, and (c) the auth header is distinct from share's so they cannot be
 * confused by a misconfigured filter chain.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmbedToken {

    /** Opaque URL-safe random id; also the store filename stem. */
    public String token;

    /** What this token grants read access to. {@code "query"} (.saiku) or
     *  {@code "dashboard"} (.saikudash). The view endpoint matches its path
     *  segment against this so a query-token can't be replayed against the
     *  dashboard path or vice versa. */
    public String resourceKind;

    /** Repository path of the resource. Ends {@code .saiku} or
     *  {@code .saikudash} depending on {@link #resourceKind}. */
    public String resourcePath;

    /** Username that minted the link (must have had read on the resource). */
    public String createdBy;

    /** Roles the minter held at mint time — the data-scope the embedded
     *  queries run under, so a delegated embed shows exactly what the owner
     *  authorised rather than the (often empty) anonymous default role. */
    public List<String> ownerRolesSnapshot;

    /** Epoch millis when minted. */
    public long createdAt;

    /** Epoch millis when the token stops working. */
    public long expiresAt;

    /** Soft revocation — a revoked token fails validation on the next request. */
    public boolean revoked;

    /** Optional human label shown in the owner's embed list. */
    public String label;

    public EmbedToken() {}

    /** True when the token is past its expiry relative to {@code nowMs}. */
    public boolean isExpired(long nowMs) {
        return expiresAt > 0 && nowMs >= expiresAt;
    }

    /** Usable = neither revoked nor expired. */
    public boolean isValid(long nowMs) {
        return !revoked && !isExpired(nowMs);
    }
}
