/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.comments;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * One comment on a dashboard tile (issue #942). Persisted as a single JSON
 * line in a per-dashboard {@code .comments.jsonl} file (see
 * {@link CommentService}); {@link #tileId} scopes it to a tile within that
 * dashboard. Deletion is soft ({@link #deleted}) so the append-only thread
 * keeps its order.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Comment {

    /** Stable id (UUID). */
    public String id;

    /** The tile this comment is attached to. */
    public String tileId;

    /** Username of the author (server-assigned from the session — never trusted from the client). */
    public String author;

    /** Free-text body. */
    public String body;

    /** Usernames @-mentioned in {@link #body}, parsed server-side. Delivery of
     *  notifications is deferred (#942 has no notification layer yet). */
    public List<String> mentions = new ArrayList<>();

    /** Epoch millis. */
    public long createdAt;

    /** Soft-delete marker. */
    public boolean deleted;

    public Comment() {}
}
