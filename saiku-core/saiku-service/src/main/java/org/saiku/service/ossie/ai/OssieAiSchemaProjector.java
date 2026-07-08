/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.saiku.datasources.connection.SaikuOssieConnection;
import org.saiku.service.ossie.OssieModelDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Project an internal {@link OssieModelDto} into the agent-facing {@link OssieAiSchema} the
 * {@code GET /ai/ossie/schema/{connection}/{model}} endpoint returns.
 *
 * <p>Two roles beyond copying the tree:
 *
 * <ol>
 *   <li><b>Sample-value generation.</b> For each dimension-shaped field the projector runs
 *       {@code SELECT DISTINCT <field> FROM <dataset> LIMIT N} against the Calcite adapter
 *       so the agent sees actual values, not just field names. Results are cached in-JVM per
 *       {@code (connection, model, dataset, field)} for {@link #SAMPLE_TTL}; the H2 fuzz
 *       fixture's DIM tables have 3–4 rows so the query is cheap, but real warehouse
 *       dimensions can be million-row, so a TTL cache is load-bearing.
 *   <li><b>Metric override discovery.</b> The projector reads each metric's expression and
 *       decides which of SUM/AVG/MIN/MAX/COUNT the translator will actually rewrite; the
 *       agent gets a {@code supportedOverrides} list on every metric so it doesn't ask for
 *       {@code SUM(*)} on a {@code COUNT(*)} metric (the {@link OssieFuzzIT}-fixed edge case).
 * </ol>
 *
 * <p>PII redaction: fields with {@code pii=true} are dropped entirely from the projected
 * schema. Downstream validators treat those fields as non-existent, so an agent that saw a
 * field name from a cached prompt can't sneak it into a query. Follow-up: mask-mode
 * (rewrite the field's SELECT expression rather than hide it) — deferred to the roles
 * work in #1393.
 */
public final class OssieAiSchemaProjector {

    private static final Logger log = LoggerFactory.getLogger(OssieAiSchemaProjector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cap on the sample-value list per field. */
    static final int SAMPLE_LIMIT = 12;

    /**
     * Default TTL for the sample-value cache. Overridable via env
     * {@code SAIKU_AI_OSSIE_SAMPLES_TTL_MINUTES} or property
     * {@code saiku.ai.ossie.samples.ttl.minutes} (#1404). Real deployments will see wildly
     * different cardinality profiles — this default balances FoodMart-sized dims (sub-second
     * refresh fine) against billion-row warehouse dims (5 min too aggressive).
     */
    static final Duration SAMPLE_TTL = resolveTtl();

    private static Duration resolveTtl() {
        String raw = System.getProperty("saiku.ai.ossie.samples.ttl.minutes");
        if (raw == null || raw.isBlank()) raw = System.getenv("SAIKU_AI_OSSIE_SAMPLES_TTL_MINUTES");
        if (raw == null || raw.isBlank()) return Duration.ofMinutes(5);
        try {
            long m = Long.parseLong(raw.trim());
            if (m < 0) return Duration.ofMinutes(5);
            return Duration.ofMinutes(m);
        } catch (NumberFormatException e) {
            return Duration.ofMinutes(5);
        }
    }

    /**
     * Aggregation kinds the translator's {@code swapAggregation} will actually rewrite.
     * Kept in sync with the fuzz-hardened set in {@code OssieShelfSqlTranslator}.
     */
    private static final List<String> ALL_OVERRIDES = List.of("SUM", "AVG", "MIN", "MAX", "COUNT");

    /** Cache: (connection|model|dataset|field) → SampleEntry. */
    private final Map<String, SampleEntry> sampleCache = new ConcurrentHashMap<>();

    /**
     * Project {@code semantic} into the agent-facing schema DTO. {@code jdbc} is used to run
     * sample-value queries; pass {@code null} to skip sample generation (unit tests that don't
     * want to spin up a warehouse do this).
     */
    public OssieAiSchema project(String connectionName, OssieModelDto semantic, Connection jdbc) {
        OssieAiSchema out = new OssieAiSchema();
        out.setConnectionName(connectionName);
        out.setModelName(semantic.getName());
        out.setDescription(semantic.getDescription());
        out.setModelId(connectionName + "/" + semantic.getName());

        // ---- Datasets + fields ----
        String factCandidate = null;
        for (OssieModelDto.Dataset ds : semantic.getDatasets()) {
            OssieAiSchema.Dataset out_ds = new OssieAiSchema.Dataset();
            out_ds.setName(ds.getName());
            out_ds.setSource(ds.getSource());
            out_ds.setDescription(ds.getDescription());
            out_ds.setPrimaryKey(new ArrayList<>(ds.getPrimaryKey()));
            for (OssieModelDto.Field f : ds.getFields()) {
                if (f.isPii()) continue; // saiku#902 parity — PII fields never enter the AI view.
                OssieAiSchema.Field out_field = new OssieAiSchema.Field();
                out_field.setName(f.getName());
                out_field.setDescription(f.getDescription());
                // Type + samples come from the warehouse. Best-effort: swallow failures so
                // the schema still projects when the warehouse is offline (agent still sees
                // field names, just without samples).
                if (jdbc != null) {
                    populateSamples(connectionName, semantic.getName(), ds, f, jdbc, out_field);
                }
                out_ds.getFields().put(f.getName().toLowerCase(Locale.ROOT), out_field);
            }
            out.getDatasets().put(ds.getName().toLowerCase(Locale.ROOT), out_ds);

            // Heuristic: the fact dataset is either the one whose name starts with 'fact_' OR the
            // one every relationship's {@code from} references. Take the fact_ prefix if present,
            // otherwise fall through to relationship-derivation below.
            if (factCandidate == null && ds.getName().toLowerCase(Locale.ROOT).startsWith("fact")) {
                factCandidate = ds.getName();
            }
        }
        if (factCandidate == null && !semantic.getRelationships().isEmpty()) {
            factCandidate = semantic.getRelationships().get(0).getFrom();
        }
        out.setFactDataset(factCandidate);

        // ---- Metrics ----
        for (OssieModelDto.Metric m : semantic.getMetrics()) {
            OssieAiSchema.Metric out_m = new OssieAiSchema.Metric();
            out_m.setName(m.getName());
            out_m.setDescription(m.getDescription());
            out_m.setExpression(m.getExpression());
            out_m.setAggregationKind(deriveAggregationKind(m));
            out_m.setSupportedOverrides(deriveSupportedOverrides(m));
            out.getMetrics().put(m.getName(), out_m);
        }

        // ---- Relationships ----
        for (OssieModelDto.Relationship rel : semantic.getRelationships()) {
            OssieAiSchema.Relationship out_rel = new OssieAiSchema.Relationship();
            out_rel.setName(rel.getName());
            out_rel.setFrom(rel.getFrom());
            out_rel.setTo(rel.getTo());
            out_rel.setFromColumns(new ArrayList<>(rel.getFromColumns()));
            out_rel.setToColumns(new ArrayList<>(rel.getToColumns()));
            out.getRelationships().add(out_rel);
        }

        // ---- Request JSON Schema ----
        out.setRequestSchema(buildRequestSchema());

        // ---- Ready-made examples ----
        addExamples(out);

        return out;
    }

    /**
     * Look at a metric's expression and figure out which override targets the translator will
     * actually accept. {@code COUNT(*)} metrics can only re-target COUNT — anything else
     * produces {@code SUM(*)} etc. which the SQL parser rejects. This is the same edge case
     * {@code OssieShelfSqlTranslator.swapAggregation} guards against; publishing the list here
     * lets the agent avoid the error entirely.
     */
    static List<String> deriveSupportedOverrides(OssieModelDto.Metric m) {
        String expr = m.getExpression();
        if (expr == null || expr.isBlank()) return List.of();
        String trimmed = expr.trim();
        // COUNT(*) shape: exactly one override applies.
        if (trimmed.matches("(?i)^\\s*COUNT\\s*\\(\\s*\\*\\s*\\)\\s*$")) {
            return List.of("COUNT");
        }
        // Simple AGG(x) shape → all 5 overrides valid.
        if (trimmed.matches("(?i)^\\s*(SUM|AVG|MIN|MAX|COUNT)\\s*\\(.*\\)\\s*$")) {
            return new ArrayList<>(ALL_OVERRIDES);
        }
        // Compound / weird expression → translator refuses to rewrite; no overrides.
        return List.of();
    }

    /**
     * Best-effort aggregation-kind derivation: extract the outer function name and map it to a
     * lower-case token the agent can branch on. Falls back to the metric's declared
     * {@code aggregationKind} when the expression isn't a simple {@code AGG(...)}.
     */
    static String deriveAggregationKind(OssieModelDto.Metric m) {
        String declared = m.getAggregationKind();
        if (declared != null && !declared.isBlank()) return declared.toLowerCase(Locale.ROOT);
        String expr = m.getExpression();
        if (expr == null) return null;
        java.util.regex.Matcher x = java.util.regex.Pattern.compile("(?i)^\\s*(SUM|AVG|MIN|MAX|COUNT)\\b")
                .matcher(expr);
        if (x.find()) return x.group(1).toLowerCase(Locale.ROOT);
        return null;
    }

    /**
     * Populate {@link OssieAiSchema.Field#getSampleValues} + {@code cardinality} + {@code type}
     * from the warehouse. Uses a SQL query that quotes the source name from
     * {@link OssieModelDto.Dataset#getSource()} so it survives dialect-specific rules on case
     * sensitivity.
     */
    private void populateSamples(
            String connectionName,
            String modelName,
            OssieModelDto.Dataset ds,
            OssieModelDto.Field f,
            Connection jdbc,
            OssieAiSchema.Field out_field) {
        String cacheKey = connectionName + "|" + modelName + "|" + ds.getName() + "|" + f.getName();
        SampleEntry cached = sampleCache.get(cacheKey);
        if (cached != null && cached.freshFor(SAMPLE_TTL)) {
            out_field.setSampleValues(cached.values);
            out_field.setCardinality(cached.cardinality);
            out_field.setType(cached.type);
            out_field.setEstimatedDistinct(cached.estimatedDistinct);
            return;
        }
        // Ossie's Calcite schema exposes tables under the DATASET name — not the source table
        // name — so query against ds.getName() not ds.getSource(). Quote it so case-insensitive
        // resolution stays predictable across dialects. Fact tables happened to work with source
        // names when the underlying warehouse used the same identifier, but dim tables (e.g.
        // DIM_PAYER) never did — silently returning empty sample lists.
        String sourceTable = "\"" + ds.getName() + "\"";
        String col = f.getName();
        String sql = "SELECT DISTINCT " + col + " FROM " + sourceTable + " LIMIT " + (SAMPLE_LIMIT + 1);

        List<String> values = new ArrayList<>();
        String type = null;
        try (Statement st = jdbc.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            type = rs.getMetaData().getColumnTypeName(1);
            while (rs.next() && values.size() < SAMPLE_LIMIT) {
                Object v = rs.getObject(1);
                values.add(v == null ? null : String.valueOf(v));
            }
            // Coarse cardinality bucket from the sample query alone (LIMIT+1 heuristic).
            String cardinality;
            if (rs.next()) cardinality = "high";
            else if (values.size() <= 6) cardinality = "low";
            else cardinality = "medium";

            // #1405 refinement: when the coarse bucket says "high", ask the warehouse for a
            // better estimate via APPROX_COUNT_DISTINCT (Calcite exposes it on most dialects;
            // BigQuery/Snowflake/Postgres 9.5+/Redshift all support the identifier). Fall back
            // to COUNT DISTINCT on a 1000-row sample when the function isn't available.
            Long distinctEstimate = null;
            if ("high".equals(cardinality)) {
                distinctEstimate = tryEstimateDistinct(jdbc, sourceTable, col);
                if (distinctEstimate != null) {
                    if (distinctEstimate < 20) cardinality = "low";
                    else if (distinctEstimate < 200) cardinality = "medium";
                    else if (distinctEstimate < 2000) cardinality = "medium-high";
                    else cardinality = "high";
                }
            }
            out_field.setSampleValues(values);
            out_field.setCardinality(cardinality);
            out_field.setType(type);
            out_field.setEstimatedDistinct(distinctEstimate);
            sampleCache.put(cacheKey, new SampleEntry(Instant.now(), values, cardinality, type, distinctEstimate));
        } catch (Exception e) {
            // Common in unit tests + when a warehouse column doesn't quote cleanly. Log at debug
            // — the agent still gets the field's identity, just without samples.
            log.debug(
                    "sample-value fetch for {}.{}.{} failed ({}); returning field without samples",
                    connectionName,
                    ds.getName(),
                    f.getName(),
                    e.getMessage());
        }
    }

    /**
     * Build the JSON Schema for {@link OssieAiQueryRequest}. Deliberately hand-written rather
     * than auto-generated: the schema doubles as agent-facing documentation and we want its
     * shape to be legible, not just correct.
     */
    private ObjectNode buildRequestSchema() {
        ObjectNode s = MAPPER.createObjectNode();
        s.put("$schema", "http://json-schema.org/draft-07/schema#");
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        props.putObject("connection").put("type", "string");
        props.putObject("model").put("type", "string");

        ObjectNode fieldRef = MAPPER.createObjectNode();
        fieldRef.put("type", "object");
        ObjectNode frProps = fieldRef.putObject("properties");
        frProps.putObject("dataset").put("type", "string");
        frProps.putObject("field").put("type", "string");
        fieldRef.putArray("required").add("dataset").add("field");

        for (String axis : new String[] {"rows", "columns"}) {
            ObjectNode arr = props.putObject(axis);
            arr.put("type", "array");
            arr.set("items", fieldRef.deepCopy());
        }
        ObjectNode metricRef = MAPPER.createObjectNode();
        metricRef.put("type", "object");
        ObjectNode mrProps = metricRef.putObject("properties");
        mrProps.putObject("metric").put("type", "string");
        mrProps.putObject("aggregation").put("type", "string");
        metricRef.putArray("required").add("metric");
        ObjectNode values = props.putObject("values");
        values.put("type", "array");
        values.set("items", metricRef);

        ObjectNode filter = MAPPER.createObjectNode();
        filter.put("type", "object");
        ObjectNode filProps = filter.putObject("properties");
        filProps.putObject("dataset").put("type", "string");
        filProps.putObject("field").put("type", "string");
        filProps.putObject("op").put("type", "string");
        filProps.putObject("value").put("type", "string");
        ObjectNode valuesArr = filProps.putObject("values");
        valuesArr.put("type", "array");
        valuesArr.putObject("items").put("type", "string");
        filter.putArray("required").add("field").add("op");
        ObjectNode filters = props.putObject("filters");
        filters.put("type", "array");
        filters.set("items", filter);

        ObjectNode sort = MAPPER.createObjectNode();
        sort.put("type", "object");
        ObjectNode sortProps = sort.putObject("properties");
        sortProps.putObject("metric").put("type", "string");
        sortProps.putObject("dataset").put("type", "string");
        sortProps.putObject("field").put("type", "string");
        sortProps.putObject("direction").put("type", "string");
        ObjectNode sorts = props.putObject("sorts");
        sorts.put("type", "array");
        sorts.set("items", sort);

        props.putObject("limit").put("type", "integer");

        s.putArray("required").add("model");
        return s;
    }

    /**
     * Add ready-made example bodies: simpleGroupBy, crosstab, topN. Uses the first dataset +
     * first metric it can find so the examples aren't Pharma-specific — every model gets
     * usable examples.
     */
    private void addExamples(OssieAiSchema out) {
        // Pick a row-shelf candidate: first non-fact dataset with at least one field.
        OssieAiSchema.Dataset rowDs = null;
        for (OssieAiSchema.Dataset ds : out.getDatasets().values()) {
            if (ds.getName().equalsIgnoreCase(out.getFactDataset())) continue;
            if (!ds.getFields().isEmpty()) {
                rowDs = ds;
                break;
            }
        }
        if (rowDs == null || out.getMetrics().isEmpty()) return;
        String rowField = rowDs.getFields().values().iterator().next().getName();
        String metric = out.getMetrics().values().iterator().next().getName();

        // simpleGroupBy
        OssieAiSchema.Example simple = new OssieAiSchema.Example();
        simple.setDescription("Total " + metric + " grouped by " + rowDs.getName() + "." + rowField);
        OssieAiQueryRequest simpleBody = new OssieAiQueryRequest();
        simpleBody.setModel(out.getModelName());
        OssieAiQueryRequest.FieldRef simpleRow = new OssieAiQueryRequest.FieldRef();
        simpleRow.setDataset(rowDs.getName());
        simpleRow.setField(rowField);
        simpleBody.getRows().add(simpleRow);
        OssieAiQueryRequest.MetricRef simpleMetric = new OssieAiQueryRequest.MetricRef();
        simpleMetric.setMetric(metric);
        simpleBody.getValues().add(simpleMetric);
        simple.setBody(simpleBody);
        out.getExamples().put("simpleGroupBy", simple);

        // crosstab — pick a second dataset for columns if available.
        OssieAiSchema.Dataset colDs = null;
        for (OssieAiSchema.Dataset ds : out.getDatasets().values()) {
            if (ds == rowDs || ds.getName().equalsIgnoreCase(out.getFactDataset())) continue;
            if (!ds.getFields().isEmpty()) {
                colDs = ds;
                break;
            }
        }
        if (colDs != null) {
            String colField = colDs.getFields().values().iterator().next().getName();
            OssieAiSchema.Example cross = new OssieAiSchema.Example();
            cross.setDescription("Crosstab: " + metric + " by " + rowDs.getName() + "." + rowField + " × "
                    + colDs.getName() + "." + colField);
            OssieAiQueryRequest crossBody = new OssieAiQueryRequest();
            crossBody.setModel(out.getModelName());
            crossBody.getRows().add(fieldRef(rowDs.getName(), rowField));
            crossBody.getColumns().add(fieldRef(colDs.getName(), colField));
            crossBody.getValues().add(metricRef(metric, null));
            cross.setBody(crossBody);
            out.getExamples().put("crosstab", cross);
        }

        // topN — same shape but with a metric sort + LIMIT.
        OssieAiSchema.Example topN = new OssieAiSchema.Example();
        topN.setDescription("Top 5 " + rowDs.getName() + "." + rowField + " by " + metric);
        OssieAiQueryRequest topNBody = new OssieAiQueryRequest();
        topNBody.setModel(out.getModelName());
        topNBody.getRows().add(fieldRef(rowDs.getName(), rowField));
        topNBody.getValues().add(metricRef(metric, null));
        OssieAiQueryRequest.SortRef sortRef = new OssieAiQueryRequest.SortRef();
        sortRef.setMetric(metric);
        sortRef.setDirection("DESC");
        topNBody.getSorts().add(sortRef);
        topNBody.setLimit(5);
        topN.setBody(topNBody);
        out.getExamples().put("topN", topN);
    }

    private static OssieAiQueryRequest.FieldRef fieldRef(String dataset, String field) {
        OssieAiQueryRequest.FieldRef ref = new OssieAiQueryRequest.FieldRef();
        ref.setDataset(dataset);
        ref.setField(field);
        return ref;
    }

    private static OssieAiQueryRequest.MetricRef metricRef(String metric, String agg) {
        OssieAiQueryRequest.MetricRef ref = new OssieAiQueryRequest.MetricRef();
        ref.setMetric(metric);
        ref.setAggregation(agg);
        return ref;
    }

    /**
     * Optional convenience to unwrap the JDBC Connection from a {@link SaikuOssieConnection}. The
     * projector doesn't hold a reference itself; callers pass the connection because they own
     * its lifecycle.
     */
    public static Connection unwrap(SaikuOssieConnection connection) {
        if (connection == null) return null;
        try {
            return (Connection) connection.getConnection();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Attempt an APPROX_COUNT_DISTINCT (or COUNT(DISTINCT ...) fallback) for a field. Returns
     * null when neither works — the caller keeps the coarse bucket then.
     */
    private Long tryEstimateDistinct(Connection jdbc, String sourceTable, String col) {
        // First try APPROX_COUNT_DISTINCT — supported by BigQuery, Snowflake, Postgres 9.5+,
        // Redshift, Databricks, Presto/Trino. Not on H2, HSQLDB, SQLite.
        String approx = "SELECT APPROX_COUNT_DISTINCT(" + col + ") FROM " + sourceTable;
        try (Statement st = jdbc.createStatement();
                ResultSet rs = st.executeQuery(approx)) {
            if (rs.next()) return rs.getLong(1);
        } catch (Exception e) {
            log.debug("APPROX_COUNT_DISTINCT failed, trying COUNT DISTINCT on sample: {}", e.getMessage());
        }
        // Fallback: COUNT DISTINCT on a 1000-row sample. Cheap on H2 (the fuzz fixture); on real
        // warehouses this is bounded, so it's safe by construction.
        String countDistinct =
                "SELECT COUNT(DISTINCT " + col + ") FROM (SELECT " + col + " FROM " + sourceTable + " LIMIT 1000)";
        try (Statement st = jdbc.createStatement();
                ResultSet rs = st.executeQuery(countDistinct)) {
            if (rs.next()) return rs.getLong(1);
        } catch (Exception e) {
            log.debug("COUNT DISTINCT sample failed: {}", e.getMessage());
        }
        return null;
    }

    private record SampleEntry(
            Instant cachedAt, List<String> values, String cardinality, String type, Long estimatedDistinct) {
        boolean freshFor(Duration ttl) {
            return Duration.between(cachedAt, Instant.now()).compareTo(ttl) < 0;
        }
    }

    /** Test hook — clears the sample cache. */
    void clearSampleCache() {
        sampleCache.clear();
    }

    /**
     * Public API for {@code /schema/{c}/{m}?refresh=true} (#1404). Invalidates every cached
     * sample entry whose key starts with the {@code connectionName|modelName|} prefix so the
     * next projection re-runs the SELECT DISTINCTs.
     */
    public void invalidateSampleCache(String connectionName, String modelName) {
        String prefix = connectionName + "|" + modelName + "|";
        sampleCache.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
