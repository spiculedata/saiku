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
