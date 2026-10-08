/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import bi.saiku.ossie.model.Dataset;
import bi.saiku.ossie.model.DialectExpression;
import bi.saiku.ossie.model.DimensionMeta;
import bi.saiku.ossie.model.Expression;
import bi.saiku.ossie.model.Field;
import bi.saiku.ossie.model.Metric;
import bi.saiku.ossie.model.OssieDocument;
import bi.saiku.ossie.model.Relationship;
import bi.saiku.ossie.model.SemanticModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LookML → Ossie. The missing spoke from saiku#1730 (apache/ossie ships hub-and-spoke
 * converters for dbt, snowflake, … but none for Looker).
 *
 * <p>Mapping:
 *
 * <table>
 *   <caption>LookML → Ossie</caption>
 *   <tr><td>{@code view: orders}</td><td>{@code datasets[]}</td></tr>
 *   <tr><td>{@code source_table} / {@code sql_table_name}</td><td>{@code dataset.source}</td></tr>
 *   <tr><td>{@code dimension:}</td><td>{@code fields[]} (label / description / time flag)</td></tr>
 *   <tr><td>{@code measure:} + {@code type:}</td><td>{@code metrics[]} (aggregate expression)</td></tr>
 *   <tr><td>{@code explore:} + {@code join_on:}</td><td>{@code relationships[]}</td></tr>
 * </table>
 *
 * <p>Scope guard (from the issue): import the modelling core, degrade the esoterica. Liquid
 * {@code {{ … }}} is stripped with a warning, {@code ${TABLE}} is resolved to the view's own
 * table, derived tables import as a plain table reference (with a warning — Saiku's Ossie
 * surface resolves dataset sources against the warehouse, it doesn't materialise views),
 * {@code sql_on} joins are skipped with a warning, and unsupported measure types are dropped
 * with a warning. Nothing here fails the import.
 *
 * <p>Namespacing: Ossie metrics are model-global (a {@code Metric} carries no dataset), while
 * LookML measures are scoped to their view. Every measure therefore imports as
 * {@code <view>_<measure>} so two views that each define {@code count} don't collide.
 */
public class LookmlConverter implements VendorModelConverter {

    private static final String DIALECT = "ANSI_SQL";

    /** LookML's table alias inside {@code sql:} references. */
    private static final Pattern TABLE_ALIAS = Pattern.compile("^\\$\\{(?:TABLE|table)\\}\\.?(.+)$");

    /** Any leftover liquid — the trigger for a "degraded" warning. */
    private static final Pattern LIQUID = Pattern.compile("\\{\\{.*?\\}\\}", Pattern.DOTALL);

    /** {@code join_on: ${a.customer_id} = ${b.id}} — the only join form we translate. */
    private static final Pattern JOIN_PREDICATE = Pattern.compile(
            "^\\$\\{([A-Za-z0-9_.]+)}(?:\\s*\\.\\s*([A-Za-z0-9_]+))?\\s*=\\s*\\$\\{([A-Za-z0-9_.]+)}(?:\\s*\\.\\s*([A-Za-z0-9_]+))?$");

    /** Measure types we can express as a standard SQL aggregate. */
    private static final Map<String, String> AGGREGATIONS = new LinkedHashMap<>();

    static {
        AGGREGATIONS.put("count", "COUNT");
        AGGREGATIONS.put("count_distinct", "COUNT_DISTINCT");
        AGGREGATIONS.put("sum", "SUM");
        AGGREGATIONS.put("sum_distinct", "SUM_DISTINCT");
        AGGREGATIONS.put("avg", "AVG");
        AGGREGATIONS.put("average", "AVG");
        AGGREGATIONS.put("min", "MIN");
        AGGREGATIONS.put("max", "MAX");
        AGGREGATIONS.put("median", "MEDIAN");
        AGGREGATIONS.put("stddev", "STDDEV");
    }

    /** Measure types that need a Looker-specific runtime we can't express. */
    private static final Set<String> UNSUPPORTED_MEASURE_TYPES =
            Set.of("string_agg", "list_agg", "array_agg", "bool", "count_distinct_approx", "percentile");

    /** View parameters with no Ossie equivalent — reported once per view, then ignored. */
    private static final Set<String> IGNORED_VIEW_PARAMS = Set.of(
            "label",
            "description",
            "access",
            "group_label",
            "label_plural",
            "hidden",
            "meta",
            "fiscal_year_offset",
            "week_start_day",
            "case_sensitive",
            "persist_for",
            "query_knowledge",
            "extensions",
            "include",
            "left",
            "right");

    /** Dimension parameters with no Ossie equivalent. */
    private static final Set<String> IGNORED_DIMENSION_PARAMS = Set.of(
            "hidden",
            "style",
            "url",
            "format",
            "html",
            "case_sensitive",
            "group_label",
            "label_plural",
            "value_format",
            "match",
            "query_knowledge",
            "can_filter",
            "fill",
            "html_value",
            "link",
            "grouping",
            "description",
            "label",
            "sql",
            "type",
            "dimension_group",
            "skip_derived",
            "primary_key",
            "fanout",
            "suggest_filter",
            "suggest_dimension",
            "rollup",
            "format_strict",
            "rollup_strategy");

    @Override
    public String id() {
        return "lookml";
    }

    @Override
    public String displayName() {
        return "LookML (Looker)";
    }

    @Override
    public String description() {
        return "Looker .view / .explore files. Upload the whole project export: views become datasets, "
                + "dimensions become fields, measures become metrics, explores become relationships.";
    }

    @Override
    public List<String> fileExtensions() {
        return List.of(".lookml", ".view", ".explore", ".model");
    }

    @Override
    public VendorConversionResult convert(List<VendorModelFile> files, String requestName)
            throws VendorModelConversionException {
        SemanticModel model = new SemanticModel();
        OssieDocument doc = new OssieDocument();
        List<ConversionDiagnostic> diags = new ArrayList<>();
        List<LookmlBlock> topLevel = new ArrayList<>();
        Set<String> viewNames = new LinkedHashSet<>();

        for (VendorModelFile file : files) {
            String where = file.describe(files);
            if (file.isBlank()) {
                diags.add(ConversionDiagnostic.warning("EMPTY_FILE", where, "File is empty — skipped."));
                continue;
            }
            for (LookmlBlock block : LookmlParser.parse(file.getContent())) {
                topLevel.add(block);
            }
        }

        if (topLevel.isEmpty()) {
            throw new VendorModelConversionException(
                    "No LookML blocks found. Expected at least one `view: <name> { … }` or `explore: <name> { … }`.");
        }

        // --- views → datasets ---
        for (LookmlBlock block : topLevel) {
            if (block.isBlock() && "view".equalsIgnoreCase(block.getKey())) {
                String viewName = block.getValue();
                if (viewName == null || viewName.isBlank()) {
                    diags.add(ConversionDiagnostic.warning(
                            "VIEW_UNNAMED", "view", "A `view` block had no name — skipped."));
                    continue;
                }
                if (!viewNames.add(viewName.toLowerCase(Locale.ROOT))) {
                    diags.add(ConversionDiagnostic.error(
                            "DUPLICATE_VIEW",
                            viewName,
                            "View defined more than once in the upload — the later definition is ignored."));
                    continue;
                }
                model.getDatasets().add(toDataset(viewName, block, diags));
            }
        }

        if (model.getDatasets().isEmpty()) {
            throw new VendorModelConversionException(
                    "No `view:` blocks found in the upload. Ossie models are table-shaped, so a LookML import needs at least one view (an `.explore` on its own has nothing to attach fields to).");
        }

        // --- explores → relationships ---
        for (LookmlBlock block : topLevel) {
            if (block.isBlock() && "explore".equalsIgnoreCase(block.getKey())) {
                String exploreName = block.getValue();
                if (exploreName == null || exploreName.isBlank()) {
                    diags.add(ConversionDiagnostic.warning(
                            "EXPLORE_UNNAMED", "explore", "An `explore` block had no name — skipped."));
                    continue;
                }
                relationshipsFrom(exploreName, block, diags).forEach(model.getRelationships()::add);
            }
        }

        // --- measures → metrics (needs the dataset table map for ${TABLE} resolution) ---
        Map<String, String> tableOfView = new LinkedHashMap<>();
        for (Dataset d : model.getDatasets()) tableOfView.put(d.getName(), d.getSource());
        for (LookmlBlock block : topLevel) {
            if (block.isBlock() && "view".equalsIgnoreCase(block.getKey())) {
                String viewName = block.getValue();
                if (viewName == null) continue;
                metricsFrom(viewName, block, tableOfView.get(viewName), diags).forEach(model.getMetrics()::add);
            }
        }

        // --- leftover top-level shapes ---
        for (LookmlBlock block : topLevel) {
            if (!block.isBlock()) continue;
            String key = block.getKey() == null ? "" : block.getKey();
            switch (key.toLowerCase(Locale.ROOT)) {
                case "view", "explore" -> {
                    /* handled above */
                }
                case "include" -> diags.add(ConversionDiagnostic.info(
                        "INCLUDE_IGNORED",
                        String.valueOf(block.getValue()),
                        "`include:` is resolved by Looker, not by this converter — upload the included files too."));
                case "map", "datagroup", "connection", "access_grant", "explore_plus" -> diags.add(
                        ConversionDiagnostic.info(
                                "NON_MODEL_BLOCK",
                                key,
                                "LookML `" + key + ":` block carries no Ossie equivalent — ignored."));
                default -> diags.add(ConversionDiagnostic.info(
                        "UNRECOGNISED_BLOCK", key, "Unrecognised top-level LookML block `" + key + ":` — ignored."));
            }
        }

        String modelName = modelName(requestName, model, topLevel, diags);
        model.setName(modelName);
        model.setDescription("Imported from LookML by the Saiku vendor-model importer (saiku#1730).");

        doc.getSemanticModel().add(model);
        return new VendorConversionResult(doc, modelName).addAll(diags);
    }

    // ------------------------------------------------------------------
    // view → dataset
    // ------------------------------------------------------------------

    private Dataset toDataset(String viewName, LookmlBlock view, List<ConversionDiagnostic> diags) {
        Dataset ds = new Dataset();
        ds.setName(viewName.toLowerCase(Locale.ROOT));

        String table = view.param("source_table").orElse(null);
        if (table == null || table.isBlank()) {
            // sql_table_name: { schema: "public" table: "orders" }
            LookmlBlock stn = view.child("sql_table_name").orElse(null);
            if (stn != null) {
                table = stn.param("table").orElse(null);
            }
        }
        if (table != null && !table.isBlank()) {
            ds.setSource(table.trim());
        } else if (view.child("derived_table").isPresent()) {
            ds.setSource(viewName.toLowerCase(Locale.ROOT));
            diags.add(ConversionDiagnostic.warning(
                    "DERIVED_TABLE",
                    viewName,
                    "`derived_table:` (a LookML SQL view) imports as a plain reference to "
                            + viewName.toLowerCase(Locale.ROOT) + " — create that view in the warehouse and re-import, "
                            + "or point the dataset at the base table."));
        } else {
            ds.setSource(viewName.toLowerCase(Locale.ROOT));
            diags.add(ConversionDiagnostic.warning(
                    "NO_SOURCE_TABLE",
                    viewName,
                    "No `source_table:` / `sql_table_name:` — the dataset points at a table named "
                            + viewName.toLowerCase(Locale.ROOT) + "; edit the Ossie YAML if the real table differs."));
        }

        Optional<String> label = view.param("label");
        Optional<String> description = view.param("description");
        String dsDescription = description.or(() -> label).orElse(null);
        if (dsDescription != null) ds.setDescription(dsDescription);

        Set<String> fieldNames = new LinkedHashSet<>();
        for (LookmlBlock child : view.getChildren()) {
            if (child.isBlock() && "dimension".equalsIgnoreCase(child.getKey())) {
                addField(ds, viewName, child, diags, fieldNames);
            } else if (child.isBlock() && "measure".equalsIgnoreCase(child.getKey())) {
                // Measures land on the metric list, not on the dataset. Recorded here so the
                // dataset's `primary_key` inference and the ignored-params report stay in one place.
                continue;
            } else if (child.isBlock() && "dimension_groups".equalsIgnoreCase(child.getKey())) {
                diags.add(ConversionDiagnostic.warning(
                        "DIMENSION_GROUP",
                        viewName,
                        "`dimension_groups:` timeframes have no direct Ossie equivalent — declare them as"
                                + " individual `dimension:`s with `type: date` if you need them."));
            } else if (child.isBlock() && "filter".equalsIgnoreCase(child.getKey())) {
                diags.add(ConversionDiagnostic.info(
                        "FILTER_IGNORED", viewName, "`filter:` parameters are Looker UI state, not model — ignored."));
            } else if (!child.isBlock() && "primary_key".equalsIgnoreCase(child.getKey())) {
                ds.getPrimaryKey().add(child.getValue().trim().toLowerCase(Locale.ROOT));
            }
        }

        if (ds.getFields().isEmpty()) {
            diags.add(ConversionDiagnostic.warning(
                    "NO_FIELDS",
                    viewName,
                    "View has no importable dimensions — the dataset will not be queryable on its own."));
        }
        // A missing primary key is NOT reported here: OssieYamlValidator reports it once per
        // dataset for every format, so one fact doesn't appear twice in the same report.

        return ds;
    }

    private void addField(
            Dataset ds,
            String viewName,
            LookmlBlock dimension,
            List<ConversionDiagnostic> diags,
            Set<String> fieldNames) {
        String rawName = dimension.getValue();
        if (rawName == null || rawName.isBlank()) {
            diags.add(ConversionDiagnostic.warning(
                    "DIMENSION_UNNAMED", viewName, "A `dimension` block had no name — skipped."));
            return;
        }
        String name = rawName.trim().toLowerCase(Locale.ROOT);
        if (!fieldNames.add(name)) {
            diags.add(ConversionDiagnostic.error(
                    "DUPLICATE_FIELD",
                    viewName + "." + name,
                    "Dimension declared more than once — the later one is ignored."));
            return;
        }
        if (dimension.flag("hidden")) {
            diags.add(
                    ConversionDiagnostic.info("HIDDEN_FIELD", viewName + "." + name, "`hidden: yes` — not imported."));
            return;
        }
        // primary_key: yes on a dimension is how modern LookML marks a key.
        if (dimension.flag("primary_key")) {
            ds.getPrimaryKey().add(name);
        }

        Field field = new Field();
        field.setName(name);
        String expression = dimensionExpression(dimension, name);
        field.setExpression(ansi(expression));
        dimension.param("label").ifPresent(field::setLabel);
        dimension.param("description").ifPresent(field::setDescription);

        String type = dimension
                .param("type")
                .or(() -> dimension.param("dimension_group"))
                .orElse("");
        String normalisedType = type.toLowerCase(Locale.ROOT);
        if (normalisedType.contains("time") || normalisedType.contains("date")) {
            field.setDimension(new DimensionMeta(Boolean.TRUE));
        }
        ds.getFields().add(field);

        if (expression == null) {
            diags.add(ConversionDiagnostic.warning(
                    "FIELD_NO_SQL",
                    viewName + "." + name,
                    "No `sql:` — the field is assumed to select the column of the same name."));
        }
        List<String> ignored = ignoredParams(dimension, IGNORED_DIMENSION_PARAMS);
        if (!ignored.isEmpty()) {
            diags.add(ConversionDiagnostic.info(
                    "IGNORED_PARAMS",
                    viewName + "." + name,
                    "Ignored dimension parameters: " + String.join(", ", ignored) + "."));
        }
    }

    // ------------------------------------------------------------------
    // measure → metric
    // ------------------------------------------------------------------

    private List<Metric> metricsFrom(
            String viewName, LookmlBlock view, String table, List<ConversionDiagnostic> diags) {
        List<Metric> metrics = new ArrayList<>();
        String prefix = viewName.toLowerCase(Locale.ROOT) + "_";

        for (LookmlBlock child : view.getChildren()) {
            if (!child.isBlock() || !"measure".equalsIgnoreCase(child.getKey())) continue;
            String rawName = child.getValue();
            if (rawName == null || rawName.isBlank()) {
                diags.add(ConversionDiagnostic.warning(
                        "MEASURE_UNNAMED", viewName, "A `measure` block had no name — skipped."));
                continue;
            }
            String name = prefix + rawName.trim().toLowerCase(Locale.ROOT);
            String element = viewName + "." + rawName.trim();
            if (child.flag("hidden")) {
                diags.add(ConversionDiagnostic.info("HIDDEN_MEASURE", element, "`hidden: yes` — not imported."));
                continue;
            }
            String type = child.param("type").orElse("sum").trim().toLowerCase(Locale.ROOT);
            if (UNSUPPORTED_MEASURE_TYPES.contains(type)) {
                diags.add(ConversionDiagnostic.warning(
                        "UNSUPPORTED_MEASURE",
                        element,
                        "Measure type `" + type + "` has no SQL aggregate equivalent — not imported."));
                continue;
            }
            String agg = AGGREGATIONS.get(type);
            if (agg == null) {
                diags.add(ConversionDiagnostic.warning(
                        "UNSUPPORTED_MEASURE", element, "Unknown measure type `" + type + "` — not imported."));
                continue;
            }
            String sql = child.param("sql").orElse(null);
            if (sql != null && sql.contains("{{")) {
                diags.add(ConversionDiagnostic.warning(
                        "LIQUID_IN_MEASURE",
                        element,
                        "Liquid templating in `sql:` can't be evaluated offline — the template was stripped."
                                + " Review the metric expression after import."));
            }
            String expression = aggregate(agg, resolveTableReference(sql, table), type, element, diags);
            if (expression == null) {
                continue; // aggregate() already reported why
            }

            Metric metric = new Metric();
            metric.setName(name);
            metric.setExpression(ansi(expression));
            child.param("description").ifPresent(metric::setDescription);
            child.param("label").ifPresent(l -> {
                if (metric.getDescription() == null) metric.setDescription(l);
            });
            metrics.add(metric);
        }
        return metrics;
    }

    /**
     * Wrap a LookML measure's {@code sql:} in its aggregate. {@code count} with no {@code sql:}
     * is {@code COUNT(*)}; {@code count_distinct} / {@code sum_distinct} take {@code DISTINCT}.
     */
    private String aggregate(String agg, String sql, String type, String element, List<ConversionDiagnostic> diags) {
        if (sql == null || sql.isBlank()) {
            if ("COUNT".equals(agg)) return "COUNT(*)";
            diags.add(ConversionDiagnostic.warning(
                    "MEASURE_NO_SQL",
                    element,
                    "Measure has no `sql:` and type `" + type + "` needs one — not imported as a metric."));
            return null;
        }
        if ("COUNT".equals(agg) && "*".equals(sql.trim())) return "COUNT(*)";
        if ("COUNT_DISTINCT".equals(agg)) return "COUNT(DISTINCT " + sql + ")";
        if ("SUM_DISTINCT".equals(agg)) return "SUM(DISTINCT " + sql + ")";
        return agg + "(" + sql + ")";
    }

    // ------------------------------------------------------------------
    // explore → relationships
    // ------------------------------------------------------------------

    private List<Relationship> relationshipsFrom(
            String exploreName, LookmlBlock explore, List<ConversionDiagnostic> diags) {
        List<Relationship> out = new ArrayList<>();
        for (LookmlBlock join : explore.getChildren()) {
            if (!join.isBlock()) continue;
            String from = join.param("from").orElse(null);
            if (from == null || from.isBlank()) continue;
            String joinOn = join.param("join_on").orElse(null);
            String element = exploreName + " -> " + from;
            if (joinOn == null || joinOn.isBlank()) {
                if (join.child("sql_on").isPresent()
                        || join.child("sql_join_one").isPresent()) {
                    diags.add(ConversionDiagnostic.warning(
                            "SQL_JOIN",
                            element,
                            "`sql_on:` / `sql_join_one:` is a hand-written predicate — Ossie "
                                    + "relationships must be column-to-column, so this join is not imported."));
                } else {
                    diags.add(ConversionDiagnostic.warning(
                            "NO_JOIN_ON",
                            element,
                            "Join has no `join_on:` — not imported (it would be a cross join)."));
                }
                continue;
            }
            Matcher m = JOIN_PREDICATE.matcher(joinOn.trim());
            if (!m.matches()) {
                diags.add(ConversionDiagnostic.warning(
                        "UNPARSEABLE_JOIN_ON",
                        element,
                        "`join_on: " + joinOn.trim() + "` isn't a simple column = column "
                                + "equality — not imported."));
                continue;
            }
            String left = columnRef(m.group(1), m.group(2));
            String right = columnRef(m.group(3), m.group(4));
            Relationship rel = new Relationship();
            rel.setName(exploreName.toLowerCase(Locale.ROOT) + "_to_"
                    + from.toLowerCase(Locale.ROOT).replace('.', '_'));
            rel.setFrom(exploreName.toLowerCase(Locale.ROOT));
            rel.setTo(from.toLowerCase(Locale.ROOT));
            rel.getFromColumns().add(left);
            rel.getToColumns().add(right);
            out.add(rel);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Strip a {@code ${TABLE}.} / {@code ${table}.} prefix and any liquid. A bare
     * {@code ${other_view}.col} reference is left alone but flagged — Ossie field
     * expressions run inside the dataset's own scope, so a cross-view reference has to be
     * rewritten by hand.
     */
    private String dimensionExpression(LookmlBlock dimension, String fieldName) {
        Optional<String> sql = dimension.param("sql");
        if (sql.isEmpty() || sql.get().isBlank()) return null;
        String raw = sql.get().trim();
        if (raw.contains("{{")) {
            raw = LIQUID.matcher(raw).replaceAll("").trim();
        }
        Matcher alias = TABLE_ALIAS.matcher(raw);
        if (alias.matches()) {
            return blankToNull(stripAlias(alias.group(1).trim()));
        }
        return blankToNull(stripAlias(raw));
    }

    /** A value that was entirely liquid leaves nothing behind — report it as "no sql" instead. */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private String resolveTableReference(String sql, String table) {
        if (sql == null) return null;
        String raw = sql.trim();
        if (raw.contains("{{")) raw = LIQUID.matcher(raw).replaceAll("").trim();
        if ("*".equals(raw)) return "*";
        return blankToNull(stripAlias(raw));
    }

    private String stripAlias(String expr) {
        return expr.replaceAll("^\\$\\{[A-Za-z0-9_]+\\}\\.", "");
    }

    /** {@code customers.id} → {@code id}; a bare {@code ${x}} with no column → the reference. */
    private String columnRef(String viewPart, String columnPart) {
        String view = viewPart == null ? "" : viewPart.trim();
        if (columnPart != null && !columnPart.isBlank())
            return columnPart.trim().toLowerCase(Locale.ROOT);
        // `${a}` alone (Looker resolved the column elsewhere) — keep the view name out of it.
        int dot = view.lastIndexOf('.');
        String col = dot >= 0 ? view.substring(dot + 1) : view;
        return col.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> ignoredParams(LookmlBlock block, Set<String> known) {
        List<String> ignored = new ArrayList<>();
        for (LookmlBlock child : block.getChildren()) {
            if (child.isBlock() || child.getKey() == null) continue;
            if (!known.contains(child.getKey().toLowerCase(Locale.ROOT))) {
                ignored.add(child.getKey().toLowerCase(Locale.ROOT));
            }
        }
        return ignored;
    }

    private String modelName(
            String requestName, SemanticModel model, List<LookmlBlock> topLevel, List<ConversionDiagnostic> diags) {
        if (requestName != null && !requestName.isBlank()) return requestName.trim();
        for (LookmlBlock block : topLevel) {
            if (block.isBlock()
                    && "explore".equalsIgnoreCase(block.getKey())
                    && block.getValue() != null
                    && !block.getValue().isBlank()) {
                diags.add(ConversionDiagnostic.info(
                        "MODEL_NAME_DERIVED", "model", "Model name taken from the first `explore:` in the upload."));
                return block.getValue().trim();
            }
        }
        diags.add(ConversionDiagnostic.info(
                "MODEL_NAME_DERIVED", "model", "Model name taken from the first `view:` in the upload."));
        return model.getDatasets().get(0).getName();
    }

    private static Expression ansi(String expression) {
        Expression e = new Expression();
        DialectExpression d = new DialectExpression();
        d.setDialect(DIALECT);
        d.setExpression(expression);
        e.getDialects().add(d);
        return e;
    }
}
