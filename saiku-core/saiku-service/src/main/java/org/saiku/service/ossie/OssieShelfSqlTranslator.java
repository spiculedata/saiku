/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.saiku.olap.query2.OssieQueryModel;

/**
 * Translate an {@link OssieQueryModel} shelf-state payload into the SQL string that the
 * Calcite-backed Ossie adapter executes. Intentionally simple — the auto-join rule already
 * shipped in {@code saiku-sql} handles cross-dataset joins, so we can list every needed dataset
 * in the {@code FROM} clause as a Cartesian and the planner injects the join predicates.
 *
 * <p>Emitted shape (illustrative):
 *
 * <pre>{@code
 * SELECT
 *   "customers"."region" AS "customers.region",
 *   SUM("orders"."amount") AS "revenue"
 * FROM "SALES"."orders", "SALES"."customers"
 * WHERE "customers"."region" = 'NA'
 * GROUP BY "customers"."region"
 * ORDER BY "customers"."region" ASC
 * LIMIT 100
 * }</pre>
 *
 * <p>Cross-dataset joins: the auto-join rule adds e.g. {@code "orders"."customer_id" =
 * "customers"."id"} at plan time from the Ossie relationship declaration. The translator only
 * has to name the datasets; no join syntax in the emitted SQL.
 */
public final class OssieShelfSqlTranslator {

    /** Alias used for the column returned by a metric (client uses metric.name as the key). */
    public String translate(OssieQueryModel model, OssieModelDto semantic) {
        if (semantic == null) throw new IllegalStateException("Ossie semantic model is required");
        if (model.getFactDataset() == null || model.getFactDataset().isBlank()) {
            throw new IllegalArgumentException("OssieQueryModel.factDataset is required");
        }
        String schema = semantic.getName();

        // --- collect referenced datasets ---
        Set<String> datasets = new LinkedHashSet<>();
        datasets.add(model.getFactDataset());
        for (OssieQueryModel.FieldRef f : model.getRows()) datasets.add(f.getDataset());
        for (OssieQueryModel.FieldRef f : model.getColumns()) datasets.add(f.getDataset());
        for (OssieQueryModel.FilterExpr f : model.getFilters()) {
            if (f.getDataset() != null) datasets.add(f.getDataset());
        }
        for (OssieQueryModel.SortRef s : model.getSorts()) {
            if (s.getDataset() != null) datasets.add(s.getDataset());
        }

        // --- SELECT ---
        List<String> selectCols = new ArrayList<>();
        List<String> groupByCols = new ArrayList<>();
        for (OssieQueryModel.FieldRef f : model.getRows()) {
            String qref = qualifiedField(f, semantic);
            selectCols.add(qref + " AS " + quoteAlias(f.getDataset() + "." + f.getField()));
            groupByCols.add(qref);
        }
        for (OssieQueryModel.FieldRef f : model.getColumns()) {
            String qref = qualifiedField(f, semantic);
            selectCols.add(qref + " AS " + quoteAlias(f.getDataset() + "." + f.getField()));
            groupByCols.add(qref);
        }
        for (OssieQueryModel.MetricRef v : model.getValues()) {
            String expr = lookupMetricExpression(semantic, v.getMetric());
            String override = v.getAggregation();
            if (override != null && !override.isBlank()) {
                expr = swapAggregation(expr, override.toUpperCase());
            }
            selectCols.add(expr + " AS " + quoteAlias(v.getMetric()));
        }
        if (selectCols.isEmpty()) throw new IllegalArgumentException("OssieQueryModel has no columns to select");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(String.join(", ", selectCols));

        // --- FROM ---
        // Emit datasets unqualified — Calcite's defaultSchema (set to the Ossie model
        // name in the connect JSON) resolves them. We tried schema-qualifying earlier
        // ("Pharma"."fact_pharma") but Calcite reports "Object 'Pharma' not found" even
        // though the schema is registered by that name; probable interaction with the
        // caseSensitive=false connect flag. For single-schema-per-connection (the MVP
        // one-model-per-datasource shape) the unqualified form always resolves. If we
        // ever wire cross-schema queries the qualifier will need to come back with a
        // different case-handling story.
        List<String> fromRefs = new ArrayList<>();
        for (String ds : datasets) fromRefs.add(quoteRef(ds));
        sql.append(" FROM ").append(String.join(", ", fromRefs));
        // schema is currently unreferenced but kept for the follow-up. Silence "unused".
        if (schema == null) throw new IllegalStateException("schema null after guard");

        // --- WHERE ---
        List<String> whereClauses = new ArrayList<>();
        for (OssieQueryModel.FilterExpr f : model.getFilters()) {
            whereClauses.add(filterToSql(f, semantic));
        }
        if (!whereClauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereClauses));
        }

        // --- GROUP BY (only when values are present — otherwise it's a rowset) ---
        if (!model.getValues().isEmpty() && !groupByCols.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupByCols));
        }

        // --- ORDER BY ---
        if (!model.getSorts().isEmpty()) {
            List<String> orderCols = new ArrayList<>();
            for (OssieQueryModel.SortRef s : model.getSorts()) {
                String ref;
                if (s.getMetric() != null && !s.getMetric().isBlank()) {
                    // Aggregate columns can be referenced by alias in ORDER BY (Calcite handles this).
                    ref = quoteRef(s.getMetric());
                } else {
                    String colExpr = lookupFieldExpression(semantic, s.getDataset(), s.getField());
                    String col = (colExpr != null && !colExpr.isBlank()) ? quoteRef(colExpr) : quoteRef(s.getField());
                    ref = quoteRef(s.getDataset()) + "." + col;
                }
                orderCols.add(ref + " " + normalizedDirection(s.getDirection()));
            }
            sql.append(" ORDER BY ").append(String.join(", ", orderCols));
        }

        if (model.getLimit() != null && model.getLimit() > 0) {
            sql.append(" LIMIT ").append(model.getLimit());
        }

        return sql.toString();
    }

    /** The aggregation function names the workbench UI's picker exposes. */
    private static final java.util.Set<String> KNOWN_AGGS = java.util.Set.of("SUM", "AVG", "MIN", "MAX", "COUNT");

    /**
     * Regex matching a simple {@code AGG(expr)} outer wrapper on a metric expression.
     * Captures group 1 = the aggregation function name; group 2 = everything between the
     * outermost parens. Doesn't handle nested aggregates in the outer position — those
     * expressions bypass the override and stay as-declared in the YAML.
     */
    private static final java.util.regex.Pattern OUTER_AGG =
            java.util.regex.Pattern.compile("^\\s*(SUM|AVG|MIN|MAX|COUNT)\\s*\\((.*)\\)\\s*$");

    /**
     * Rewrite the outer aggregation function on a metric expression. Only fires when the
     * expression looks like {@code AGG(...)} at the top level AND the override is one of
     * the known aggregations; otherwise the expression is returned unchanged (silent
     * pass-through, no exception — so weird expressions like {@code SUM(x) + SUM(y)} keep
     * working with their declared aggregation).
     */
    static String swapAggregation(String expr, String override) {
        if (expr == null || override == null || !KNOWN_AGGS.contains(override)) return expr;
        java.util.regex.Matcher m = OUTER_AGG.matcher(expr);
        if (!m.matches()) return expr;
        String inner = m.group(2);
        // Fast sanity check on balanced parens so we don't rewrite something like
        //   SUM(x) + AVG(y)
        // which the outer regex would greedily consume up to the last close-paren.
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth < 0) return expr;
            }
        }
        if (depth != 0) return expr;
        // Only COUNT accepts `*` as its argument in ANSI SQL — SUM(*), AVG(*), MIN(*),
        // MAX(*) are all parse errors. If the declared metric is COUNT(*) and the
        // override isn't COUNT, don't rewrite: preserve the declared aggregation rather
        // than emit broken SQL. Caught by OssieFuzzIT combinatorial pass 2026-07-08.
        if ("*".equals(inner.trim()) && !"COUNT".equals(override)) return expr;
        return override + "(" + inner + ")";
    }

    /**
     * Resolve a shelf {@link OssieQueryModel.FieldRef} to its qualified SQL expression.
     *
     * <p>When the model declares an ANSI_SQL expression on the field, we use it — this covers
     * converters like the dbt/MetricFlow bridge that give fields agent-friendly names
     * ({@code customer_country}) mapped to raw columns ({@code COUNTRY}). When no expression
     * is declared, fall back to the field's name itself; that's the shape our Pharma / TPCDS /
     * Flights demo YAMLs use where the field name IS the underlying column.
     */
    private String qualifiedField(OssieQueryModel.FieldRef f, OssieModelDto semantic) {
        if (f.getDataset() == null || f.getField() == null) {
            throw new IllegalArgumentException("FieldRef requires both dataset and field");
        }
        String columnExpr = lookupFieldExpression(semantic, f.getDataset(), f.getField());
        String col = (columnExpr != null && !columnExpr.isBlank()) ? quoteRef(columnExpr) : quoteRef(f.getField());
        return quoteRef(f.getDataset()) + "." + col;
    }

    /** Walk the semantic model for a field's ANSI_SQL expression. Returns null when absent. */
    private String lookupFieldExpression(OssieModelDto semantic, String datasetName, String fieldName) {
        if (semantic == null || datasetName == null || fieldName == null) return null;
        for (OssieModelDto.Dataset ds : semantic.getDatasets()) {
            if (!datasetName.equalsIgnoreCase(ds.getName())) continue;
            for (OssieModelDto.Field field : ds.getFields()) {
                if (fieldName.equalsIgnoreCase(field.getName())) {
                    return field.getExpression();
                }
            }
        }
        return null;
    }

    /**
     * Look up the ANSI SQL expression the metric maps to. Falls back to referencing the metric by
     * name as a table {@code "<metric>"} on the assumption that the Calcite adapter has exposed
     * the metric as a view; that path only works when the SQL has a single value and no
     * dimensions, so realistic queries need a real expression.
     */
    private String lookupMetricExpression(OssieModelDto semantic, String metricName) {
        for (OssieModelDto.Metric m : semantic.getMetrics()) {
            if (metricName.equals(m.getName())) {
                if (m.getExpression() != null && !m.getExpression().isBlank()) return m.getExpression();
                throw new IllegalStateException(
                        "Ossie metric '" + metricName + "' has no ANSI SQL expression declared in the model");
            }
        }
        throw new IllegalArgumentException(
                "Ossie metric '" + metricName + "' not found in semantic model '" + semantic.getName() + "'");
    }

    private String filterToSql(OssieQueryModel.FilterExpr f, OssieModelDto semantic) {
        String col;
        if (f.getDataset() != null && !f.getDataset().isBlank()) {
            String colExpr = lookupFieldExpression(semantic, f.getDataset(), f.getField());
            String colName = (colExpr != null && !colExpr.isBlank()) ? colExpr : f.getField();
            col = quoteRef(f.getDataset()) + "." + quoteRef(colName);
        } else {
            col = quoteRef(f.getField());
        }
        String op = f.getOp() == null ? "EQ" : f.getOp().toUpperCase();
        switch (op) {
            case "EQ":
                return col + " = " + literal(f.getValue());
            case "NEQ":
                return col + " <> " + literal(f.getValue());
            case "LT":
                return col + " < " + literal(f.getValue());
            case "LTE":
                return col + " <= " + literal(f.getValue());
            case "GT":
                return col + " > " + literal(f.getValue());
            case "GTE":
                return col + " >= " + literal(f.getValue());
            case "IN": {
                if (f.getValues().isEmpty()) {
                    // Empty IN would break the SQL parser — synthesize the trivially-false predicate
                    // so the caller sees zero rows rather than a parse error.
                    return "1 = 0";
                }
                List<String> lits = new ArrayList<>();
                for (String v : f.getValues()) lits.add(literal(v));
                return col + " IN (" + String.join(", ", lits) + ")";
            }
            case "BETWEEN": {
                if (f.getValues().size() < 2) {
                    throw new IllegalArgumentException("BETWEEN filter requires two values");
                }
                return col + " BETWEEN " + literal(f.getValues().get(0)) + " AND "
                        + literal(f.getValues().get(1));
            }
            case "IS_NULL":
                return col + " IS NULL";
            case "IS_NOT_NULL":
                return col + " IS NOT NULL";
            default:
                throw new IllegalArgumentException("Unsupported filter op: " + f.getOp());
        }
    }

    /**
     * Encode a literal for the WHERE clause. Numeric-looking values pass through unquoted;
     * strings get single-quoted with any embedded quote doubled per SQL standard. NULL passes
     * through as the SQL keyword — a null on the wire becomes a plain equality with the SQL
     * literal, which is deliberately incorrect (SQL requires {@code IS NULL}); operators that
     * genuinely mean "check for null" should use {@code IS_NULL}/{@code IS_NOT_NULL} ops.
     */
    private String literal(String v) {
        if (v == null) return "NULL";
        if (v.matches("-?\\d+(\\.\\d+)?")) return v;
        return "'" + v.replace("'", "''") + "'";
    }

    private String normalizedDirection(String dir) {
        if (dir != null && dir.equalsIgnoreCase("DESC")) return "DESC";
        return "ASC";
    }

    /**
     * Quote an identifier (schema, dataset, field) with double quotes and escape any embedded
     * double quotes per SQL standard. Calcite treats double-quoted identifiers as case-sensitive
     * but our connections open with {@code caseSensitive=false} so it's fine for the identifiers
     * to be quoted in whichever case the operator declared.
     */
    private String quoteRef(String ident) {
        if (ident == null) throw new IllegalArgumentException("identifier is null");
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private String quoteAlias(String alias) {
        return quoteRef(alias);
    }
}
