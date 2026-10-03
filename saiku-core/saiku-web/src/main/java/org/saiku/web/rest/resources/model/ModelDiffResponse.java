/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.saiku.service.schema.diff.BrokenReference;
import org.saiku.service.schema.diff.ModelChange;
import org.saiku.service.schema.diff.ModelDiffReport;

/**
 * The JSON shape returned by {@code POST /saiku/admin/model/diff} (saiku#1434) — the same
 * information {@link ModelDiffReport#markdown()} renders as Markdown, in a form the Model IDE's
 * branch preview (#1428) and the agent-eval gate (#1424) can both consume.
 *
 * <p>{@code clean} is the merge signal: true when nothing in the repository is left pointing at a
 * member the after-model no longer resolves.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelDiffResponse {

    private final String format;
    private final String beforeName;
    private final String afterName;
    private final List<ModelChange> changes;
    private final List<BrokenReference> brokenReferences;
    private final int filesScanned;
    private final int affectedFiles;
    private final boolean clean;
    private final boolean breaking;
    private final String markdown;

    public ModelDiffResponse(ModelDiffReport report, boolean includeMarkdown) {
        this.format = report.format().name();
        this.beforeName = report.beforeName();
        this.afterName = report.afterName();
        this.changes = report.changes();
        this.brokenReferences = report.brokenReferences();
        this.filesScanned = report.filesScanned();
        this.affectedFiles = report.affectedFileCount();
        this.clean = report.isClean();
        this.breaking = report.hasBreakingChanges();
        this.markdown = includeMarkdown ? report.markdown() : null;
    }

    public String getFormat() {
        return format;
    }

    public String getBeforeName() {
        return beforeName;
    }

    public String getAfterName() {
        return afterName;
    }

    public List<ModelChange> getChanges() {
        return changes;
    }

    public List<BrokenReference> getBrokenReferences() {
        return brokenReferences;
    }

    public int getFilesScanned() {
        return filesScanned;
    }

    public int getAffectedFiles() {
        return affectedFiles;
    }

    public boolean isClean() {
        return clean;
    }

    public boolean isBreaking() {
        return breaking;
    }

    public String getMarkdown() {
        return markdown;
    }
}
