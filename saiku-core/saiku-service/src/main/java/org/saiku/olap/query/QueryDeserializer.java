/*
 *   Copyright 2012 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.olap.query;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import org.apache.commons.lang3.StringUtils;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.olap4j.Axis;
import org.olap4j.OlapConnection;
import org.olap4j.OlapException;
import org.olap4j.mdx.IdentifierNode;
import org.olap4j.mdx.IdentifierSegment;
import org.olap4j.metadata.Catalog;
import org.olap4j.metadata.Cube;
import org.olap4j.metadata.Database;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Schema;
import org.olap4j.query.LimitFunction;
import org.olap4j.query.Query;
import org.olap4j.query.QueryAxis;
import org.olap4j.query.QueryDimension;
import org.olap4j.query.QueryDimension.HierarchizeMode;
import org.olap4j.query.Selection;
import org.olap4j.query.SortOrder;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.util.exception.QueryParseException;
import org.saiku.service.util.xml.SecureXml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;

/**
 * De-Serializes an XML into a PatQuery object
 *
 * @author Paul Stoellberger
 * @created May 27, 2010
 * @since 0.8
 */
public class QueryDeserializer {

    private static final Logger log = LoggerFactory.getLogger(QueryDeserializer.class);

    private static final String QUERY = "Query";
    private static final String CUBE = "cube";
    private static final String CONNECTION = "connection";
    private static final String CATALOG = "catalog";
    private static final String SCHEMA = "schema";
    private static final String SELECTION = "Selection";
    private Document dom;
    private Query qm;
    private OlapConnection connection;
    private InputSource source;

    public IQuery unparse(String xml, OlapConnection connection) throws Exception {
        this.connection = connection;
        SAXBuilder parser = SecureXml.secureSaxBuilder();
        source = new InputSource((new ByteArrayInputStream(xml.getBytes("UTF8"))));
        dom = parser.build(source);
        Element child = dom.getRootElement();
        Element qmElement = child.getChild("QueryModel");
        Element mdxElement = child.getChild("MDX");

        IQuery returnQuery;
        if (qmElement != null) {
            returnQuery = createQmQuery();
            return returnQuery;
        } else if (mdxElement != null) {
            returnQuery = createMdxQuery();
            return returnQuery;
        }
        throw new Exception("Cant find <QueryModel> nor <MDX> Query");
    }

    public SaikuCube getFakeCube(String xml) throws Exception {
        SAXBuilder parser = SecureXml.secureSaxBuilder();
        InputSource source = new InputSource((new ByteArrayInputStream(xml.getBytes())));
        Document dom = parser.build(source);

        Element queryElement = dom.getRootElement();
        if (queryElement != null && queryElement.getName().equals(QUERY)) {

            String cubeName = queryElement.getAttributeValue(CUBE);

            String connectionName = queryElement.getAttributeValue(CONNECTION);
            String catalogName = queryElement.getAttributeValue(CATALOG);
            String schemaName = queryElement.getAttributeValue(SCHEMA);
            return new SaikuCube(connectionName, cubeName, cubeName, cubeName, catalogName, schemaName);
        }
        throw new Exception("Cant find <QueryModel> nor <MDX> Query");
    }

    public SaikuCube getCube(String xml, OlapConnection con) throws Exception {
        this.connection = con;
        SAXBuilder parser = SecureXml.secureSaxBuilder();
        source = new InputSource((new ByteArrayInputStream(xml.getBytes())));
        dom = parser.build(source);

        Element queryElement = dom.getRootElement();
        if (queryElement != null && queryElement.getName().equals(QUERY)) {

            String cubeName = queryElement.getAttributeValue(CUBE);

            if (!StringUtils.isNotBlank(cubeName)) {
                throw new QueryParseException("Cube for query not defined");
            }
            String connectionName = queryElement.getAttributeValue(CONNECTION);
            String catalogName = queryElement.getAttributeValue(CATALOG);
            String schemaName = queryElement.getAttributeValue(SCHEMA);
            Query tmpQuery = createEmptyQuery("tmp-1234", catalogName, schemaName, cubeName);
            Cube cub = tmpQuery.getCube();
            return new SaikuCube(
                    connectionName, cub.getUniqueName(), cub.getName(), cub.getCaption(), catalogName, schemaName);
        }
        throw new Exception("Cant find <QueryModel> nor <MDX> Query");
    }

    private IQuery createQmQuery() throws QueryParseException, SQLException {

        Element queryElement = dom.getRootElement();
        if (queryElement != null && queryElement.getName().equals(QUERY)) {

            String queryName = queryElement.getAttributeValue("name");
            String cubeName = queryElement.getAttributeValue(CUBE);

            if (!StringUtils.isNotBlank(cubeName)) {
                throw new QueryParseException("Cube for query not defined");
            }
            String connectionName = queryElement.getAttributeValue(CONNECTION);
            String catalogName = queryElement.getAttributeValue(CATALOG);
            String schemaName = queryElement.getAttributeValue(SCHEMA);

            try {
                Element qmElement = queryElement.getChild("QueryModel");
                if (qmElement != null) {
                    qm = createEmptyQuery(queryName, catalogName, schemaName, cubeName);
                    manipulateQuery(qmElement);
                    SaikuCube cube = new SaikuCube(
                            connectionName,
                            cubeName,
                            qm.getCube().getName(),
                            qm.getCube().getCaption(),
                            catalogName,
                            schemaName);
                    IQuery q = new OlapQuery(qm, connection, cube, false);
                    setTotals(q, queryElement);
                    Properties p = getProperties(queryElement);
                    q.setProperties(p);
                    return q;
                } else {
                    throw new OlapException("Can't find child <QueryModel>");
                }

            } catch (OlapException e) {
                throw new QueryParseException(e.getMessage(), e);
            }

        } else {
            throw new QueryParseException(
                    "Cannot parse Query Model: Query node not found and/or more than 1 Query node found");
        }
    }

    private IQuery createMdxQuery() throws QueryParseException {

        Element queryElement = dom.getRootElement();
        if (queryElement != null && queryElement.getName().equals(QUERY)) {

            String queryName = queryElement.getAttributeValue("name");
            String cubeName = queryElement.getAttributeValue(CUBE);

            String connectionName = queryElement.getAttributeValue(CONNECTION);
            String catalogName = queryElement.getAttributeValue(CATALOG);
            String schemaName = queryElement.getAttributeValue(SCHEMA);
            Properties props = getProperties(queryElement);
            try {
                Element mdxElement = queryElement.getChild("MDX");
                if (mdxElement != null) {
                    SaikuCube cube =
                            new SaikuCube(connectionName, cubeName, cubeName, cubeName, catalogName, schemaName);
                    IQuery q = new MdxQuery(connection, cube, queryName, mdxElement.getText());
                    q.setProperties(props);
                    return q;
                } else {
                    throw new OlapException("Can't find child <MDX>");
                }

            } catch (OlapException e) {
                throw new QueryParseException(e.getMessage(), e);
            }

        } else {
            throw new QueryParseException(
                    "Cannot parse Query Model: Query node not found and/or more than 1 Query node found");
        }
    }

    private void setTotals(IQuery q, Element queryElement) {
        final Element totals = queryElement.getChild("Totals");
        if (null != totals) {
            final List totalList = totals.getChildren("Total");
            for (Element function : (List<Element>) totalList) {
                final String uniqueLevelName = function.getAttributeValue("uniqueLevelName");
                final String functionName = function.getAttributeValue("functionName");
                q.setTotalFunction(uniqueLevelName, functionName);
            }
        }
    }

    private Properties getProperties(Element queryElement) {
        Properties props = new Properties();
        try {

            Element propertiesElement = queryElement.getChild("Properties");
            if (propertiesElement != null) {
                for (int i = 0; i < propertiesElement.getChildren("Property").size(); i++) {
                    Element p =
                            (Element) propertiesElement.getChildren("Property").get(i);
                    String k = p.getAttributeValue("name");
                    String v = p.getAttributeValue("value");
                    props.put(k, v);
                }
            }
        } catch (Exception e) {
            log.error("Exception", e.getCause());
        }
        return props;
    }

    private void manipulateQuery(Element qmElement) throws OlapException {
        moveDims2Axis(qmElement);
    }

    private void moveDims2Axis(Element qmElement) throws OlapException {
        Element axesElement = qmElement.getChild("Axes");
        if (axesElement != null) {

            for (int i = 0; i < axesElement.getChildren("Axis").size(); i++) {
                Element axisElement = (Element) axesElement.getChildren("Axis").get(i);

                String location = axisElement.getAttributeValue("location");
                if (!StringUtils.isNotBlank(location)) {
                    throw new OlapException("Location for Axis Element can't be null");
                }

                QueryAxis qAxis = qm.getAxes().get(getAxisName(location));

                String nonEmpty = axisElement.getAttributeValue("nonEmpty");
                if (StringUtils.isNotBlank(nonEmpty)) {
                    qAxis.setNonEmpty(Boolean.parseBoolean(nonEmpty));
                }

                String sortOrder = axisElement.getAttributeValue("sortOrder");
                String sortEvaluationLiteral = axisElement.getAttributeValue("sortEvaluationLiteral");

                if (StringUtils.isNotBlank(sortOrder)) {
                    if (StringUtils.isNotBlank(sortEvaluationLiteral)) {
                        qAxis.sort(SortOrder.valueOf(sortOrder), sortEvaluationLiteral);
                    } else {
                        qAxis.sort(SortOrder.valueOf(sortOrder));
                    }
                }

                String limitFunction = axisElement.getAttributeValue("limitFunction");
                String limitFunctionN = axisElement.getAttributeValue("limitFunctionN");
                String limitFunctionSortLiteral = axisElement.getAttributeValue("limitFunctionSortLiteral");
                String filterCondition = axisElement.getAttributeValue("filterCondition");
                try {
                    if (StringUtils.isNotBlank(limitFunction)) {
                        LimitFunction func = LimitFunction.valueOf(limitFunction);
                        BigDecimal n = new BigDecimal(limitFunctionN);
                        qAxis.limit(func, n, limitFunctionSortLiteral);
                    }
                    if (StringUtils.isNotBlank(filterCondition)) {
                        qAxis.filter(filterCondition);
                    }
                } catch (Error e) {
                    log.error("ERROR", e);
                }

                Element dimensions = axisElement.getChild("Dimensions");
                if (dimensions != null) {
                    for (int z = 0; z < dimensions.getChildren("Dimension").size(); z++) {
                        Element dimensionElement =
                                (Element) dimensions.getChildren("Dimension").get(z);
                        processDimension(dimensionElement, location);
                    }
                }
            }
        }
    }

    /**
     * Finds a level of a dimension from the unique name a saved query carries.
     *
     * <p>An exact match is tried first. Failing that, the last segment is matched against the level
     * names of the dimension: Saiku 2.x wrote a level as {@code [dimension].[level]}, two segments,
     * because Mondrian 3 named it that way when a dimension held a single hierarchy. Mondrian 4
     * always writes the three-segment {@code [dimension].[hierarchy].[level]}, so a query saved
     * before the upgrade no longer matches on the unique name alone.
     *
     * @param dimension the dimension the selection belongs to
     * @param uniqueName the level unique name as stored in the saved query
     * @return the level, or null when the dimension holds nothing by that name
     */
    private static Level findLevel(Dimension dimension, String uniqueName) {
        for (Hierarchy hierarchy : dimension.getHierarchies()) {
            for (Level level : hierarchy.getLevels()) {
                if (level.getUniqueName().equals(uniqueName)) {
                    return level;
                }
            }
        }

        List<IdentifierSegment> segments = IdentifierNode.parseIdentifier(uniqueName).getSegmentList();
        if (segments.isEmpty()) {
            return null;
        }
        String levelName = segments.get(segments.size() - 1).getName();
        for (Hierarchy hierarchy : dimension.getHierarchies()) {
            for (Level level : hierarchy.getLevels()) {
                if (level.getName().equals(levelName)) {
                    return level;
                }
            }
        }
        return null;
    }

    /**
     * Includes a member, tolerating the way Mondrian 3 wrote a member path.
     *
     * <p>Mondrian 3 could write the head of a path as a single segment holding a dot, such as
     * {@code [dimension.hierarchy].[member]}. Mondrian 4 writes {@code [dimension].[hierarchy].
     * [member]} and rejects the first form, so the head is split and the include retried once.
     *
     * @param dim the dimension the selection belongs to
     * @param operator the selection operator
     * @param name the member path as stored in the saved query
     * @return the selection, or null when neither form resolves
     */
    private static Selection includeMember(QueryDimension dim, Selection.Operator operator, String name)
            throws OlapException {
        try {
            return dim.include(operator, IdentifierNode.parseIdentifier(name).getSegmentList());
        } catch (RuntimeException e) {
            List<IdentifierSegment> segments = IdentifierNode.parseIdentifier(name).getSegmentList();
            if (segments.isEmpty() || !segments.get(0).getName().contains(".")) {
                throw e;
            }
            StringBuilder rewritten = new StringBuilder();
            for (String part : segments.get(0).getName().split("\\.")) {
                rewritten.append('[').append(part).append(']').append('.');
            }
            for (int i = 1; i < segments.size(); i++) {
                rewritten.append('[').append(segments.get(i).getName()).append(']');
                if (i < segments.size() - 1) {
                    rewritten.append('.');
                }
            }
            log.warn("Member " + name + " not found, retrying as " + rewritten
                    + ": Mondrian 3 wrote the dimension and hierarchy as one segment");
            return dim.include(
                    operator, IdentifierNode.parseIdentifier(rewritten.toString()).getSegmentList());
        }
    }

    private void processDimension(Element dimension, String location) throws OlapException {

        String dimName = dimension.getAttributeValue("name");
        if (StringUtils.isNotBlank(dimName)) {

            QueryDimension dim = qm.getDimension(dimName);

            if (dim == null) {
                throw new OlapException("Dimension not found:" + dimName);
            }

            String sortOrder = dimension.getAttributeValue("sortOrder");
            if (StringUtils.isNotBlank(sortOrder)) {
                dim.sort(SortOrder.valueOf(sortOrder));
            }

            String hierarchizeMode = dimension.getAttributeValue("hierarchizeMode");
            if (StringUtils.isNotBlank(hierarchizeMode)) {
                dim.setHierarchizeMode(HierarchizeMode.valueOf(hierarchizeMode));
            }

            String hierarchyConsistent = dimension.getAttributeValue("hierarchyConsistent");
            if (StringUtils.isNotBlank(hierarchyConsistent)) {
                dim.setHierarchyConsistent(Boolean.parseBoolean(hierarchyConsistent));
            }

            qm.getAxes().get(Axis.Standard.valueOf(location)).getDimensions().add(dim);

            Element inclusions = dimension.getChild("Inclusions");
            if (inclusions != null) {
                for (int z = 0; z < inclusions.getChildren(SELECTION).size(); z++) {
                    Element selectionElement =
                            (Element) inclusions.getChildren(SELECTION).get(z);
                    String name = selectionElement.getAttributeValue("node");
                    String operator = selectionElement.getAttributeValue("operator");
                    String type = selectionElement.getAttributeValue("type");
                    Selection sel = null;
                    if ("level".equals(type)) {
                        Level level = findLevel(dim.getDimension(), name);
                        if (level != null) {
                            sel = dim.include(level);
                        } else {
                            log.warn("Level not found in dimension " + dim.getName() + ": " + name);
                        }
                    } else if ("member".equals(type)) {
                        sel = includeMember(dim, Selection.Operator.valueOf(operator), name);
                    }

                    Element contextElement = selectionElement.getChild("Context");
                    if (sel != null && contextElement != null) {
                        for (int h = 0;
                                h < contextElement.getChildren(SELECTION).size();
                                h++) {
                            Element context = (Element)
                                    contextElement.getChildren(SELECTION).get(h);
                            String contextname = context.getAttributeValue("node");
                            String contextoperator = context.getAttributeValue("operator");
                            String contextDimension = context.getAttributeValue("dimension");
                            QueryDimension contextDim = qm.getDimension(contextDimension);
                            if (contextDim != null) {
                                Selection contextSelection = contextDim.createSelection(
                                        Selection.Operator.valueOf(contextoperator),
                                        IdentifierNode.parseIdentifier(contextname)
                                                .getSegmentList());
                                if (contextSelection != null) {
                                    sel.addContext(contextSelection);
                                } else {
                                    throw new OlapException("Cannot create selection for node: " + contextname
                                            + " operator:" + contextoperator + " on dimension: " + dim.getName());
                                }
                            } else {
                                throw new OlapException("Context dimension is null");
                            }
                        }
                    }
                }
            }

            Element exclusions = dimension.getChild("Exclusions");
            if (inclusions != null) {
                for (int z = 0; z < exclusions.getChildren(SELECTION).size(); z++) {
                    Element selectionElement =
                            (Element) exclusions.getChildren(SELECTION).get(z);
                    String name = selectionElement.getAttributeValue("node");
                    String operator = selectionElement.getAttributeValue("operator");
                    dim.exclude(
                            Selection.Operator.valueOf(operator),
                            IdentifierNode.parseIdentifier(name).getSegmentList());
                    // ADD CONTEXT ?
                }
            }

        } else {
            throw new OlapException("No Dimension name defined");
        }
    }

    private Query createEmptyQuery(String queryName, String catalogName, String schemaName, String cubeName)
            throws SQLException {
        if (!StringUtils.isNotBlank(catalogName)) {
            try {
                connection.setCatalog(catalogName);
            } catch (SQLException e) {
                throw new OlapException(e.getMessage(), e);
            }
        }

        Cube cube = null;
        if (connection != null) {
            for (Database db : connection.getOlapDatabases()) {
                Catalog cat = db.getCatalogs().get(catalogName);
                if (cat != null) {
                    for (Schema schema : cat.getSchemas()) {
                        if (schema.getName().equals(schemaName)
                                || (schema.getName().equals("") && schemaName == null)) {
                            for (Cube cub : schema.getCubes()) {
                                if (cub.getName().equals(cubeName)
                                        || cub.getUniqueName().equals(cubeName)) {
                                    cube = cub;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (cube != null) {
            try {
                return new Query(queryName, cube);
            } catch (SQLException e) {
                throw new OlapException("Error creating query :" + queryName, e);
            }
        } else {
            throw new OlapException("No Cube with name: " + cubeName + " found");
        }
    }

    private Axis.Standard getAxisName(String location) {
        if (location != null) {
            return org.olap4j.Axis.Standard.valueOf(location);
        }
        return null;
    }
}
