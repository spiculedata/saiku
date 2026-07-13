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

    public EmbedPublicGrant() {}
}
