/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.dashboards;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Per-tile image configuration (issue #918). Mirrors the {@code ImageConfig}
 * TS shape on the UI side. The server treats it as an opaque bag — Jackson
 * round-trips the JSON and the UI renderer interprets every field. Modelling
 * it here (rather than ignoring unknowns) is what lets an image tile's config
 * survive the load → edit → save round-trip.
 *
 * <p>All fields nullable so a partially-configured image tile saves cleanly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageConfig {

    /** {@code url | upload} — how {@link #src} is sourced. */
    public String source;

    /** External http(s) URL ("url" mode) or the repository asset download path
     *  returned by the image upload endpoint ("upload" mode). */
    public String src;

    /** CSS object-fit: {@code contain | cover | fill | scale-down}. */
    public String fit;

    /** Optional caption rendered below the image. */
    public String caption;

    /** Alt text for accessibility. */
    public String alt;
}
