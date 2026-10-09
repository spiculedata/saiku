/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for {@code POST /saiku/admin/model/diff} (saiku#1434).
 *
 * <p>Each side of the diff is a {@link Side}: either raw model text ({@code content}) or a path
 * ({@code path}) the server reads. Both forms exist because the two callers differ — the Model
 * IDE (#1428) has the proposed text in hand, while a CI hook or a review UI has two git refs and
 * only paths.
 *
 * <p>Unknown JSON properties are ignored rather than rejected, so a newer client sending an extra
 * hint does not get a 400 from an older server.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelDiffRequest {

    /** One side of the diff. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Side {

        /** Raw model text (Mondrian XML or Ossie YAML). */
        @JsonProperty("content")
        public String content;

        /** Path to a model file on the server, relative to {@code repositoryRoot} or absolute. */
        @JsonProperty("path")
        public String path;

        /** Label used in the report when the file name would be unhelpful. */
        @JsonProperty("name")
        public String name;

        public boolean isEmpty() {
            return (content == null || content.isBlank()) && (path == null || path.isBlank());
        }
    }

    @JsonProperty("before")
    public Side before;

    @JsonProperty("after")
    public Side after;

    /**
     * Repository root to walk for saved queries / dashboards / apps. Defaults to the server's own
     * {@code <saiku-home>/repository/data}. An explicit value must still resolve inside the
     * configured root — see {@code ModelDiffResource}.
     */
    @JsonProperty("repository")
    public String repository;
}
