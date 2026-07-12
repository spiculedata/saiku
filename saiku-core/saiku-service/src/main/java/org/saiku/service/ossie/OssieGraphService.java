/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.service.olap.OlapDiscoverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Graph traversal over an Ossie semantic model.
 *
 * <p>The Ossie query surface {@link OssieQueryService} <em>aggregates</em> — it compiles a shelf
 * state to {@code GROUP BY} SQL. This sibling <em>walks</em> the relationships the model declares.
 * Given a root node it follows the ownership relationship recursively (a DuckDB {@code WITH
 * RECURSIVE} CTE executed over the datasource's warehouse connection — the same DuckDB/Quack
 * endpoint the aggregate queries use), and returns nodes + edges with cycle detection.
 *
 * <p>The Ossie YAML spec is untouched. We read the model's declared {@code datasets} and {@code
 * relationships} to resolve the fact table, the two join columns (owned side + owner side) and the
 * node-label tables, then generate the walk. Any model whose relationships describe a
 * fact-with-two-node-edges shape (e.g. beneficial ownership) can be traversed.
 */
public class OssieGraphService {

    private static final Logger log = LoggerFactory.getLogger(OssieGraphService.class);
    private static final int MAX_DEPTH_CAP = 8;

    // Signals: entities at/above this computed risk score are the "high-risk" headline count.
    // The Benafide warehouse tops out at 30.0; >=20 isolates ~3.7k entities (the sharp tail).
    private static final double HIGH_RISK_THRESHOLD = 20.0;
    private static final int SIGNALS_FLAG_CAP = 200;
    private static final int SIGNALS_RISK_CAP = 50;
    private static final int SIGNALS_TOPIC_CAP = 12;

    private OssieDiscoverService discoverService;
    private OlapDiscoverService olapDiscoverService;

    public void setOssieDiscoverService(OssieDiscoverService s) {
        this.discoverService = s;
    }

    public void setOlapDiscoverService(OlapDiscoverService s) {
        this.olapDiscoverService = s;
    }

    /** A node in the ownership graph. {@code kind} is "entity" or "person". */
    public record Node(String id, String label, String kind) {}

    /** A directed ownership edge: {@code owner} holds {@code percentage}% of {@code owned}. */
    public record Edge(String owned, String owner, Double percentage, int depth, boolean cycle) {}

    /** Traversal result. */
    public record GraphResult(String rootId, List<Node> nodes, List<Edge> edges, int maxDepth, boolean hasCycle) {}

    /** A high-risk company for the Signals leaderboard. */
    public record RiskEntity(String id, String name, String jurisdiction, Double riskScore) {}

    /** One screening hit — {@code topics} is the split-out list of matched categories. */
    public record Flag(String name, List<String> topics, String matchType, String status) {}

    /** A screening-category tally (post-split, so multi-topic flags count once per category). */
    public record TopicCount(String topic, long count) {}

    /** Headline counts for the Signals radar. */
    public record SignalsStats(long totalFlags, long sanctionFlags, long highRiskEntities, int distinctTopics) {}

    /** The Signals bundle: headline stats + screening feed + category tally + risk leaderboard. */
    public record SignalsResult(
            SignalsStats stats, List<RiskEntity> topRisk, List<Flag> flags, List<TopicCount> topics) {}

    /**
     * Walk the ownership relationship up from {@code rootId} to {@code depth} levels.
     *
     * @param connectionName the Ossie datasource connection (e.g. {@code unknown_Benafide})
     * @param rootId the id of the company to start from
     * @param depth max levels to walk (clamped to {@value #MAX_DEPTH_CAP})
     */
    public GraphResult ownershipGraph(String connectionName, String rootId, int depth) throws Exception {
        if (rootId == null || rootId.isBlank()) {
            throw new IllegalArgumentException("rootId is required");
        }
        int d = Math.max(1, Math.min(depth, MAX_DEPTH_CAP));
        OssieModelDto model = discoverService.getModel(connectionName);
        if (model == null) {
            throw new IllegalStateException("No Ossie model for connection '" + connectionName + "'");
        }
        Resolved r = resolve(model);

        ISaikuConnection saikuConn = olapDiscoverService.getConnectionManager().getConnection(connectionName);
        if (saikuConn == null) {
            throw new IllegalStateException("No live connection registered for '" + connectionName + "'");
        }
        Properties p = saikuConn.getProperties();
        String url = p.getProperty(ISaikuConnection.URL_KEY);
        String user = p.getProperty(ISaikuConnection.USERNAME_KEY);
        String pass = p.getProperty(ISaikuConnection.PASSWORD_KEY);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Datasource '" + connectionName + "' has no warehouse URL (location)");
        }

        String walkSql = "WITH RECURSIVE walk(depth, owned_id, owner_id, pct, path, cyc) AS ("
                + "  SELECT 0, f.\"" + r.subjectCol + "\", f.\"" + r.partyCol + "\", " + r.pctExpr + ","
                + "         [f.\"" + r.subjectCol + "\", f.\"" + r.partyCol + "\"], false"
                + "  FROM \"" + r.factTable + "\" f WHERE f.\"" + r.subjectCol + "\" = ?"
                + "  UNION ALL"
                + "  SELECT w.depth+1, f.\"" + r.subjectCol + "\", f.\"" + r.partyCol + "\", " + r.pctExpr + ","
                + "         list_append(w.path, f.\"" + r.partyCol + "\"),"
                + "         list_contains(w.path, f.\"" + r.partyCol + "\")"
                + "  FROM \"" + r.factTable + "\" f JOIN walk w ON f.\"" + r.subjectCol + "\" = w.owner_id"
                + "  WHERE w.depth < ? AND NOT w.cyc"
                + ") SELECT depth, owned_id, owner_id, pct, cyc FROM walk ORDER BY depth";

        List<Edge> edges = new ArrayList<>();
        Map<String, Node> nodes = new LinkedHashMap<>();
        int maxDepth = 0;
        boolean hasCycle = false;
        long start = System.currentTimeMillis();

        try (Connection c = DriverManager.getConnection(url, user == null ? "" : user, pass == null ? "" : pass)) {
            try (PreparedStatement ps = c.prepareStatement(walkSql)) {
                ps.setString(1, rootId);
                ps.setInt(2, d);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int depthVal = rs.getInt(1);
                        String owned = rs.getString(2);
                        String owner = rs.getString(3);
                        Object pctObj = rs.getObject(4);
                        Double pct = pctObj instanceof Number ? ((Number) pctObj).doubleValue() : null;
                        boolean cyc = rs.getBoolean(5);
                        edges.add(new Edge(owned, owner, pct, depthVal, cyc));
                        maxDepth = Math.max(maxDepth, depthVal);
                        if (cyc) hasCycle = true;
                        nodes.putIfAbsent(owned, null);
                        nodes.putIfAbsent(owner, null);
                    }
                }
            }
            labelNodes(c, r, nodes);
        }
        log.info(
                "Ossie graph walk (connection='{}', root='{}', depth={}): {} edges, {} nodes in {}ms",
                connectionName,
                rootId,
                d,
                edges.size(),
                nodes.size(),
                System.currentTimeMillis() - start);
        return new GraphResult(rootId, new ArrayList<>(nodes.values()), edges, maxDepth, hasCycle);
    }

    /**
     * Search entities by name. Uses the warehouse's full-text index ({@code fts_main_<table>}) for
     * relevance-ranked results when present; falls back to a case-insensitive substring match.
     */
    public List<Map<String, Object>> searchEntities(String connectionName, String q, int limit) throws Exception {
        if (q == null || q.isBlank()) return List.of();
        OssieModelDto model = discoverService.getModel(connectionName);
        if (model == null) throw new IllegalStateException("No Ossie model for '" + connectionName + "'");
        OssieModelDto.Dataset entity = datasetWithField(model, "jurisdiction", "entity");
        String table = entity.getSource();
        String idCol =
                entity.getPrimaryKey().isEmpty() ? "id" : entity.getPrimaryKey().get(0);
        int lim = Math.max(1, Math.min(limit, 25));

        String cols =
                "\"" + idCol + "\" AS id, \"name\" AS name, \"jurisdiction\" AS jurisdiction, \"status\" AS status";
        String ftsSql = "SELECT " + cols + " FROM (SELECT *, fts_main_" + table + ".match_bm25(\"" + idCol
                + "\", ?) AS _score FROM \"" + table + "\") WHERE _score IS NOT NULL ORDER BY _score DESC LIMIT " + lim;
        String likeSql = "SELECT " + cols + " FROM \"" + table + "\" WHERE \"name\" ILIKE ? LIMIT " + lim;

        ISaikuConnection saikuConn = olapDiscoverService.getConnectionManager().getConnection(connectionName);
        if (saikuConn == null) throw new IllegalStateException("No live connection for '" + connectionName + "'");
        Properties p = saikuConn.getProperties();
        try (Connection c = DriverManager.getConnection(
                p.getProperty(ISaikuConnection.URL_KEY),
                orEmpty(p.getProperty(ISaikuConnection.USERNAME_KEY)),
                orEmpty(p.getProperty(ISaikuConnection.PASSWORD_KEY)))) {
            try {
                return runSearch(c, ftsSql, q);
            } catch (Exception ftsMiss) {
                log.info("FTS search unavailable on '{}', falling back to substring: {}", table, ftsMiss.getMessage());
                return runSearch(c, likeSql, "%" + q + "%");
            }
        }
    }

    private List<Map<String, Object>> runSearch(Connection c, String sql, String param) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** Per-entity profile: attributes + risk + opacity, resolved from the model, one query. */
    public Map<String, Object> entityProfile(String connectionName, String id) throws Exception {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        OssieModelDto model = discoverService.getModel(connectionName);
        if (model == null) throw new IllegalStateException("No Ossie model for '" + connectionName + "'");
        OssieModelDto.Dataset entity = datasetWithField(model, "jurisdiction", "entity");
        OssieModelDto.Dataset riskDs = datasetWithFieldOrNull(model, "risk_score");
        OssieModelDto.Dataset featDs = datasetWithFieldOrNull(model, "opacity_score");
        String entityTable = entity.getSource();
        String entityId =
                entity.getPrimaryKey().isEmpty() ? "id" : entity.getPrimaryKey().get(0);

        StringBuilder sql = new StringBuilder("SELECT e.\"" + entityId + "\" AS id, e.\"name\" AS name, "
                + "e.\"jurisdiction\" AS jurisdiction, e.\"status\" AS status");
        if (riskDs != null) sql.append(", r.\"risk_score\" AS risk_score");
        if (featDs != null) sql.append(", f.\"opacity_score\" AS opacity_score");
        sql.append(" FROM \"").append(entityTable).append("\" e");
        if (riskDs != null) {
            sql.append(" LEFT JOIN \"")
                    .append(riskDs.getSource())
                    .append("\" r ON r.\"")
                    .append(
                            riskDs.getPrimaryKey().isEmpty()
                                    ? "entity_id"
                                    : riskDs.getPrimaryKey().get(0))
                    .append("\" = e.\"")
                    .append(entityId)
                    .append("\"");
        }
        if (featDs != null) {
            sql.append(" LEFT JOIN \"")
                    .append(featDs.getSource())
                    .append("\" f ON f.\"")
                    .append(
                            featDs.getPrimaryKey().isEmpty()
                                    ? "entity_id"
                                    : featDs.getPrimaryKey().get(0))
                    .append("\" = e.\"")
                    .append(entityId)
                    .append("\"");
        }
        sql.append(" WHERE e.\"").append(entityId).append("\" = ? LIMIT 1");

        ISaikuConnection saikuConn = olapDiscoverService.getConnectionManager().getConnection(connectionName);
        if (saikuConn == null) throw new IllegalStateException("No live connection for '" + connectionName + "'");
        Properties p = saikuConn.getProperties();
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(
                        p.getProperty(ISaikuConnection.URL_KEY),
                        orEmpty(p.getProperty(ISaikuConnection.USERNAME_KEY)),
                        orEmpty(p.getProperty(ISaikuConnection.PASSWORD_KEY)));
                PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    java.sql.ResultSetMetaData md = rs.getMetaData();
                    for (int i = 1; i <= md.getColumnCount(); i++) out.put(md.getColumnLabel(i), rs.getObject(i));
                }
            }
        }
        return out;
    }

    /**
     * The Signals radar bundle: headline screening/risk stats, the sanctions-first screening feed,
     * a matched-category tally, and the high-risk company leaderboard. One warehouse round-trip of
     * four read-only SELECTs, all resolved from the model's declared datasets (the {@code entity} +
     * {@code risk_score} datasets for the leaderboard, the {@code risk_topics} dataset for the feed).
     */
    public SignalsResult signals(String connectionName, int flagLimit, int riskLimit) throws Exception {
        OssieModelDto model = discoverService.getModel(connectionName);
        if (model == null) throw new IllegalStateException("No Ossie model for '" + connectionName + "'");
        OssieModelDto.Dataset entity = datasetWithField(model, "jurisdiction", "entity");
        OssieModelDto.Dataset riskDs = datasetWithFieldOrNull(model, "risk_score");
        OssieModelDto.Dataset flagDs = datasetWithFieldOrNull(model, "risk_topics");
        if (riskDs == null || flagDs == null) {
            throw new IllegalStateException("Model has no risk_score / risk_topics datasets — Signals unavailable");
        }
        String entityTable = entity.getSource();
        String entityId =
                entity.getPrimaryKey().isEmpty() ? "id" : entity.getPrimaryKey().get(0);
        String riskTable = riskDs.getSource();
        String riskFk = riskDs.getPrimaryKey().isEmpty()
                ? "entity_id"
                : riskDs.getPrimaryKey().get(0);
        String flagTable = flagDs.getSource();
        int fLim = Math.max(1, Math.min(flagLimit, SIGNALS_FLAG_CAP));
        int rLim = Math.max(1, Math.min(riskLimit, SIGNALS_RISK_CAP));

        // Secondary sort on id is load-bearing: hundreds of entities tie at the top risk score
        // (134 at exactly 30.0), so risk_score alone leaves LIMIT free to return a different
        // arbitrary subset each run. Ordering by id after the score makes the leaderboard stable.
        String topRiskSql = "SELECT e.\"" + entityId + "\" AS id, e.\"name\" AS name, "
                + "e.\"jurisdiction\" AS jurisdiction, r.\"risk_score\" AS risk_score "
                + "FROM \"" + entityTable + "\" e JOIN \"" + riskTable + "\" r ON r.\"" + riskFk + "\" = e.\""
                + entityId
                + "\" WHERE r.\"risk_score\" > 0 ORDER BY r.\"risk_score\" DESC, e.\"" + entityId + "\" LIMIT " + rLim;

        String flagsSql = "SELECT \"os_name\" AS name, \"risk_topics\" AS topics, "
                + "\"match_type\" AS match_type, \"status\" AS status FROM \"" + flagTable + "\" "
                + "ORDER BY (CASE WHEN \"risk_topics\" ILIKE '%sanction%' THEN 0 ELSE 1 END), \"os_name\", \"id\" LIMIT "
                + fLim;

        String topicsSql = "SELECT trim(t) AS topic, count(*) AS c FROM \"" + flagTable
                + "\", unnest(string_split(\"risk_topics\", ';')) AS u(t) "
                + "WHERE trim(t) <> '' GROUP BY 1 ORDER BY 2 DESC LIMIT " + SIGNALS_TOPIC_CAP;

        String statsSql = "SELECT (SELECT count(*) FROM \"" + flagTable + "\") AS total_flags, "
                + "(SELECT count(*) FROM \"" + flagTable
                + "\" WHERE \"risk_topics\" ILIKE '%sanction%') AS sanction_flags, "
                + "(SELECT count(*) FROM \"" + riskTable + "\" WHERE \"risk_score\" >= " + HIGH_RISK_THRESHOLD
                + ") AS high_risk";

        ISaikuConnection saikuConn = olapDiscoverService.getConnectionManager().getConnection(connectionName);
        if (saikuConn == null) throw new IllegalStateException("No live connection for '" + connectionName + "'");
        Properties p = saikuConn.getProperties();
        long start = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection(
                p.getProperty(ISaikuConnection.URL_KEY),
                orEmpty(p.getProperty(ISaikuConnection.USERNAME_KEY)),
                orEmpty(p.getProperty(ISaikuConnection.PASSWORD_KEY)))) {
            List<RiskEntity> topRisk = new ArrayList<>();
            for (Map<String, Object> row : query(c, topRiskSql)) {
                Object score = row.get("risk_score");
                topRisk.add(new RiskEntity(
                        str(row.get("id")),
                        str(row.get("name")),
                        str(row.get("jurisdiction")),
                        score instanceof Number ? ((Number) score).doubleValue() : null));
            }
            List<Flag> flags = new ArrayList<>();
            for (Map<String, Object> row : query(c, flagsSql)) {
                flags.add(new Flag(
                        str(row.get("name")),
                        splitTopics(str(row.get("topics"))),
                        str(row.get("match_type")),
                        str(row.get("status"))));
            }
            List<TopicCount> topics = new ArrayList<>();
            for (Map<String, Object> row : query(c, topicsSql)) {
                Object cnt = row.get("c");
                topics.add(
                        new TopicCount(str(row.get("topic")), cnt instanceof Number ? ((Number) cnt).longValue() : 0));
            }
            SignalsStats stats = new SignalsStats(0, 0, 0, topics.size());
            List<Map<String, Object>> statRows = query(c, statsSql);
            if (!statRows.isEmpty()) {
                Map<String, Object> s = statRows.get(0);
                stats = new SignalsStats(
                        asLong(s.get("total_flags")),
                        asLong(s.get("sanction_flags")),
                        asLong(s.get("high_risk")),
                        topics.size());
            }
            log.info(
                    "Ossie signals (connection='{}'): {} flags, {} topics, {} top-risk in {}ms",
                    connectionName,
                    flags.size(),
                    topics.size(),
                    topRisk.size(),
                    System.currentTimeMillis() - start);
            return new SignalsResult(stats, topRisk, flags, topics);
        }
    }

    /** Run a parameterless SELECT and collect its rows as ordered maps. */
    private List<Map<String, Object>> query(Connection c, String sql) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                out.add(row);
            }
        }
        return out;
    }

    /** Split a ';'-delimited risk_topics string into trimmed, non-empty categories. */
    private static List<String> splitTopics(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : raw.split(";")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long asLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private OssieModelDto.Dataset datasetWithField(OssieModelDto model, String field, String fallbackName) {
        OssieModelDto.Dataset ds = datasetWithFieldOrNull(model, field);
        return ds != null ? ds : dataset(model, fallbackName);
    }

    private OssieModelDto.Dataset datasetWithFieldOrNull(OssieModelDto model, String field) {
        for (OssieModelDto.Dataset ds : model.getDatasets()) {
            for (OssieModelDto.Field f : ds.getFields()) {
                if (field.equalsIgnoreCase(f.getName())) return ds;
            }
        }
        return null;
    }

    /** Resolve node ids to labels: each id is an entity or a person. */
    private void labelNodes(Connection c, Resolved r, Map<String, Node> nodes) throws Exception {
        if (nodes.isEmpty()) return;
        List<String> ids = new ArrayList<>(nodes.keySet());
        String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
        // entities first, then persons — a person id never collides with an entity id here.
        resolveLabels(
                c,
                "SELECT \"" + r.entityIdCol + "\" AS id, \"" + r.entityNameCol + "\" AS nm FROM \"" + r.entityTable
                        + "\" WHERE \"" + r.entityIdCol + "\" IN (" + placeholders + ")",
                ids,
                nodes,
                "entity");
        resolveLabels(
                c,
                "SELECT \"" + r.personIdCol + "\" AS id, \"" + r.personNameCol + "\" AS nm FROM \"" + r.personTable
                        + "\" WHERE \"" + r.personIdCol + "\" IN (" + placeholders + ")",
                ids,
                nodes,
                "person");
        // any id still unlabelled → keep the raw id as its own label.
        for (Map.Entry<String, Node> e : nodes.entrySet()) {
            if (e.getValue() == null) e.setValue(new Node(e.getKey(), e.getKey(), "unknown"));
        }
    }

    private void resolveLabels(Connection c, String sql, List<String> ids, Map<String, Node> nodes, String kind)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) ps.setString(i + 1, ids.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    String nm = rs.getString(2);
                    // don't overwrite an entity label with a person one
                    if (nodes.get(id) == null) nodes.put(id, new Node(id, nm == null ? id : nm, kind));
                }
            }
        }
    }

    /** Table/column names resolved from the model's declared datasets + relationships. */
    private static final class Resolved {
        String factTable, subjectCol, partyCol, pctExpr;
        String entityTable, entityIdCol, entityNameCol;
        String personTable, personIdCol, personNameCol;
    }

    /**
     * Identify the fact dataset (declared as {@code from} of ≥2 relationships and never a {@code
     * to}) and, from its two relationships, the owned-side column (→ the entity dataset) and the
     * owner-side column (→ the person dataset). Node label tables come from those two datasets.
     */
    private Resolved resolve(OssieModelDto model) {
        List<OssieModelDto.Relationship> rels = model.getRelationships();
        // datasets that are ever a relationship target
        java.util.Set<String> targets = new java.util.HashSet<>();
        Map<String, Integer> fromCounts = new java.util.HashMap<>();
        for (OssieModelDto.Relationship rel : rels) {
            targets.add(rel.getTo());
            fromCounts.merge(rel.getFrom(), 1, Integer::sum);
        }
        String factName = null;
        for (Map.Entry<String, Integer> e : fromCounts.entrySet()) {
            if (e.getValue() >= 2 && !targets.contains(e.getKey())) {
                factName = e.getKey();
                break;
            }
        }
        if (factName == null) {
            throw new IllegalStateException(
                    "Model has no traversable fact (a dataset that is 'from' of >=2 relationships and never a 'to')");
        }
        // the two edges out of the fact
        OssieModelDto.Relationship toEntity = null, toPerson = null;
        for (OssieModelDto.Relationship rel : rels) {
            if (!factName.equals(rel.getFrom())) continue;
            if (toEntity == null) toEntity = rel;
            else toPerson = rel;
        }
        if (toEntity == null || toPerson == null) {
            throw new IllegalStateException("Fact '" + factName + "' does not have two node relationships");
        }
        Resolved r = new Resolved();
        r.factTable = source(model, factName);
        r.subjectCol = toEntity.getFromColumns().get(0);
        r.partyCol = toPerson.getFromColumns().get(0);
        r.pctExpr = pctColumn(model, factName);
        r.entityTable = source(model, toEntity.getTo());
        r.entityIdCol = toEntity.getToColumns().get(0);
        r.entityNameCol = nameField(model, toEntity.getTo());
        r.personTable = source(model, toPerson.getTo());
        r.personIdCol = toPerson.getToColumns().get(0);
        r.personNameCol = nameField(model, toPerson.getTo());
        return r;
    }

    private OssieModelDto.Dataset dataset(OssieModelDto model, String name) {
        for (OssieModelDto.Dataset ds : model.getDatasets()) {
            if (name.equals(ds.getName())) return ds;
        }
        throw new IllegalStateException("Model has no dataset '" + name + "'");
    }

    private String source(OssieModelDto model, String datasetName) {
        return dataset(model, datasetName).getSource();
    }

    /** A field literally named {@code percentage}, else the SQL literal NULL. */
    private String pctColumn(OssieModelDto model, String datasetName) {
        for (OssieModelDto.Field f : dataset(model, datasetName).getFields()) {
            if ("percentage".equalsIgnoreCase(f.getName())) return "f.\"percentage\"";
        }
        return "CAST(NULL AS DOUBLE)";
    }

    /** A field named {@code name}, else the dataset's primary-key column. */
    private String nameField(OssieModelDto model, String datasetName) {
        OssieModelDto.Dataset ds = dataset(model, datasetName);
        for (OssieModelDto.Field f : ds.getFields()) {
            if ("name".equalsIgnoreCase(f.getName())) return "name";
        }
        return ds.getPrimaryKey().isEmpty() ? "name" : ds.getPrimaryKey().get(0);
    }
}
