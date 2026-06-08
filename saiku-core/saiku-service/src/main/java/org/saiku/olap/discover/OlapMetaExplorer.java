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
package org.saiku.olap.discover;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mondrian.olap4j.SaikuMondrianHelper;
import mondrian.rolap.RolapConnection;
import org.apache.commons.lang3.StringUtils;
import org.olap4j.OlapConnection;
import org.olap4j.OlapDatabaseMetaData;
import org.olap4j.OlapException;
import org.olap4j.mdx.IdentifierNode;
import org.olap4j.mdx.IdentifierSegment;
import org.olap4j.metadata.*;
import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.olap.dto.*;
import org.saiku.olap.util.ObjectUtil;
import org.saiku.olap.util.SaikuCubeCaptionComparator;
import org.saiku.olap.util.SaikuDimensionCaptionComparator;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.util.MondrianDictionary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OlapMetaExplorer {

    private static final Logger log = LoggerFactory.getLogger(OlapMetaExplorer.class);

    private final IConnectionManager connections;

    public OlapMetaExplorer(IConnectionManager ic) {
        connections = ic;
    }

    public SaikuConnection getConnection(String connectionName) throws SaikuOlapException {
        OlapConnection olapcon = connections.getOlapConnection(connectionName);
        SaikuConnection connection;
        if (olapcon != null) {
            List<SaikuCatalog> catalogs = new ArrayList<>();
            try {
                for (Catalog cat : olapcon.getOlapCatalogs()) {
                    List<SaikuSchema> schemas = new ArrayList<>();
                    for (Schema schem : cat.getSchemas()) {
                        List<SaikuCube> cubes = new ArrayList<>();
                        for (Cube cub : schem.getCubes()) {
                            cubes.add(new SaikuCube(
                                    connectionName,
                                    cub.getUniqueName(),
                                    cub.getName(),
                                    cub.getCaption(),
                                    cat.getName(),
                                    schem.getName(),
                                    cub.isVisible()));
                        }
                        Collections.sort(cubes, new SaikuCubeCaptionComparator());
                        schemas.add(new SaikuSchema(schem.getName(), cubes));
                    }
                    if (schemas.size() == 0) {
                        OlapDatabaseMetaData olapDbMeta = olapcon.getMetaData();
                        ResultSet cubesResult = olapDbMeta.getCubes(cat.getName(), null, null);

                        try {
                            List<SaikuCube> cubes = new ArrayList<>();
                            while (cubesResult.next()) {

                                cubes.add(new SaikuCube(
                                        connectionName,
                                        cubesResult.getString("CUBE_NAME"),
                                        cubesResult.getString("CUBE_NAME"),
                                        cubesResult.getString("CUBE_NAME"),
                                        cubesResult.getString("CATALOG_NAME"),
                                        cubesResult.getString("SCHEMA_NAME")));
                            }
                            Collections.sort(cubes, new SaikuCubeCaptionComparator());
                            schemas.add(new SaikuSchema("", cubes));
                        } catch (SQLException e) {
                            throw new OlapException(e.getMessage(), e);
                        } finally {
                            try {
                                cubesResult.close();
                            } catch (SQLException e) {
                                log.error("Could not close cubesResult", e.getNextException());
                            }
                        }
                    }
                    Collections.sort(schemas);
                    catalogs.add(new SaikuCatalog(cat.getName(), schemas));
                }
            } catch (OlapException e) {
                throw new SaikuOlapException("Error getting objects of connection (" + connectionName + ")", e);
            }
            Collections.sort(catalogs);
            connection = new SaikuConnection(connectionName, catalogs);
            return connection;
        }
        throw new SaikuOlapException("Cannot find connection: (" + connectionName + ")");
    }

    public List<SaikuConnection> getConnections(List<String> connectionNames) throws SaikuOlapException {
        List<SaikuConnection> connectionList = new ArrayList<>();
        for (String connectionName : connectionNames) {
            connectionList.add(getConnection(connectionName));
        }
        return connectionList;
    }

    public List<SaikuConnection> getAllConnections() throws SaikuOlapException {
        List<SaikuConnection> cubesList = new ArrayList<>();
        for (String connectionName : connections.getAllOlapConnections().keySet()) {
            cubesList.add(getConnection(connectionName));
        }
        Collections.sort(cubesList);
        return cubesList;
    }

    public List<SaikuCube> getCubes(String connectionName) throws SaikuOlapException {
        OlapConnection olapcon = connections.getOlapConnection(connectionName);
        // saiku#1221 Phase 3: cache the connection's catalog-XML URL once per
        // call so we can parse TimeCalc directives per cube. Empty when the
        // connection isn't configured with a Mondrian Catalog= URL.
        String catalogUrl = resolveCatalogUrl(connectionName);
        List<SaikuCube> cubes = new ArrayList<>();
        if (olapcon != null) {
            try {
                for (Catalog cat : olapcon.getOlapCatalogs()) {
                    for (Schema schem : cat.getSchemas()) {
                        for (Cube cub : schem.getCubes()) {
                            SaikuCube sc = new SaikuCube(
                                    connectionName,
                                    cub.getUniqueName(),
                                    cub.getName(),
                                    cub.getCaption(),
                                    cat.getName(),
                                    schem.getName(),
                                    cub.isVisible());
                            if (catalogUrl != null) {
                                sc.setTimeCalcs(
                                        org.saiku.olap.util.TimeCalcParser.parse(catalogUrl, cub.getName()));
                            }
                            cubes.add(sc);
                        }
                    }
                }
            } catch (OlapException e) {
                log.error("Olap Exception", e.getCause());
            }
        }
        Collections.sort(cubes, new SaikuCubeCaptionComparator());
        return cubes;
    }

    /** Pull the {@code Catalog=} URL out of a connection's {@code location}
     *  property (the Mondrian connection string). Returns {@code null} when
     *  the connection doesn't expose one — TimeCalc surfacing is a nice-to-
     *  have, never load-bearing. */
    private String resolveCatalogUrl(String connectionName) {
        try {
            org.saiku.datasources.connection.ISaikuConnection scon = connections.getConnection(connectionName);
            if (scon == null) return null;
            java.util.Properties props = scon.getProperties();
            if (props == null) return null;
            String location = props.getProperty(org.saiku.datasources.connection.ISaikuConnection.URL_KEY);
            return org.saiku.olap.util.TimeCalcParser.extractCatalogUrl(location);
        } catch (Exception ignored) {
            return null;
        }
    }

    public List<SaikuCube> getCubes(List<String> connectionNames) throws SaikuOlapException {
        List<SaikuCube> cubesList = new ArrayList<>();
        for (String connectionName : connectionNames) {
            cubesList.addAll(getCubes(connectionName));
        }
        Collections.sort(cubesList, new SaikuCubeCaptionComparator());
        return cubesList;
    }

    public List<SaikuCube> getAllCubes() throws SaikuOlapException {
        List<SaikuCube> cubes = new ArrayList<>();
        for (String connectionName : connections.getAllOlapConnections().keySet()) {
            cubes.addAll(getCubes(connectionName));
        }
        Collections.sort(cubes, new SaikuCubeCaptionComparator());
        return cubes;
    }

    public Cube getNativeCube(SaikuCube cube) throws SaikuOlapException {
        try {
            OlapConnection con = connections.getOlapConnection(cube.getConnection());
            if (con != null) {
                for (Database db : con.getOlapDatabases()) {
                    Catalog cat = db.getCatalogs().get(cube.getCatalog());
                    if (cat != null) {
                        for (Schema schema : cat.getSchemas()) {
                            if ((StringUtils.isBlank(cube.getSchema()) && StringUtils.isBlank(schema.getName()))
                                    || schema.getName().equals(cube.getSchema())) {
                                for (Cube cub : schema.getCubes()) {
                                    if (cub.getName().equals(cube.getName())
                                            || cub.getUniqueName().equals(cube.getName())) {
                                        return cub;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new SaikuOlapException("Cannot get native cube for ( " + cube + " )", e);
        }
        throw new SaikuOlapException("Cannot get native cube for ( " + cube + " )");
    }

    public OlapConnection getNativeConnection(String name) throws SaikuOlapException {
        try {
            OlapConnection con = connections.getOlapConnection(name);
            if (con != null) {
                return con;
            }
        } catch (Exception e) {
            throw new SaikuOlapException("Cannot get native connection for ( " + name + " )", e);
        }
        return null;
    }

    public List<SaikuDimension> getAllDimensions(SaikuCube cube) throws SaikuOlapException {
        Cube nativeCube = getNativeCube(cube);
        List<SaikuDimension> dimensions = ObjectUtil.convertDimensions(nativeCube.getDimensions());
        for (int i = 0; i < dimensions.size(); i++) {
            SaikuDimension dim = dimensions.get(i);
            if (dim.getName().equals("Measures") || dim.getUniqueName().equals("[Measures]")) {
                dimensions.remove(i);
                break;
            }
        }
        Collections.sort(dimensions, new SaikuDimensionCaptionComparator());
        return dimensions;
    }

    public SaikuDimension getDimension(SaikuCube cube, String dimensionName) throws SaikuOlapException {
        Cube nativeCube = getNativeCube(cube);
        Dimension dim = nativeCube.getDimensions().get(dimensionName);
        if (dim != null) {
            return ObjectUtil.convert(dim);
        }
        return null;
    }

    public List<SaikuHierarchy> getAllHierarchies(SaikuCube cube) throws SaikuOlapException {
        Cube nativeCube = getNativeCube(cube);
        return ObjectUtil.convertHierarchies(nativeCube.getHierarchies());
    }

    public SaikuHierarchy getHierarchy(SaikuCube cube, String hierarchyName) throws SaikuOlapException {
        Cube nativeCube = getNativeCube(cube);
        Hierarchy h = findHierarchy(hierarchyName, nativeCube);
        if (h != null) {
            return ObjectUtil.convert(h);
        }
        return null;
    }

    public List<SaikuMember> getHierarchyRootMembers(SaikuCube cube, String hierarchyName) throws SaikuOlapException {
        Cube nativeCube = getNativeCube(cube);
        List<SaikuMember> members = new ArrayList<>();
        Hierarchy h = findHierarchy(hierarchyName, nativeCube);

        if (h == null) {
            for (Hierarchy hlist : nativeCube.getHierarchies()) {
                if (hlist.getUniqueName().equals(hierarchyName)
                        || hlist.getName().equals(hierarchyName)) {
                    h = hlist;
                }
            }
        }
        if (h != null) {
            try {
                members = (ObjectUtil.convertMembers(h.getRootMembers()));
            } catch (OlapException e) {
                throw new SaikuOlapException("Cannot retrieve root members of hierarchy: " + hierarchyName, e);
            }
        }

        return members;
    }

    public List<SaikuLevel> getAllLevels(SaikuCube cube, String dimension, String hierarchy) throws SaikuOlapException {
        Cube nativeCube = getNativeCube(cube);
        Dimension dim = nativeCube.getDimensions().get(dimension);
        if (dim != null) {
            Hierarchy h = dim.getHierarchies().get(hierarchy);
            if (h == null) {
                for (Hierarchy hlist : dim.getHierarchies()) {
                    if (hlist.getUniqueName().equals(hierarchy)
                            || hlist.getName().equals(hierarchy)) {
                        h = hlist;
                    }
                }
            }

            if (h != null) {
                return (ObjectUtil.convertLevels(h.getLevels()));
            }
        }
        return new ArrayList<>();
    }

    public List<SimpleCubeElement> getAllMembers(SaikuCube cube, String hierarchy, String level)
            throws SaikuOlapException {
        return getAllMembers(cube, hierarchy, level, null, -1);
    }

    public List<SimpleCubeElement> getAllMembers(
            SaikuCube cube, String hierarchy, String level, String searchString, int searchLimit)
            throws SaikuOlapException {
        try {
            Cube nativeCube = getNativeCube(cube);
            OlapConnection con =
                    nativeCube.getSchema().getCatalog().getDatabase().getOlapConnection();
            Hierarchy h = findHierarchy(hierarchy, nativeCube);

            boolean search = StringUtils.isNotBlank(searchString);
            // Pre-lowercase the search string once. The contains() check
            // below lower-cases both the member name and caption, but the
            // raw `searchString` was being compared as-is — so any query
            // with mixed case (e.g. q="Excellent") returned zero hits
            // because it was matched against the all-lower-case member.
            String searchLower = search ? searchString.toLowerCase() : null;
            int found = 0;
            List<SimpleCubeElement> simpleMembers;
            if (h != null) {
                Level l = h.getLevels().get(level);
                if (l == null) {
                    for (Level lvl : h.getLevels()) {
                        if (lvl.getUniqueName().equals(level) || lvl.getName().equals(level)) {
                            l = lvl;
                            break;
                        }
                    }
                }
                if (l == null) {
                    throw new SaikuOlapException(
                            "Cannot find level " + level + " in hierarchy " + hierarchy + " of cube " + cube.getName());
                }
                if (isMondrian(nativeCube)) {
                    if (SaikuMondrianHelper.hasAnnotation(l, MondrianDictionary.SQLMemberLookup)) {
                        if (search) {
                            // getSQLMemberLookup returns a live ResultSet backed by its own JDBC
                            // Connection + Statement (opened from the Mondrian RolapConnection's
                            // DataSource). The helper can't close them without losing the ResultSet,
                            // and this caller previously dropped all three — a JDBC connection leak on
                            // every SQL member search. Materialise, then close the ResultSet and its
                            // owning Statement + Connection (saiku#1191).
                            ResultSet rs = SaikuMondrianHelper.getSQLMemberLookup(
                                    con, MondrianDictionary.SQLMemberLookup, l, searchString);
                            try {
                                simpleMembers = ObjectUtil.convert2simple(rs);
                                log.debug("Found " + simpleMembers.size() + " members using SQL lookup for level "
                                        + level);
                            } finally {
                                closeResultSetWithOwner(rs);
                            }
                            return simpleMembers;
                        } else {
                            return new ArrayList<>();
                        }
                    }
                }
                if (search || searchLimit > 0) {
                    List<Member> foundMembers = new ArrayList<>();
                    List<Member> lokuplist;
                    if (SaikuMondrianHelper.isMondrianConnection(con)
                            && SaikuMondrianHelper.getMondrianServer(con)
                                            .getVersion()
                                            .getMajorVersion()
                                    >= 4) {
                        lokuplist = SaikuMondrianHelper.getMDXMemberLookup(con, cube.getName(), l);
                    } else {
                        lokuplist = l.getMembers();
                    }
                    for (Member m : lokuplist) {
                        if (search) {
                            if (m.getName().toLowerCase().contains(searchLower)
                                    || m.getCaption().toLowerCase().contains(searchLower)) {
                                foundMembers.add(m);
                                found++;
                            }
                        } else {
                            foundMembers.add(m);
                            found++;
                        }
                        if (searchLimit > 0 && found >= searchLimit) {
                            break;
                        }
                    }
                    simpleMembers = ObjectUtil.convert2Simple(foundMembers);
                } else {
                    List<Member> lookuplist = null;
                    if (SaikuMondrianHelper.isMondrianConnection(con)
                            && SaikuMondrianHelper.getMondrianServer(con)
                                            .getVersion()
                                            .getMajorVersion()
                                    >= 4) {
                        lookuplist = SaikuMondrianHelper.getMDXMemberLookup(con, cube.getName(), l);
                    } else {
                        lookuplist = l.getMembers();
                    }
                    simpleMembers = ObjectUtil.convert2Simple(lookuplist);
                }
                return simpleMembers;
            }
        } catch (Exception e) {
            throw new SaikuOlapException("Cannot get all members", e);
        }

        return new ArrayList<>();
    }

    /**
     * Close a ResultSet returned by {@link SaikuMondrianHelper#getSQLMemberLookup} together with the
     * JDBC Statement and Connection that own it. The lookup helper opens a fresh connection from the
     * Mondrian DataSource and hands back only the ResultSet, so closing the ResultSet alone would
     * leak the Statement and (pooled) Connection. Best-effort and null-safe (saiku#1191).
     */
    private static void closeResultSetWithOwner(ResultSet rs) {
        if (rs == null) {
            return;
        }
        Statement st = null;
        Connection cn = null;
        try {
            st = rs.getStatement();
            if (st != null) {
                cn = st.getConnection();
            }
        } catch (SQLException ignored) {
            // best-effort recovery of the owning Statement/Connection
        }
        try {
            rs.close();
        } catch (SQLException ignored) {
            // ignore
        }
        if (st != null) {
            try {
                st.close();
            } catch (SQLException ignored) {
                // ignore
            }
        }
        if (cn != null) {
            try {
                cn.close();
            } catch (SQLException ignored) {
                // ignore
            }
        }
    }

    public List<SaikuMember> getMemberChildren(SaikuCube cube, String uniqueMemberName) throws SaikuOlapException {
        List<SaikuMember> members = new ArrayList<>();
        try {
            Cube nativeCube = getNativeCube(cube);
            List<IdentifierSegment> memberList =
                    IdentifierNode.parseIdentifier(uniqueMemberName).getSegmentList();
            Member m = nativeCube.lookupMember(memberList);
            if (m != null) {
                for (Member c : m.getChildMembers()) {
                    SaikuMember sm = ObjectUtil.convert(c);
                    members.add(sm);
                }
            }
        } catch (OlapException e) {
            throw new SaikuOlapException("Cannot get child members of member:" + uniqueMemberName, e);
        }

        return members;
    }

    public List<SaikuMember> getAllMeasures(SaikuCube cube) throws SaikuOlapException {
        List<SaikuMember> measures = new ArrayList<>();
        try {
            Cube nativeCube = getNativeCube(cube);
            for (Measure measure : nativeCube.getMeasures()) {
                if (measure.isVisible()) {
                    measures.add(ObjectUtil.convertMeasure(measure));
                }
            }
            if (measures.size() == 0) {
                Hierarchy hierarchy = nativeCube.getDimensions().get("Measures").getDefaultHierarchy();
                measures = (ObjectUtil.convertMembers(hierarchy.getRootMembers()));
            }
        } catch (OlapException e) {
            throw new SaikuOlapException("Cannot get measures for cube:" + cube.getName(), e);
        }

        //		Collections.sort(measures, new SaikuMemberCaptionComparator());
        return measures;
    }

    public SaikuMember getMember(SaikuCube cube, String uniqueMemberName) throws SaikuOlapException {
        try {
            Cube nativeCube = getNativeCube(cube);
            Member m = nativeCube.lookupMember(
                    IdentifierNode.parseIdentifier(uniqueMemberName).getSegmentList());
            if (m != null) {
                return ObjectUtil.convert(m);
            }
            return null;
        } catch (Exception e) {
            throw new SaikuOlapException("Cannot find member: " + uniqueMemberName + " in cube:" + cube.getName(), e);
        }
    }

    private boolean isMondrian(Cube cube) {
        OlapConnection con = cube.getSchema().getCatalog().getDatabase().getOlapConnection();
        try {
            return con.isWrapperFor(RolapConnection.class);
        } catch (SQLException e) {
            log.error("SQLException", e.getNextException());
        }
        return false;
    }

    private Hierarchy findHierarchy(String name, Cube cube) {
        Hierarchy h = cube.getHierarchies().get(name);
        if (h != null) {
            return h;
        }
        for (Hierarchy hierarchy : cube.getHierarchies()) {
            if (hierarchy.getUniqueName().equals(name)) {
                return hierarchy;
            }
        }
        return null;
    }
}
