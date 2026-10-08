/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import org.saiku.service.olap.ai.AiQueryRequest;

/**
 * DTOs for the {@code POST /saiku/api/ai/narrate-dashboard} wire contract (saiku#910, Tier-2
 * aggregated).
 *
 * <p>Held in saiku-service, same convention as {@link AiAskApi}, so both the resource and any
 * future agent consumer can deserialise against the same types.
 *
 * <p>v1 design note: the dashboard layer is layout-only on the backend (see {@code
 * DashboardResource}'s own doc comment) — the frontend already computes each tile's effective
 * filters and re-issues its query client-side. Rather than have this endpoint re-derive that
 * filter-resolution logic server-side from a bare {@code dashboardId}, the caller posts each
 * VISIBLE tile's already filter-resolved {@link AiQueryRequest} directly (the same typed shape
 * {@code /ai/query} accepts) — the server executes each one itself (so k-anonymity + PII
 * redaction apply to freshly-run data, never client-supplied numbers) and narrates the result.
 */
public final class AiDashboardNarrativeApi {

    private AiDashboardNarrativeApi() {}

    /** Wire shape for {@code POST /saiku/api/ai/narrate-dashboard} body. */
    public static class Request {
        private String dashboardTitle;
        private List<Tile> tiles = new ArrayList<>();

        public String getDashboardTitle() {
            return dashboardTitle;
        }

        public void setDashboardTitle(String v) {
            this.dashboardTitle = v;
        }

        public List<Tile> getTiles() {
            return tiles;
        }

        public void setTiles(List<Tile> v) {
            this.tiles = v == null ? new ArrayList<>() : v;
        }
    }

    /** One visible dashboard tile: display title + the query that produces its data. */
    public static class Tile {
        private String title;
        private AiQueryRequest query;

        public Tile() {}

        public Tile(String title, AiQueryRequest query) {
            this.title = title;
            this.query = query;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String v) {
            this.title = v;
        }

        public AiQueryRequest getQuery() {
            return query;
        }

        public void setQuery(AiQueryRequest v) {
            this.query = v;
        }
    }

    /** Response envelope. Mirrors {@link AiAskApi.AskResponse}'s degraded/reason/model shape. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Response {
        private boolean degraded;
        private String reason;
        private String model;
        private String narrative;

        public boolean isDegraded() {
            return degraded;
        }

        public void setDegraded(boolean v) {
            this.degraded = v;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String v) {
            this.reason = v;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String v) {
            this.model = v;
        }

        public String getNarrative() {
            return narrative;
        }

        public void setNarrative(String v) {
            this.narrative = v;
        }
    }
}
