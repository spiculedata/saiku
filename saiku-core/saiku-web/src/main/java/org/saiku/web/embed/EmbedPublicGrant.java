/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.embed;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Server-side record marking one saved query / dashboard as publicly
 * embeddable — anyone hitting {@code /rest/saiku/api/embed/*} for this resource
 * gets the data with NO token presented. The owner is the user who flipped the
 * grant on; their data-scope at grant time is the perspective the resource
 * renders under for every public viewer (mirrors the
 * {@code ShareToken#ownerRolesSnapshot} pattern from saiku#941).
 *
 * <p>Persisted as part of an {@code embed-public.json} sidecar (see
 * {@link EmbedPublicRegistry}) rather than baked into the saved file itself, so
 * (a) revoking publicness doesn't rewrite the resource and (b) the ACL is
 * server-authoritative — a copy of the file outside saiku-home (e.g. exported
 * to git) doesn't inadvertently re-grant public access on import.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmbedPublicGrant {

    /** {@code "query"} or {@code "dashboard"} — must match the resourceKind
     *  the view endpoint dispatches on. */
    public String resourceKind;

    /** Repository path. Acts as the lookup key in {@link EmbedPublicRegistry}. */
    public String resourcePath;

    /** User who flipped public on. */
    public String grantedBy;

    /** Owner's roles at grant time — the data-scope public reads render under. */
    public List<String> ownerRolesSnapshot;

    /** Epoch millis when public was granted. */
    public long grantedAt;

    /** Optional human label shown in the owner's public-grants list. */
    public String label;

    /** saiku#2536 — lowercase SHA-256 hex of the granted content (as read
     *  under the grantor's scope) at grant time. Binds the grant to WHAT
     *  was shared, not only to its path: a later edit by a collaborator
     *  changes the hash and the anonymous read is refused instead of
     *  executing the new content under the grantor's roles. Null on grants
     *  made before this binding existed — those bind to current content on
     *  first use. */
    public String contentSha256;

    /** saiku#2536 — set once a user other than the grantor has saved the
     *  granted content since the grant was made. Refuses the public read
     *  even if the bytes were later restored to match the pinned hash: the
     *  grant must be revoked and re-granted. Cleared only by the grantor
     *  writing again. */
    public boolean contentChangedByOther;

    public EmbedPublicGrant() {}
}
