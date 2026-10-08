/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.semantic;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.saiku.service.ossie.OssieModelDto;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * {@link OssieModelDto} &rarr; Tableau {@code .tds} datasource file (saiku#1427, "Semantic Layer
 * Sync").
 *
 * <p>Emits a federated datasource: one {@code <relation type="table">} per Ossie dataset (joined
 * per the model's {@link OssieModelDto.Relationship} list where present), one {@code <column>}
 * per field, and one calculated-field {@code <column>} per {@link OssieModelDto.Metric}.
 *
 * <p><b>Deliberately out of scope for this first cut</b> (tracked on the parent issue):
 *
 * <ul>
 *   <li><b>Live warehouse credentials.</b> The generated {@code <connection class="genericodbc">}
 *       is a placeholder — {@code server}/{@code dbname} are left blank for the analyst to fill
 *       in locally. Saiku's Ossie warehouse URL is an arbitrary JDBC URL (Postgres, DuckDB/Quack,
 *       H2, ...) with no general mapping onto Tableau's native connector taxonomy, so baking in a
 *       live connection is a separate, connector-by-connector effort.
 *   <li><b>{@code .hyper} extract generation.</b> The issue calls this out as "if we can drive one
 *       from the JDBC connection" — a live/extract data source still needs the above connector
 *       mapping first.
 *   <li><b>Multi-component joins.</b> Datasets not reachable from another dataset via the
 *       relationship list are emitted as independent sibling relations (no cross join declared) —
 *       see {@link #skippedRelationships}.
 * </ul>
 *
 * <p>Metric SQL expressions (e.g. {@code SUM("fact_pharma"."NETREVENUE")}) are translated into
 * Tableau calculation formulas by rewriting {@code "dataset"."field"} / {@code dataset.field}
 * references into Tableau's {@code [dataset].[field]} bracket syntax; any part of the expression
 * that isn't a recognised dataset/field reference passes through unchanged (aggregate function
 * names, literals, arithmetic operators all happen to be valid in both dialects for the common
 * cases this produces).
 */
public class TableauTdsExporter {

    private static final Pattern QUALIFIED_REF =
            Pattern.compile("\"?([A-Za-z_][A-Za-z0-9_]*)\"?\\s*\\.\\s*\"?([A-Za-z_][A-Za-z0-9_]*)\"?");

    private final List<String> skippedRelationships = new ArrayList<>();

    /** Relationships whose datasets couldn't be tied into the primary join tree — see class javadoc. */
    public List<String> getSkippedRelationships() {
        return skippedRelationships;
    }

    public SemanticExportResult export(OssieModelDto model) {
        skippedRelationships.clear();
        if (model.getDatasets().isEmpty()) {
            throw new SemanticExportException(
                    "Ossie model '" + model.getName() + "' has no datasets — nothing to export to Tableau.");
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.newDocument();

            Element datasource = doc.createElement("datasource");
            datasource.setAttribute("formatted-name", nameOr(model.getName(), "ossie_model"));
            datasource.setAttribute("inline", "true");
            datasource.setAttribute("source-platform", "saiku");
            datasource.setAttribute("version", "18.1");
            doc.appendChild(datasource);

            Element connection = doc.createElement("connection");
            connection.setAttribute("class", "federated");
            datasource.appendChild(connection);

            Element namedConnections = doc.createElement("named-connections");
            connection.appendChild(namedConnections);
            String connectionAlias = "saiku_" + OssieExportModelSupport.slug(model.getConnection());
            Element namedConnection = doc.createElement("named-connection");
            namedConnection.setAttribute("caption", nameOr(model.getConnection(), model.getName()));
            namedConnection.setAttribute("name", connectionAlias);
            namedConnections.appendChild(namedConnection);
            Element innerConnection = doc.createElement("connection");
            innerConnection.setAttribute("class", "genericodbc");
            innerConnection.setAttribute("authentication", "auth-none");
            // Placeholder — see class javadoc: arbitrary warehouse JDBC has no fixed Tableau
            // connector mapping, so the analyst completes these locally.
            innerConnection.setAttribute("server", "");
            innerConnection.setAttribute("dbname", "");
            innerConnection.setAttribute("odbc-connect-string-extras", "");
            namedConnection.appendChild(innerConnection);

            appendRelations(doc, connection, model, connectionAlias);

            Element cols = doc.createElement("cols");
            for (OssieModelDto.Dataset ds : model.getDatasets()) {
                for (OssieModelDto.Field f : ds.getFields()) {
                    Element map = doc.createElement("map");
                    map.setAttribute("key", "[" + f.getName() + "]");
                    map.setAttribute("value", "[" + ds.getName() + "].[" + f.getName() + "]");
                    cols.appendChild(map);
                }
            }
            connection.appendChild(cols);

            Element aliases = doc.createElement("aliases");
            aliases.setAttribute("enabled", "yes");
            datasource.appendChild(aliases);

            for (OssieModelDto.Dataset ds : model.getDatasets()) {
                for (OssieModelDto.Field f : ds.getFields()) {
                    datasource.appendChild(fieldColumn(doc, ds, f));
                }
            }
            for (OssieModelDto.Metric metric : model.getMetrics()) {
                datasource.appendChild(metricColumn(doc, model, metric));
            }

            String xml = serialize(doc);
            String filename = OssieExportModelSupport.slug(model.getName()) + ".tds";
            return new SemanticExportResult(filename, "application/xml", xml.getBytes(StandardCharsets.UTF_8));
        } catch (SemanticExportException e) {
            throw e;
        } catch (Exception e) {
            throw new SemanticExportException(
                    "Failed to render Tableau .tds for '" + model.getName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Builds the {@code <relation>} tree. Datasets connected by the model's relationships fold
     * into a left-deep inner-join chain in relationship order; any relationship referencing a
     * dataset name the model doesn't declare is recorded in {@link #skippedRelationships} and
     * skipped. Datasets untouched by any relationship become independent sibling relations.
     */
    private void appendRelations(Document doc, Element connection, OssieModelDto model, String connectionAlias) {
        Set<String> joined = new LinkedHashSet<>();
        Element joinTree = null;
        for (OssieModelDto.Relationship rel : model.getRelationships()) {
            OssieModelDto.Dataset from = findDataset(model, rel.getFrom());
            OssieModelDto.Dataset to = findDataset(model, rel.getTo());
            if (from == null || to == null) {
                skippedRelationships.add(rel.getName() != null ? rel.getName() : rel.getFrom() + " -> " + rel.getTo());
                continue;
            }
            if (joinTree == null) {
                joinTree = joinRelation(doc, table(doc, from, connectionAlias), table(doc, to, connectionAlias), rel);
                joined.add(from.getName());
                joined.add(to.getName());
            } else if (joined.contains(from.getName()) && !joined.contains(to.getName())) {
                joinTree = joinRelation(doc, joinTree, table(doc, to, connectionAlias), rel);
                joined.add(to.getName());
            } else if (joined.contains(to.getName()) && !joined.contains(from.getName())) {
                joinTree = joinRelation(doc, joinTree, table(doc, from, connectionAlias), rel);
                joined.add(from.getName());
            } else {
                // Both ends already joined (cycle) or neither end is in the tree yet (disjoint
                // component) — safest to skip rather than guess at a join order.
                skippedRelationships.add(rel.getName() != null ? rel.getName() : rel.getFrom() + " -> " + rel.getTo());
            }
        }
        if (joinTree != null) {
            connection.appendChild(joinTree);
        }
        for (OssieModelDto.Dataset ds : model.getDatasets()) {
            if (!joined.contains(ds.getName())) {
                connection.appendChild(table(doc, ds, connectionAlias));
            }
        }
    }

    private Element joinRelation(Document doc, Element left, Element right, OssieModelDto.Relationship rel) {
        Element join = doc.createElement("relation");
        join.setAttribute("type", "join");
        join.setAttribute("join", "inner");
        List<String> fromCols = rel.getFromColumns();
        List<String> toCols = rel.getToColumns();
        if (!fromCols.isEmpty() && !toCols.isEmpty()) {
            Element clause = doc.createElement("clause");
            clause.setAttribute("type", "join");
            Element expr = doc.createElement("expression");
            expr.setAttribute("op", "=");
            Element leftExpr = doc.createElement("expression");
            leftExpr.setAttribute("op", "[" + rel.getFrom() + "].[" + fromCols.get(0) + "]");
            Element rightExpr = doc.createElement("expression");
            rightExpr.setAttribute("op", "[" + rel.getTo() + "].[" + toCols.get(0) + "]");
            expr.appendChild(leftExpr);
            expr.appendChild(rightExpr);
            clause.appendChild(expr);
            join.appendChild(clause);
        }
        join.appendChild(left);
        join.appendChild(right);
        return join;
    }

    private Element table(Document doc, OssieModelDto.Dataset ds, String connectionAlias) {
        Element relation = doc.createElement("relation");
        relation.setAttribute("name", ds.getName());
        relation.setAttribute("table", "[" + ds.getName() + "]");
        relation.setAttribute("type", "table");
        relation.setAttribute("connection", connectionAlias);
        return relation;
    }

    private OssieModelDto.Dataset findDataset(OssieModelDto model, String name) {
        if (name == null) return null;
        for (OssieModelDto.Dataset ds : model.getDatasets()) {
            if (name.equalsIgnoreCase(ds.getName())) return ds;
        }
        return null;
    }

    private Element fieldColumn(Document doc, OssieModelDto.Dataset ds, OssieModelDto.Field f) {
        Element column = doc.createElement("column");
        column.setAttribute("caption", captionOr(f.getDisplayCaption(), f.getLabel(), f.getName()));
        column.setAttribute("datatype", f.isTime() ? "date" : "string");
        column.setAttribute("name", "[" + ds.getName() + "].[" + f.getName() + "]");
        column.setAttribute("role", "dimension");
        column.setAttribute("type", f.isTime() ? "ordinal" : "nominal");
        if (f.isDisplayHidden()) {
            column.setAttribute("hidden", "true");
        }
        if (f.getDescription() != null && !f.getDescription().isBlank()) {
            Element desc = doc.createElement("desc");
            Element formatted = doc.createElement("formatted-text");
            Element run = doc.createElement("run");
            run.setTextContent(f.getDescription());
            formatted.appendChild(run);
            desc.appendChild(formatted);
            column.appendChild(desc);
        }
        return column;
    }

    private Element metricColumn(Document doc, OssieModelDto model, OssieModelDto.Metric metric) {
        OssieModelDto.Dataset owner = OssieExportModelSupport.resolveMetricDataset(model, metric);
        Element column = doc.createElement("column");
        column.setAttribute("caption", captionOr(metric.getDisplayCaption(), null, metric.getName()));
        column.setAttribute("datatype", "real");
        column.setAttribute("name", "[" + metric.getName() + "]");
        column.setAttribute("role", "measure");
        column.setAttribute("type", "quantitative");
        if (metric.isDisplayHidden()) {
            column.setAttribute("hidden", "true");
        }
        Element calculation = doc.createElement("calculation");
        calculation.setAttribute("class", "tableau");
        calculation.setAttribute("formula", toTableauFormula(metric.getExpression(), owner));
        column.appendChild(calculation);
        return column;
    }

    /** Rewrites {@code "dataset"."field"} / {@code dataset.field} references into {@code [dataset].[field]}. */
    private String toTableauFormula(String expression, OssieModelDto.Dataset fallbackDataset) {
        if (expression == null || expression.isBlank()) {
            return fallbackDataset == null ? "0" : "COUNT([" + fallbackDataset.getName() + "])";
        }
        Matcher m = QUALIFIED_REF.matcher(expression);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(expression, last, m.start());
            out.append('[').append(m.group(1)).append("].[").append(m.group(2)).append(']');
            last = m.end();
        }
        out.append(expression.substring(last));
        return out.toString();
    }

    private String captionOr(String a, String b, String fallback) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return fallback;
    }

    private String nameOr(String a, String fallback) {
        return a != null && !a.isBlank() ? a : fallback;
    }

    private String serialize(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}
