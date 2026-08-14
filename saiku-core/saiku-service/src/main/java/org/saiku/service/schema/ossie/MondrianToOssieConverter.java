/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.ossie;

import bi.saiku.ossie.model.AiContext;
import bi.saiku.ossie.model.CustomExtension;
import bi.saiku.ossie.model.Dataset;
import bi.saiku.ossie.model.DimensionMeta;
import bi.saiku.ossie.model.Expression;
import bi.saiku.ossie.model.Field;
import bi.saiku.ossie.model.Metric;
import bi.saiku.ossie.model.OssieDocument;
import bi.saiku.ossie.model.Relationship;
import bi.saiku.ossie.model.SemanticModel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.xml.parsers.ParserConfigurationException;
import org.saiku.service.util.xml.SecureXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Convert a Mondrian XML schema into an {@link OssieDocument} following the mapping documented on
 * saiku#1384.
 *
 * <p>Cube → {@code SemanticModel}. Fact table → {@code dataset}. Each dimension table (via
 * hierarchy {@code <Table/>}) → its own {@code dataset}. Foreign key from cube's {@code
 * <Dimension foreignKey=…>} + hierarchy's {@code primaryKey=…} → {@code relationship}. Each
 * {@code <Measure aggregator=… column=…/>} → {@code metric} with both an ANSI_SQL dialect
 * expression AND an MDX dialect expression (verbatim {@code [Measures].[Name]}). {@code
 * <CalculatedMember>} → metric with MDX dialect only (no reliable ANSI_SQL translation).
 *
 * <p>Saiku semantic annotations propagate: {@code saiku.semantic.description} and {@code
 * saiku.semantic.synonyms} lift into the Ossie {@link AiContext} directly (Ossie has first-class
 * homes for both); {@code saiku.semantic.pii} + {@code cardinality} + {@code grain} + {@code
 * aggregation_kind} + {@code required_filters} ride under {@code custom_extensions} with {@code
 * vendor_name="SAIKU"} because Ossie has no first-class field for them yet.
 *
 * <p>Deliberately not supported in this iteration (issues to file separately):
 *
 * <ul>
 *   <li>Mondrian 4 {@code <MeasureGroup>} with {@code <NoLink>}. <b>This is the shape every MDX
 *       schema Saiku ships actually uses</b> — FoodMart4, Bank and Pharma all declare
 *       MeasureGroups — so in practice this converter maps none of them and
 *       {@code saiku ossie-export} returns an empty model for all three. (An earlier version of
 *       this list claimed "the classic-3 shape covers every schema we ship"; it never did. See
 *       saiku#1808 for how that mistake stayed invisible.) Tracked separately for a v2.
 *   <li>Virtual cubes.
 *   <li>Parent-child hierarchies.
 *   <li>Shared dimensions consumed via {@code <DimensionUsage>} (uses {@code source=…}).
 * </ul>
 *
 * <p>The converter is stateless once built; a single instance can serve many schemas.
 */
public final class MondrianToOssieConverter {

    /** Namespace prefix for saiku.semantic.* annotations. Kept local to avoid a service dep. */
    private static final String SEMANTIC_PREFIX = "saiku.semantic.";

    private static final String SEMANTIC_DESCRIPTION = SEMANTIC_PREFIX + "description";
    private static final String SEMANTIC_SYNONYMS = SEMANTIC_PREFIX + "synonyms";

    private final ObjectMapper json = new ObjectMapper();

    public OssieDocument convert(InputStream mondrianXml)
            throws IOException, SAXException, ParserConfigurationException {
        Document doc = SecureXml.secureDocumentBuilder().parse(Objects.requireNonNull(mondrianXml, "mondrianXml"));
        skippedCubes.clear();
        return convert(doc.getDocumentElement());
    }

    public OssieDocument convert(Element schemaEl) {
        OssieDocument out = new OssieDocument();
        Element physicalSchema = firstChild(schemaEl, "PhysicalSchema");
        for (Element cube : childrenNamed(schemaEl, "Cube")) {
            SemanticModel sm = convertCube(cube, physicalSchema);
            // Ossie's core schema requires `datasets` to have at least one entry. Cubes that
            // yield zero datasets are almost always Mondrian 4 <MeasureGroup> shape or virtual
            // cubes — neither of which this first-cut converter recognises. Emit them into
            // {@link #skippedCubes} for the CLI to report rather than failing the whole document.
            if (sm.getDatasets().isEmpty()) {
                skippedCubes.add(sm.getName());
                continue;
            }
            out.getSemanticModel().add(sm);
        }
        return out;
    }

    /** Cube names skipped during the last conversion. Reset on each call to {@link #convert}. */
    public java.util.List<String> getSkippedCubes() {
        return skippedCubes;
    }

    private final java.util.List<String> skippedCubes = new ArrayList<>();

    /* ---------------- cube ---------------- */

    private SemanticModel convertCube(Element cube, Element physicalSchema) {
        // saiku#1813: Mondrian 4 puts measures in a <MeasureGroup> and dimensions
        // under <Dimensions>/<Attributes>. It is the shape EVERY schema Saiku
        // ships uses, so this is the common path, not the exotic one.
        if (firstChild(cube, "MeasureGroups") != null) {
            return convertCubeM4(cube, physicalSchema);
        }
        return convertCubeClassic3(cube);
    }

    /* ---------------- Mondrian 4 (saiku#1813) ---------------- */

    /**
     * Convert a Mondrian 4 cube.
     *
     * <p>Shape differences that matter, all of them structural rather than cosmetic:
     *
     * <ul>
     *   <li>The fact table comes from {@code <MeasureGroup table=…>}, not a {@code <Table>} child
     *       of the cube.
     *   <li>A dimension names its table on itself ({@code <Dimension table= key=…>}) and its
     *       columns as {@code <Attribute keyColumn= nameColumn=…>}; the {@code <Hierarchy>} then
     *       ORDERS those attributes by reference. So fields come from attributes, not levels.
     *   <li>Joins are declared per measure-group as {@code <ForeignKeyLink dimension=
     *       foreignKeyColumn=…>}, and the dimension side of the key comes from
     *       {@code <PhysicalSchema><Table><Key>}.
     *   <li>{@code <NoLink dimension=…>} explicitly declares NO join — it must not produce a
     *       relationship (see CLAUDE.md on virtual cubes).
     * </ul>
     */
    private SemanticModel convertCubeM4(Element cube, Element physicalSchema) {
        SemanticModel sm = new SemanticModel();
        sm.setName(attr(cube, "name"));
        sm.setDescription(nullIfBlank(attr(cube, "description")));
        applyAnnotationsToAiContext(cube, sm::setAiContext);
        applyAnnotationsToExtensions(cube, sm.getCustomExtensions());

        Map<String, List<String>> tableKeys = readPhysicalKeys(physicalSchema);

        // Dimension datasets first, keyed by DIMENSION name so the links below resolve.
        Map<String, Dataset> byDimension = new LinkedHashMap<>();
        Element dims = firstChild(cube, "Dimensions");
        if (dims != null) {
            for (Element dim : childrenNamed(dims, "Dimension")) {
                Dataset ds = convertM4Dimension(dim, tableKeys);
                if (ds == null) continue;
                byDimension.put(attr(dim, "name"), ds);
                sm.getDatasets().add(ds);
            }
        }

        // Each measure group contributes a fact dataset, its metrics, and its links.
        Element groups = firstChild(cube, "MeasureGroups");
        Dataset firstFact = null;
        if (groups != null) {
            for (Element mg : childrenNamed(groups, "MeasureGroup")) {
                String table = attr(mg, "table");
                if (table == null || table.isBlank()) continue;
                Dataset fact = new Dataset();
                fact.setName(sanitiseName(table));
                fact.setSource(qualifyTable(null, table));
                fact.setDescription("Fact table for measure group '" + attr(mg, "name") + "'.");
                for (String k : tableKeys.getOrDefault(table, List.of())) {
                    fact.getPrimaryKey().add(k);
                }
                sm.getDatasets().add(fact);
                if (firstFact == null) firstFact = fact;

                Element measures = firstChild(mg, "Measures");
                if (measures != null) {
                    for (Element measure : childrenNamed(measures, "Measure")) {
                        sm.getMetrics().add(convertMeasure(sm.getName(), fact, measure));
                    }
                }

                Element links = firstChild(mg, "DimensionLinks");
                if (links != null) {
                    for (Element link : childrenNamed(links, "ForeignKeyLink")) {
                        Dataset dim = byDimension.get(attr(link, "dimension"));
                        String fk = attr(link, "foreignKeyColumn");
                        if (dim == null
                                || fk == null
                                || fk.isBlank()
                                || dim.getPrimaryKey().isEmpty()) {
                            continue;
                        }
                        Relationship rel = new Relationship();
                        rel.setName(sanitiseName(fact.getName() + "_to_" + dim.getName()));
                        rel.setFrom(fact.getName());
                        rel.setTo(dim.getName());
                        rel.getFromColumns().add(fk);
                        rel.getToColumns().add(dim.getPrimaryKey().get(0));
                        sm.getRelationships().add(rel);
                    }
                    // <NoLink> is deliberately not iterated: it declares the ABSENCE of a join.
                }
            }
        }

        for (Element calc : childrenNamed(cube, "CalculatedMember")) {
            sm.getMetrics().add(convertCalculatedMember(sm.getName(), calc));
        }
        Element calcs = firstChild(cube, "CalculatedMembers");
        if (calcs != null) {
            for (Element calc : childrenNamed(calcs, "CalculatedMember")) {
                sm.getMetrics().add(convertCalculatedMember(sm.getName(), calc));
            }
        }
        return sm;
    }

    /** {@code <PhysicalSchema><Table name=…><Key><Column name=…/></Key></Table>} → table → key columns. */
    private Map<String, List<String>> readPhysicalKeys(Element physicalSchema) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (physicalSchema == null) return out;
        for (Element t : childrenNamed(physicalSchema, "Table")) {
            Element key = firstChild(t, "Key");
            if (key == null) continue;
            List<String> cols = new ArrayList<>();
            for (Element c : childrenNamed(key, "Column")) {
                String n = attr(c, "name");
                if (n != null && !n.isBlank()) cols.add(n);
            }
            if (!cols.isEmpty()) out.put(attr(t, "name"), cols);
        }
        return out;
    }

    /**
     * One M4 dimension → one dataset.
     *
     * <p>Fields come from {@code <Attribute>}, but ANNOTATIONS live on the {@code <Level>} that
     * references an attribute — which is where a schema author naturally writes
     * {@code saiku.semantic.pii}. Carrying them across is the whole point of #1496 on this shape:
     * read only the attributes and the PII flags vanish silently.
     */
    private Dataset convertM4Dimension(Element dim, Map<String, List<String>> tableKeys) {
        String table = attr(dim, "table");
        if (table == null || table.isBlank()) return null;
        Dataset ds = new Dataset();
        ds.setName(sanitiseName(table));
        ds.setSource(qualifyTable(null, table));
        for (String k : tableKeys.getOrDefault(table, List.of())) {
            ds.getPrimaryKey().add(k);
        }
        applyAnnotationsToAiContext(dim, ds::setAiContext);
        applyAnnotationsToExtensions(dim, ds.getCustomExtensions());

        // Level element per attribute NAME, so annotations can be lifted onto the field.
        Map<String, Element> levelByAttribute = new LinkedHashMap<>();
        Element hiers = firstChild(dim, "Hierarchies");
        if (hiers != null) {
            for (Element h : childrenNamed(hiers, "Hierarchy")) {
                for (Element lvl : childrenNamed(h, "Level")) {
                    String ref = attr(lvl, "attribute");
                    if (ref != null && !levelByAttribute.containsKey(ref)) levelByAttribute.put(ref, lvl);
                }
            }
        }

        Element attrs = firstChild(dim, "Attributes");
        if (attrs != null) {
            for (Element a : childrenNamed(attrs, "Attribute")) {
                Field f = new Field();
                String name = attr(a, "name");
                f.setName(name);
                // An attribute names its columns EITHER as attributes (keyColumn / nameColumn)
                // OR as <Key>/<Name> child elements — FoodMart4 uses the latter throughout, and
                // its <Key> is frequently COMPOUND ("Store City" keys on store_state +
                // store_city). Ossie's schema requires an expression on every field, so an
                // attribute whose column can't be resolved would emit an invalid document; the
                // display column is the honest single-column choice, falling back to the last
                // key column (the leaf of a compound key is the one that identifies the member).
                String column = firstNonBlank(
                        attr(a, "nameColumn"),
                        firstColumnOf(firstChild(a, "Name")),
                        attr(a, "keyColumn"),
                        lastColumnOf(firstChild(a, "Key")));
                if (column == null) {
                    // Nothing to express it with — skip rather than emit a schema-invalid field.
                    continue;
                }
                f.setExpression(Expression.ansi(column));
                String levelType = attr(a, "levelType");
                if (levelType != null && levelType.startsWith("Time")) {
                    f.setDimension(new DimensionMeta(true));
                }
                applyAnnotationsToAiContext(a, f::setAiContext);
                applyAnnotationsToExtensions(a, f.getCustomExtensions());
                Element lvl = levelByAttribute.get(name);
                if (lvl != null) {
                    applyAnnotationsToAiContext(lvl, f::setAiContext);
                    applyAnnotationsToExtensions(lvl, f.getCustomExtensions());
                }
                ds.getFields().add(f);
            }
        }
        return ds;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }

    /** First {@code <Column name=…/>} under {@code parent}, or null. */
    private String firstColumnOf(Element parent) {
        if (parent == null) return null;
        for (Element c : childrenNamed(parent, "Column")) {
            String n = attr(c, "name");
            if (n != null && !n.isBlank()) return n;
        }
        return null;
    }

    /** LAST {@code <Column>} under {@code parent} — for a compound key, the leaf column is the
     *  one that actually identifies the member ({@code store_state, store_city} → store_city). */
    private String lastColumnOf(Element parent) {
        if (parent == null) return null;
        String last = null;
        for (Element c : childrenNamed(parent, "Column")) {
            String n = attr(c, "name");
            if (n != null && !n.isBlank()) last = n;
        }
        return last;
    }

    /* ---------------- classic Mondrian 3 ---------------- */

    private SemanticModel convertCubeClassic3(Element cube) {
        SemanticModel sm = new SemanticModel();
        sm.setName(attr(cube, "name"));
        sm.setDescription(nullIfBlank(attr(cube, "description")));
        applyAnnotationsToAiContext(cube, sm::setAiContext);
        applyAnnotationsToExtensions(cube, sm.getCustomExtensions());

        // Fact table — child <Table name="..." schema="..."/> is the classic Mondrian 3-4 shape.
        // If missing (e.g. cube references a view via <View>), we skip building the fact dataset
        // and log a comment via description so the operator can spot the gap. Not fatal.
        Element factTable = firstChild(cube, "Table");
        Dataset factDataset = null;
        if (factTable != null) {
            factDataset = new Dataset();
            factDataset.setName(sanitiseName(attr(factTable, "name")));
            factDataset.setSource(qualifyTable(attr(factTable, "schema"), attr(factTable, "name")));
            factDataset.setDescription("Fact table for cube '" + sm.getName() + "'.");
            sm.getDatasets().add(factDataset);
        }

        // Each <Dimension> under the cube → its own dim dataset + a relationship back to the fact.
        for (Element dim : childrenNamed(cube, "Dimension")) {
            convertCubeDimension(sm, factDataset, dim);
        }

        // Measures — one metric each. Both ANSI_SQL and MDX dialects on every entry.
        for (Element measure : childrenNamed(cube, "Measure")) {
            sm.getMetrics().add(convertMeasure(sm.getName(), factDataset, measure));
        }
        // Calculated members — MDX-only.
        for (Element calc : childrenNamed(cube, "CalculatedMember")) {
            sm.getMetrics().add(convertCalculatedMember(sm.getName(), calc));
        }

        return sm;
    }

    /* ---------------- dimensions ---------------- */

    private void convertCubeDimension(SemanticModel sm, Dataset factDataset, Element dim) {
        String dimName = attr(dim, "name");
        String foreignKey = attr(dim, "foreignKey");
        // Only handle the classic single-hierarchy shape here — spec-covered dims have exactly one
        // <Hierarchy/> child. Multi-hierarchy dims (a Time dim with Weekly and Monthly for
        // instance) get one dataset per hierarchy because each hierarchy has its own Table root.
        for (Element hier : childrenNamed(dim, "Hierarchy")) {
            Element hierTable = firstChild(hier, "Table");
            if (hierTable == null) {
                // Degenerate dim — resolves against the fact table. Levels attach as fields on
                // the fact dataset directly rather than getting their own dataset + relationship.
                if (factDataset != null) {
                    for (Element level : childrenNamed(hier, "Level")) {
                        factDataset.getFields().add(convertLevel(level));
                    }
                }
                continue;
            }
            Dataset dimDataset = new Dataset();
            String hierName = attr(hier, "name");
            String datasetName = sanitiseName(dimName
                    + (hierName == null || hierName.isBlank() || hierName.equals(dimName) ? "" : "_" + hierName));
            dimDataset.setName(datasetName);
            dimDataset.setSource(qualifyTable(attr(hierTable, "schema"), attr(hierTable, "name")));
            String primaryKey = attr(hier, "primaryKey");
            if (primaryKey != null && !primaryKey.isBlank()) {
                dimDataset.getPrimaryKey().add(primaryKey);
            }
            applyAnnotationsToAiContext(dim, dimDataset::setAiContext);
            applyAnnotationsToExtensions(dim, dimDataset.getCustomExtensions());

            // Levels become fields on the dim dataset.
            for (Element level : childrenNamed(hier, "Level")) {
                dimDataset.getFields().add(convertLevel(level));
            }
            sm.getDatasets().add(dimDataset);

            // Relationship: fact.foreignKey → dim.primaryKey. Skip when either side is unresolvable.
            if (factDataset != null && foreignKey != null && !foreignKey.isBlank() && primaryKey != null) {
                Relationship rel = new Relationship();
                rel.setName(sanitiseName(factDataset.getName() + "_to_" + dimDataset.getName()));
                rel.setFrom(factDataset.getName());
                rel.setTo(dimDataset.getName());
                rel.getFromColumns().add(foreignKey);
                rel.getToColumns().add(primaryKey);
                sm.getRelationships().add(rel);
            }
        }
    }

    private Field convertLevel(Element level) {
        Field f = new Field();
        f.setName(attr(level, "name"));
        String column = attr(level, "column");
        if (column != null && !column.isBlank()) {
            // ANSI_SQL expression is just the column reference — the field lives on this dataset,
            // so no qualification is needed.
            f.setExpression(Expression.ansi(column));
        }
        // Ossie's is_time triggers off Mondrian's levelType attribute (TimeYears / TimeQuarters
        // / TimeMonths / TimeDays / etc). Any Time* value marks the field as temporal.
        String levelType = attr(level, "levelType");
        if (levelType != null && levelType.startsWith("Time")) {
            f.setDimension(new DimensionMeta(true));
        }
        applyAnnotationsToAiContext(level, f::setAiContext);
        applyAnnotationsToExtensions(level, f.getCustomExtensions());
        return f;
    }

    /* ---------------- measures ---------------- */

    private Metric convertMeasure(String cubeName, Dataset factDataset, Element measure) {
        Metric m = new Metric();
        String name = attr(measure, "name");
        m.setName(name);
        String aggregator = attr(measure, "aggregator");
        String column = attr(measure, "column");
        Expression expr = new Expression();
        // ANSI_SQL — best-effort from aggregator + column, qualified by the fact dataset name.
        if (aggregator != null && column != null && factDataset != null) {
            String ansi = translateAggregator(aggregator, factDataset.getName() + "." + column);
            if (ansi != null) {
                expr.add("ANSI_SQL", ansi);
            }
        }
        // MDX — the canonical unique name Mondrian resolves. Downstream MDX-native consumers
        // (Saiku itself, XMLA clients) use this dialect.
        expr.add("MDX", "[Measures].[" + name + "]");
        m.setExpression(expr);
        applyAnnotationsToAiContext(measure, m::setAiContext);
        applyAnnotationsToExtensions(measure, m.getCustomExtensions());
        return m;
    }

    private Metric convertCalculatedMember(String cubeName, Element calc) {
        Metric m = new Metric();
        m.setName(attr(calc, "name"));
        Expression expr = new Expression();
        // Formula lives in <Formula>text</Formula> child OR in a formula="..." attribute — try
        // both, in that order (child element wins if both present).
        Element formula = firstChild(calc, "Formula");
        String mdx = formula != null ? formula.getTextContent().trim() : attr(calc, "formula");
        if (mdx != null && !mdx.isBlank()) {
            expr.add("MDX", mdx);
        }
        m.setExpression(expr);
        applyAnnotationsToAiContext(calc, m::setAiContext);
        applyAnnotationsToExtensions(calc, m.getCustomExtensions());
        return m;
    }

    /**
     * Translate a Mondrian aggregator attribute value into an ANSI SQL aggregation function
     * wrapped around the qualified column reference. Returns null for aggregators we can't
     * express in one line — the caller falls back to MDX-only.
     */
    private String translateAggregator(String aggregator, String qualifiedColumn) {
        switch (aggregator.toLowerCase(Locale.ROOT)) {
            case "sum":
                return "SUM(" + qualifiedColumn + ")";
            case "count":
                return "COUNT(" + qualifiedColumn + ")";
            case "distinct-count":
                return "COUNT(DISTINCT " + qualifiedColumn + ")";
            case "min":
                return "MIN(" + qualifiedColumn + ")";
            case "max":
                return "MAX(" + qualifiedColumn + ")";
            case "avg":
                return "AVG(" + qualifiedColumn + ")";
            default:
                return null;
        }
    }

    /* ---------------- annotations ---------------- */

    /**
     * Extract {@code saiku.semantic.description} + {@code saiku.semantic.synonyms} from an
     * element's {@code <Annotations>} block and populate an Ossie AiContext via the given setter.
     * Ossie has first-class homes for both, so we prefer these over {@code custom_extensions}.
     */
    private void applyAnnotationsToAiContext(Element parent, java.util.function.Consumer<AiContext> setter) {
        Map<String, String> ann = readAnnotations(parent);
        if (ann.isEmpty()) return;
        AiContext ctx = new AiContext();
        String desc = ann.get(SEMANTIC_DESCRIPTION);
        if (desc != null && !desc.isBlank()) ctx.setInstructions(desc);
        String syn = ann.get(SEMANTIC_SYNONYMS);
        if (syn != null && !syn.isBlank()) {
            List<String> list = new ArrayList<>();
            for (String s : syn.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) list.add(t);
            }
            ctx.setSynonyms(list);
        }
        if (!ctx.isEmpty()) setter.accept(ctx);
    }

    /**
     * Push everything OTHER than description/synonyms into a single SAIKU-vendored extension.
     * Empty payload → no extension appended (Ossie's own {@code @JsonInclude(NON_EMPTY)} would
     * strip it anyway, but keeping the emitted YAML tidy is easier if we just skip it up-front).
     */
    private void applyAnnotationsToExtensions(Element parent, List<CustomExtension> extensions) {
        Map<String, String> ann = readAnnotations(parent);
        if (ann.isEmpty()) return;
        // Keep insertion order for a stable YAML diff.
        Map<String, String> payload = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : ann.entrySet()) {
            if (!e.getKey().startsWith(SEMANTIC_PREFIX)) continue;
            String bare = e.getKey().substring(SEMANTIC_PREFIX.length());
            if (bare.equals("description") || bare.equals("synonyms")) continue;
            payload.put(bare, e.getValue());
        }
        if (payload.isEmpty()) return;
        try {
            ObjectNode node = json.createObjectNode();
            for (Map.Entry<String, String> e : payload.entrySet()) {
                // pii → boolean if the source spells 'true' / 'false'; anything else stays a string.
                String v = e.getValue();
                if (v != null && (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false"))) {
                    node.put(e.getKey(), Boolean.parseBoolean(v));
                } else {
                    node.put(e.getKey(), v);
                }
            }
            extensions.add(new CustomExtension(CustomExtension.VENDOR_SAIKU, json.writeValueAsString(node)));
        } catch (JsonProcessingException ignored) {
            // Never happens for a freshly built ObjectNode of primitives.
        }
    }

    private Map<String, String> readAnnotations(Element parent) {
        Element wrap = firstChild(parent, "Annotations");
        if (wrap == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Element a : childrenNamed(wrap, "Annotation")) {
            String name = attr(a, "name");
            if (name == null) continue;
            out.put(name, a.getTextContent() == null ? "" : a.getTextContent().trim());
        }
        return out;
    }

    /* ---------------- DOM helpers ---------------- */

    private static List<Element> childrenNamed(Element parent, String tagName) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tagName.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static Element firstChild(Element parent, String tagName) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tagName.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String attr(Element el, String name) {
        String v = el.getAttribute(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static String qualifyTable(String schema, String table) {
        if (table == null || table.isEmpty()) return null;
        if (schema == null || schema.isEmpty()) return table;
        return schema + "." + table;
    }

    /** Ossie identifiers are effectively YAML keys — spaces + brackets survive but are ugly. */
    private static String sanitiseName(String raw) {
        if (raw == null) return null;
        return raw.replaceAll("[\\[\\]]", "").replace(' ', '_');
    }

    private static String nullIfBlank(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    // Reserved for HashMap-based caching if we start doing shared-dim resolution.
    @SuppressWarnings("unused")
    private final Map<String, Dataset> unused = new HashMap<>();
}
