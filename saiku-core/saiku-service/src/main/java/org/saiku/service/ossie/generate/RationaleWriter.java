/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.generate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.saiku.service.schema.generate.delta.DeltaReport;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.enrich.PiiFilter;
import org.saiku.service.schema.generate.enrich.ops.AggregatorOp;
import org.saiku.service.schema.generate.enrich.ops.DegenerateDimOp;
import org.saiku.service.schema.generate.enrich.ops.HierarchyOp;
import org.saiku.service.schema.generate.enrich.ops.IgnoreOp;
import org.saiku.service.schema.generate.enrich.ops.RenameOp;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;

/**
 * Renders the {@code <name>.generated.rationale.md} audit document that ships next to every
 * generated Ossie model (saiku#1439).
 *
 * <p>The document is the enterprise-credibility half of the feature: an operator who did not
 * write the warehouse schema needs to be able to answer "why is this measure called Revenue and
 * not {@code sum_amt_2}" without reading Java. So it carries, in order:
 *
 * <ol>
 *   <li>a provenance header (source data source, model name, when, degraded flag),
 *   <li>one entry per applied suggestion, quoting the op's own {@link SuggestionOp#rationale()},
 *   <li>the PII-looking columns the enrichment layer withheld from the vendor,
 *   <li>the deterministic inference the rules produced (cubes, facts, dims, measures),
 *   <li>the delta against the previous generation when there was one,
 *   <li>cubes the Mondrian→Ossie converter could not map, if any.
 * </ol>
 *
 * <p>Stateless and thread-safe. The output is deterministic for a given input: timestamps come
 * from the caller's {@link Instant}, never {@code Instant.now()}, so the same run renders
 * byte-identical text and the file diffs cleanly between regenerations.
 */
public final class RationaleWriter {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private RationaleWriter() {}

    /**
     * Render the rationale document for a completed (or failed) generation job.
     *
     * @param piiColumns table-qualified names of every introspected column the {@link PiiFilter}
     *     deny list matched, e.g. {@code CUSTOMER.EMAIL}. Computed by the caller from the
     *     {@link org.saiku.service.schema.generate.model.DbModel} rather than derived here from
     *     the draft: the draft only carries the columns the dimension builder promoted, so a PII
     *     column on a dimension that stayed a bare key would go unreported. The audit question is
     *     "what did the vendor not see", and the vendor saw the whole warehouse schema.
     */
    public static String render(
            OssieGenerationJob job, DraftSchema draft, List<String> piiColumns, Instant generatedAt) {
        StringBuilder sb = new StringBuilder(2048);
        header(sb, job, generatedAt);

        sb.append("## LLM decisions\n\n");
        List<SuggestionOp> ops = job.appliedOps();
        if (ops.isEmpty()) {
            sb.append("No enrichment suggestions were applied. ")
                    .append("The model below is entirely the product of the deterministic rules ")
                    .append("(fact/dimension classification, PK-FK join inference, measure builders).\n\n");
        } else {
            sb.append("One entry per suggestion, in the order they were applied to the draft.\n\n");
            for (int i = 0; i < ops.size(); i++) {
                entry(sb, i + 1, ops.get(i));
            }
        }

        skippedDecisionSection(sb, job);
        piiSection(sb, piiColumns);
        inferenceSection(sb, draft);
        deltaSection(sb, job.deltaReport());
        skippedSection(sb, job.skippedCubes());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // sections
    // ------------------------------------------------------------------

    private static void header(StringBuilder sb, OssieGenerationJob job, Instant generatedAt) {
        sb.append("# Generation rationale — ").append(job.modelName()).append("\n\n");
        sb.append("- **Data source:** `").append(job.dataSourceId()).append("`\n");
        sb.append("- **Generated:** ").append(TS.format(generatedAt)).append('\n');
        sb.append("- **Job:** `").append(job.id()).append("`\n");
        sb.append("- **Enrichment:** ")
                .append(
                        job.degraded()
                                ? "**degraded** — at least one chunk fell back to offline rules after the LLM provider"
                                        + " failed"
                                : "LLM-backed (or offline rules, if no provider is configured)")
                .append('\n');
        sb.append("- **Emitted model:** `").append(job.yamlPath()).append("`\n");
        sb.append("- **Shape:** ")
                .append(job.semanticModelCount())
                .append(" semantic model(s), ")
                .append(job.datasetCount())
                .append(" dataset(s), ")
                .append(job.fieldCount())
                .append(" field(s), ")
                .append(job.metricCount())
                .append(" metric(s), ")
                .append(job.relationshipCount())
                .append(" relationship(s)\n\n");
    }

    private static void entry(StringBuilder sb, int n, SuggestionOp op) {
        sb.append("### ").append(n).append(". ").append(kind(op)).append('\n');
        sb.append("- **Target:** `").append(op.targetPath()).append("`\n");
        sb.append("- **Confidence:** ")
                .append(String.format(Locale.ROOT, "%.2f", op.confidence()))
                .append('\n');
        sb.append("- **Why:** ").append(nullToEmpty(op.rationale())).append('\n');
        sb.append("- **Change:** ").append(change(op)).append("\n\n");
    }

    private static String kind(SuggestionOp op) {
        if (op instanceof RenameOp) {
            return "Rename";
        }
        if (op instanceof HierarchyOp) {
            return "Hierarchy grouping";
        }
        if (op instanceof AggregatorOp) {
            return "Aggregator";
        }
        if (op instanceof DegenerateDimOp) {
            return "Degenerate dimension";
        }
        if (op instanceof IgnoreOp) {
            return "Ignore (dropped from the model)";
        }
        return op.getClass().getSimpleName();
    }

    private static String change(SuggestionOp op) {
        if (op instanceof RenameOp r) {
            return "`" + nullToEmpty(r.oldCaption()) + "` → `" + nullToEmpty(r.newCaption()) + "`"
                    + (blank(r.description()) ? "" : " (description: \"" + r.description() + "\")");
        }
        if (op instanceof HierarchyOp h) {
            return "hierarchy `" + nullToEmpty(h.hierarchyName()) + "` over levels ["
                    + String.join(", ", h.levelColumns()) + "]";
        }
        if (op instanceof AggregatorOp a) {
            return "aggregator `" + a.oldAggregator() + "` → `" + a.newAggregator() + "`";
        }
        if (op instanceof DegenerateDimOp d) {
            return "promote fact column `" + nullToEmpty(d.factColumn()) + "` to dimension `" + nullToEmpty(d.dimName())
                    + "`";
        }
        if (op instanceof IgnoreOp) {
            return "element removed from the emitted model";
        }
        return "—";
    }

    /**
     * Decisions the generator proposed and could not apply. Rendered prominently rather than
     * dropped: a reader who sees only the applied entries cannot distinguish "the model reflects
     * every decision" from "the model reflects the decisions that happened to work".
     */
    private static void skippedDecisionSection(StringBuilder sb, OssieGenerationJob job) {
        List<OssieGenerationJob.SkippedOp> skipped = job.skippedOps();
        if (skipped.isEmpty()) {
            return;
        }
        sb.append("### Rejected decisions\n\n");
        sb.append(skipped.size())
                .append(" suggestion(s) could not be applied and are NOT reflected in the model "
                        + "below. Usually a stale path, or an element an earlier decision already "
                        + "removed.\n\n");
        for (OssieGenerationJob.SkippedOp s : skipped) {
            sb.append("- `")
                    .append(s.op().targetPath())
                    .append("` — ")
                    .append(kind(s.op()))
                    .append(": ")
                    .append(nullToEmpty(s.reason()))
                    .append('\n');
        }
        sb.append('\n');
    }

    /**
     * List the columns whose names the PII deny list matched. This is the "what the vendor never
     * saw" half of the audit story: {@link PiiFilter} strips these from the sample map before any
     * provider call, so an operator reading the rationale can confirm no PII left the building.
     */
    private static void piiSection(StringBuilder sb, List<String> hits) {
        sb.append("## PII\n\n");
        if (hits == null || hits.isEmpty()) {
            sb.append("No introspected column matched the PII deny list.\n\n");
            return;
        }
        sb.append("Columns matching the PII deny list. Their sample values were withheld from the ")
                .append("LLM provider before every call (see `PiiFilter`); only the column metadata ")
                .append("reached the vendor. Annotate the source schema with ")
                .append("`saiku.semantic.pii=true` to have the flag carried into the emitted ")
                .append("Ossie custom extension.\n\n");
        for (String h : hits) {
            sb.append("- `").append(h).append("`\n");
        }
        sb.append('\n');
    }

    private static void inferenceSection(StringBuilder sb, DraftSchema draft) {
        sb.append("## Deterministic inference\n\n");
        if (draft == null || draft.cubes().isEmpty()) {
            sb.append("No cubes were inferred.\n\n");
            return;
        }
        sb.append("Produced by the rule-based layer, not the LLM. `fact` is the classified fact ")
                .append("table; measures are the aggregators the measure builder proposed.\n\n");
        for (DraftCube cube : draft.cubes()) {
            sb.append("### Cube `").append(cube.name()).append("`\n\n");
            sb.append("- **Fact table:** `").append(cube.sourceFactTable()).append("`\n");
            sb.append("- **Dimensions:**\n");
            if (cube.dimensions().isEmpty()) {
                sb.append("  - (none)\n");
            }
            for (DraftDimension dim : cube.dimensions()) {
                sb.append("  - `")
                        .append(dim.name())
                        .append("` — table `")
                        .append(dim.sourceTable())
                        .append("`, type ")
                        .append(dim.type())
                        .append(", foreign key ")
                        // A degenerate TIME dim is colocated on the fact row and has no FK;
                        // printing a literal `null` reads as a defect in the audit document.
                        .append(
                                dim.foreignKey() == null
                                        ? "n/a (degenerate — lives on the fact row)"
                                        : "`" + dim.foreignKey() + "`")
                        .append(", levels [")
                        .append(String.join(", ", levelColumns(dim)))
                        .append("]\n");
            }
            sb.append("- **Measures:**\n");
            if (cube.measures().isEmpty()) {
                sb.append("  - (none)\n");
            }
            for (DraftMeasure m : cube.measures()) {
                sb.append("  - `")
                        .append(m.name())
                        .append("` = ")
                        .append(m.aggregator())
                        .append('(')
                        .append(m.column() == null ? "*" : m.column())
                        .append(")\n");
            }
            sb.append('\n');
        }
        if (!draft.sharedDimensions().isEmpty()) {
            sb.append("### Shared dimensions\n\n");
            for (DraftDimension dim : draft.sharedDimensions()) {
                sb.append("- `")
                        .append(dim.name())
                        .append("` — table `")
                        .append(dim.sourceTable())
                        .append("`\n");
            }
            sb.append('\n');
        }
    }

    private static List<String> levelColumns(DraftDimension dim) {
        List<String> out = new java.util.ArrayList<>();
        for (DraftHierarchy h : dim.hierarchies()) {
            for (DraftLevel l : h.levels()) {
                out.add(l.column());
            }
        }
        return out;
    }

    /**
     * The regenerate-diff section. When a previous generation exists the reconciler has already
     * tagged every element, so this only has to render the counts and enumerate the two
     * interesting buckets.
     */
    private static void deltaSection(StringBuilder sb, DeltaReport delta) {
        if (delta == null) {
            return;
        }
        sb.append("## Changes since the previous generation\n\n");
        sb.append("- **New elements:** ").append(delta.newPaths().size()).append('\n');
        sb.append("- **Unchanged elements:** ")
                .append(delta.existingPaths().size())
                .append('\n');
        sb.append("- **Removed upstream:** ")
                .append(delta.removedUpstreamPaths().size())
                .append("\n\n");
        if (!delta.newPaths().isEmpty()) {
            sb.append("New:\n\n");
            for (String p : delta.newPaths()) {
                sb.append("- `").append(p).append("`\n");
            }
            sb.append('\n');
        }
        if (!delta.removedUpstreamPaths().isEmpty()) {
            sb.append("Removed upstream (kept in this model — a regeneration never deletes):\n\n");
            for (String p : delta.removedUpstreamPaths()) {
                sb.append("- `").append(p).append("`\n");
            }
            sb.append('\n');
        }
    }

    private static void skippedSection(StringBuilder sb, List<String> skippedCubes) {
        if (skippedCubes == null || skippedCubes.isEmpty()) {
            return;
        }
        sb.append("## Cubes not mapped\n\n");
        sb.append("The Mondrian→Ossie converter recognises the classic-3 and Mondrian-4 `<MeasureGroup>` ")
                .append("shapes. These cubes were neither — most often virtual cubes, shared dimensions ")
                .append("consumed via `<DimensionUsage>`, or parent-child hierarchies. They are absent ")
                .append("from the emitted Ossie model:\n\n");
        for (String c : skippedCubes) {
            sb.append("- `").append(c).append("`\n");
        }
        sb.append('\n');
    }

    // ------------------------------------------------------------------

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
