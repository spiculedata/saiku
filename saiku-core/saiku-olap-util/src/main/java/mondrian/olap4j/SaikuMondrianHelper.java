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
package mondrian.olap4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import mondrian.olap.*;
import mondrian.rolap.*;
import org.olap4j.*;
import org.olap4j.Position;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Measure;
import org.olap4j.metadata.MetadataElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SaikuMondrianHelper {

    private static final Logger log = LoggerFactory.getLogger(SaikuMondrianHelper.class);

    private static RolapConnection getMondrianConnection(OlapConnection con) {
        try {
            if (!isMondrianConnection(con)) {
                throw new IllegalArgumentException("Connection has to wrap RolapConnection");
            }
            return con.unwrap(RolapConnection.class);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static MondrianServer getMondrianServer(OlapConnection con) {
        RolapConnection rcon = getMondrianConnection(con);
        return rcon != null ? rcon.getServer() : null;
    }

    /**
     * Drop {@code con}'s schema from Mondrian's schema pool.
     *
     * <p>saiku#1872: the Cube Designer's query preview mounts each unsaved schema under a fresh
     * catalog path, so without this an editing session leaves one cached RolapSchema behind per
     * preview run. Lives here because reaching the Mondrian connection needs package access.
     *
     * <p>Best-effort by design — failing to tidy a cache entry must never fail the query the user
     * actually asked for.
     *
     * @return true if the schema was flushed
     */
    public static boolean flushSchema(OlapConnection con) {
        try {
            RolapConnection rcon = getMondrianConnection(con);
            rcon.getCacheControl(null).flushSchema(rcon.getSchema());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isMondrianConnection(OlapConnection con) {
        try {
            return con.isWrapperFor(RolapConnection.class);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static boolean isMondrianDrillthrough(OlapConnection con, String mdx) {
        boolean isMondrian = isMondrianConnection(con);
        if (isMondrian) {
            RolapConnection rcon = getMondrianConnection(con);
            QueryPart qp = rcon.parseStatement(mdx);
            return (qp != null && qp instanceof DrillThrough);
        }
        return false;
    }

    public static void setRoles(OlapConnection con, String[] roleNames) throws Exception {
        RolapConnection rcon = getMondrianConnection(con);
        if (roleNames == null) {
            con.setRoleName(null);
            return;
        }
        Schema schema = rcon.getSchema();
        List<Role> roleList = new ArrayList<Role>();
        Role role;
        for (String roleName : roleNames) {
            Role role1 = schema.lookupRole(roleName);
            if (role1 == null) {
                throw Util.newError("Role '" + roleName + "' not found");
            }
            roleList.add(role1);
        }
        switch (roleList.size()) {
            case 0:
                role = null;
                break;
            case 1:
                role = roleList.get(0);
                break;
            default:
                role = RoleImpl.union(roleList);
                break;
        }
        rcon.setRole(role);
    }

    private static boolean isMondrian(MetadataElement element) {
        return (element instanceof MondrianOlap4jMetadataElement);
    }

    private static Map<String, Annotation> getAnnotations(Level level) {
        if (isMondrian(level)) {
            MondrianOlap4jLevel mlevel = (MondrianOlap4jLevel) level;
            mondrian.olap.Level rvl = mlevel.level;
            return rvl.getAnnotationMap();
        }
        return new HashMap<String, Annotation>();
    }

    public static boolean hasAnnotation(Level level, String key) {
        Map<String, Annotation> a = getAnnotations(level);
        return a.containsKey(key);
    }

    public boolean isHanger(org.olap4j.metadata.Dimension dimension) {
        if (isMondrian(dimension)) {
            RolapCubeDimension dim = (RolapCubeDimension) dimension;
            return DimensionLookup.getHanger(dim);
        }
        return false;
    }

    public static String getMeasureGroup(Measure measure) {
        if (isMondrian(measure)) {
            MondrianOlap4jMeasure m = (MondrianOlap4jMeasure) measure;

            try {
                return ((RolapBaseCubeMeasure) m.member).getMeasureGroup().getName();
            } catch (Exception e) {
                return "";
            } catch (Error e2) {
                return "";
            }
        }
        return null;
    }

    /**
     * For a Mondrian cube dimension, return the names of every measure group
     * the dimension has a real (non-NoLink) join to. Used by the SPA's
     * applicability hinting in DimensionList: when the user has measures
     * selected the SPA mutes dimensions whose link set is not a superset of
     * the required measure groups, so virtual cubes that mix conformed and
     * non-conformed dims surface the asymmetry instead of silently producing
     * sparse/empty cellsets (the saiku-cloud applicability-hinting feature).
     *
     * <p>Returns:
     * <ul>
     *   <li>a set of MG names (possibly empty for a fully-NoLinked dim);</li>
     *   <li>{@code null} for non-Mondrian providers — caller treats as
     *       "no information, assume applicable" to preserve legacy UX.</li>
     * </ul>
     */
    public static java.util.Set<String> getMeasureGroupsForDimension(org.olap4j.metadata.Dimension dim) {
        if (!isMondrian(dim)) {
            return null;
        }
        try {
            MondrianOlap4jDimension odim = (MondrianOlap4jDimension) dim;
            // getOlapElement() is protected — accessible from this same
            // mondrian.olap4j package. Returns the wrapped Mondrian
            // Dimension (which is a RolapCubeDimension when surfaced
            // through Cube.getDimensions()).
            mondrian.olap.OlapElement el = odim.getOlapElement();
            if (!(el instanceof RolapCubeDimension)) {
                return null;
            }
            RolapCubeDimension rcd = (RolapCubeDimension) el;
            RolapCube cube = rcd.getCube();
            java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
            // dimensionMap3 is keyed by RolapCubeDimension and only contains
            // dims with a real (non-NoLink) join — exactly the applicability
            // signal we want to surface to the SPA.
            for (RolapMeasureGroup mg : cube.getMeasureGroups()) {
                if (mg.dimensionMap3.containsKey(rcd)) {
                    out.add(mg.getName());
                }
            }
            return out;
        } catch (Throwable t) {
            // Defensive: never let a metadata introspection failure poison
            // discover — the SPA treats null as "no info, assume applicable".
            log.debug("getMeasureGroupsForDimension failed: {}", t.toString());
            return null;
        }
    }

    private static boolean isHanger(RolapCubeDimension dimension) {
        return DimensionLookup.getHanger(dimension);
    }

    public static ResultSet getSQLMemberLookup(OlapConnection con, String annotation, Level level, String search)
            throws SQLException {
        if (hasAnnotation(level, annotation)) {
            Map<String, Annotation> ann = getAnnotations(level);
            Annotation a = ann.get(annotation);
            String sql = a.getValue().toString();

            log.debug(
                    "Level SQLMember Lookup for " + level.getName() + " sql:[" + sql + "] parameter [" + search + "]");

            RolapConnection rcon = con.unwrap(RolapConnection.class);
            DataSource ds = rcon.getDataSource();
            Connection sqlcon = ds.getConnection();
            PreparedStatement stmt = sqlcon.prepareCall(sql);
            stmt.setString(1, search);
            return stmt.executeQuery();
        }
        return null;
    }

    public static List<org.olap4j.metadata.Member> getMDXMemberLookup(OlapConnection con, String cube, Level level) {
        String l;
        RolapCubeDimension o = (RolapCubeDimension) ((MondrianOlap4jDimension) level.getDimension()).getOlapElement();
        if (isHanger(o)) {
            l = level.getHierarchy().getUniqueName();
        } else {
            l = level.getUniqueName();
        }
        // try-with-resources so the OlapStatement and CellSet are always closed — the method
        // materialises the members into a List, so the cell set can be released here. This also
        // removes the prior NPE risk where a failed createStatement() left statement == null
        // and the next block dereferenced it (saiku#1191).
        try (OlapStatement statement = con.createStatement();
                CellSet cellSet = statement.executeOlapQuery("with member [Measures].[Zero] as 0\n"
                        + " select AddCalculatedMembers(" + l
                        + ".Members) on 0\n"
                        + " from [" + cube + "]\n"
                        + " where [Measures].[Zero]")) {
            List<org.olap4j.metadata.Member> members = new ArrayList<org.olap4j.metadata.Member>();
            List<CellSetAxis> cellSetAxes = cellSet.getAxes();
            CellSetAxis columnsAxis = cellSetAxes.get(0);
            for (Position position : columnsAxis.getPositions()) {
                org.olap4j.metadata.Member m = position.getMembers().get(0);
                members.add(m);
            }
            return members;
        } catch (java.sql.SQLException e) {
            // OlapException (from executeOlapQuery) and the implicit close() of the
            // OlapStatement/CellSet both surface as SQLException — OlapException extends it.
            e.printStackTrace();
        }
        return null;
    }

    //	public void getAnnotationMap(MetadataElement element) throws SQLException {
    //		if (isMondrian(element)) {
    //			MondrianOlap4jMetadataElement el = (MondrianOlap4jMetadataElement) element;
    //			el.getOlapElement().
    //			OlapElementBase base = el.unwrap(OlapElementBase.class);
    //
    //
    //		}
    //	}

    public static OlapElement getChildLevel(Level l) {
        if (l instanceof MondrianOlap4jLevel) {
            return ((RolapCubeLevel) ((MondrianOlap4jLevel) l).getOlapElement()).getChildLevel();
        }
        return null;
    }
}
