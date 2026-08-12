/*
 * Copyright 2014 OSBI Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.saiku.olap.util;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import mondrian.olap4j.SaikuMondrianHelper;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.olap4j.Axis;
import org.olap4j.OlapException;
import org.olap4j.metadata.*;
import org.olap4j.query.QueryAxis;
import org.olap4j.query.QueryDimension;
import org.olap4j.query.Selection;
import org.saiku.olap.dto.*;
import org.saiku.olap.dto.SaikuSelection.Type;
import org.saiku.olap.query.IQuery;
import org.saiku.service.util.exception.SaikuServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ObjectUtil.
 */
public class ObjectUtil {
    private static final Logger LOG = LoggerFactory.getLogger(ObjectUtil.class);

    private ObjectUtil() {}

    @NotNull
    public static SaikuCube convert(String connection, @NotNull Cube c) {
        return new SaikuCube(
                connection,
                c.getUniqueName(),
                c.getName(),
                c.getCaption(),
                c.getSchema().getCatalog().getName(),
                c.getSchema().getName(),
                c.isVisible());
    }

    @NotNull
    public static SaikuDimension convert(@NotNull Dimension dim) {
        SaikuDimension sd = new SaikuDimension(
                dim.getName(),
                dim.getUniqueName(),
                dim.getCaption(),
                dim.getDescription(),
                dim.isVisible(),
                convertHierarchies(dim.getHierarchies()));
        // saiku#818 follow-up: surface dimension-level annotations so
        // OlapAiCubeMetadataService can apply saiku.semantic.* to AiSchema.Dimension.
        sd.setAnnotations(annotationsAsStringMap(dim));
        // saiku#1221: Mondrian-native dimension type ("TIME" / "MEASURE" /
        // "OTHER") from <Dimension type="TIME">. Lets the SPA detect time
        // dimensions deterministically; the caption substring match was
        // fragile under translations and renamed hierarchies.
        try {
            Dimension.Type t = dim.getDimensionType();
            if (t != null) {
                sd.setDimensionType(t.name());
            }
        } catch (Throwable ignored) {
            // Some drivers throw on getDimensionType — leave null.
        }
        // Per-dimension MeasureGroup link set (Mondrian only). Drives the
        // SPA's dim-applicability hinting on virtual cubes.
        sd.setMeasureGroups(mondrian.olap4j.SaikuMondrianHelper.getMeasureGroupsForDimension(dim));
        return sd;
    }

    @NotNull
    private static SaikuDimension convert(@NotNull QueryDimension dim) {
        return convert(dim.getDimension());
    }

    @NotNull
    public static List<SaikuDimension> convertQueryDimensions(@NotNull List<QueryDimension> dims) {
        List<SaikuDimension> dimList = new ArrayList<>();
        for (QueryDimension d : dims) {
            dimList.add(convert(d));
        }
        return dimList;
    }

    @NotNull
    public static List<SaikuDimension> convertDimensions(@NotNull List<Dimension> dims) {
        List<SaikuDimension> dimList = new ArrayList<>();
        for (Dimension d : dims) {
            dimList.add(convert(d));
        }
        return dimList;
    }

    @NotNull
    public static List<SaikuHierarchy> convertHierarchies(@NotNull List<Hierarchy> hierarchies) {
        List<SaikuHierarchy> hierarchyList = new ArrayList<>();
        for (Hierarchy h : hierarchies) {
            hierarchyList.add(convert(h));
        }
        return hierarchyList;
    }

    @NotNull
    public static SaikuHierarchy convert(@NotNull Hierarchy hierarchy) {
        try {
            return new SaikuHierarchy(
                    hierarchy.getName(),
                    hierarchy.getUniqueName(),
                    hierarchy.getCaption(),
                    hierarchy.getDescription(),
                    hierarchy.getDimension().getUniqueName(),
                    hierarchy.isVisible(),
                    convertLevels(hierarchy.getLevels()),
                    convertMembers(hierarchy.getRootMembers()));
        } catch (OlapException e) {
            throw new SaikuServiceException("Cannot get root members", e);
        }
    }

    @NotNull
    public static List<SaikuLevel> convertLevels(@NotNull List<Level> levels) {
        List<SaikuLevel> levelList = new ArrayList<>();
        for (Level l : levels) {
            levelList.add(convert(l));
        }
        return levelList;
    }

    @NotNull
    private static SaikuLevel convert(@NotNull Level level) {
        try {
            // The historical {@code MondrianOlap4jLevelExtend} reflection bridge is
            // not present in current Spicule/mondrian-saiku builds — fell through to
            // a null-annotation SaikuLevel. saiku#818 routes through the same
            // {@link #annotationsAsStringMap} helper used by the measure path so
            // level annotations actually populate.
            Map<String, String> ann = annotationsAsStringMap(level);
            String levelType = null;
            try {
                levelType = level.getLevelType() == null
                        ? null
                        : level.getLevelType().toString();
            } catch (Throwable ignored) {
                // Some drivers throw on getLevelType — leave null.
            }
            return new SaikuLevel(
                    level.getName(),
                    level.getUniqueName(),
                    level.getCaption(),
                    level.getDescription(),
                    level.getDimension().getUniqueName(),
                    level.getHierarchy().getUniqueName(),
                    level.isVisible(),
                    levelType,
                    ann);
        } catch (Exception e) {
            throw new SaikuServiceException("Cannot convert level: " + level, e);
        }
    }

    @NotNull
    public static List<SaikuMember> convertMembers(@NotNull Collection<Member> members) {
        List<SaikuMember> memberList = new ArrayList<>();
        for (Member m : members) {
            memberList.add(convert(m));
        }
        return memberList;
    }

    @NotNull
    private static List<SaikuSelection> convertSelections(
            @NotNull List<Selection> selections, @NotNull QueryDimension dim, @NotNull IQuery query) {
        List<SaikuSelection> selectionList = new ArrayList<>();
        for (Selection sel : selections) {
            selectionList.add(convert(sel, dim, query));
        }
        return selectionList;
    }

    private static Level getSelectionLevel(@NotNull Selection sel) {
        Level retVal;
        if (Level.class.isAssignableFrom(sel.getRootElement().getClass())) {
            retVal = (Level) sel.getRootElement();
        } else {
            retVal = ((Member) sel.getRootElement()).getLevel();
        }
        return retVal;
    }

    @NotNull
    private static SaikuSelection convert(@NotNull Selection sel, @NotNull QueryDimension dim, @NotNull IQuery query) {
        Type type;
        String hierarchyUniqueName;
        String levelUniqueName;
        Level level;
        if (Level.class.isAssignableFrom(sel.getRootElement().getClass())) {
            level = (Level) sel.getRootElement();
            type = SaikuSelection.Type.LEVEL;
            hierarchyUniqueName = ((Level) sel.getRootElement()).getHierarchy().getUniqueName();
            levelUniqueName = sel.getUniqueName();
        } else {
            level = ((Member) sel.getRootElement()).getLevel();
            type = SaikuSelection.Type.MEMBER;
            hierarchyUniqueName = ((Member) sel.getRootElement()).getHierarchy().getUniqueName();
            levelUniqueName = ((Member) sel.getRootElement()).getLevel().getUniqueName();
        }
        String totalsFunction = query.getTotalFunction(level.getUniqueName());
        List<QueryDimension> dimensions = dim.getAxis().getDimensions();
        QueryDimension lastDimension = dimensions.get(dimensions.size() - 1);
        Selection deepestSelection = null;
        int selectionDepth = -1;
        for (Selection selection : lastDimension.getInclusions()) {
            Level current = getSelectionLevel(selection);
            if (selectionDepth < current.getDepth()) {
                deepestSelection = selection;
                selectionDepth = current.getDepth();
            }
        }
        return new SaikuSelection(
                sel.getRootElement().getName(),
                sel.getUniqueName(),
                sel.getRootElement().getCaption(),
                sel.getRootElement().getDescription(),
                sel.getDimension().getName(),
                hierarchyUniqueName,
                levelUniqueName,
                type,
                totalsFunction,
                sel.equals(deepestSelection));
    }

    @NotNull
    public static SaikuMember convert(@NotNull Member m) {
        SaikuMember sm = new SaikuMember(
                m.getName(),
                m.getUniqueName(),
                m.getCaption(),
                m.getDescription(),
                m.getDimension().getUniqueName(),
                m.getHierarchy().getUniqueName(),
                m.getLevel().getUniqueName(),
                m.isCalculated());
        // saiku#835: non-measure members carry visibility via the standard
        // $visible member property (olap4j Member has no isVisible()). Schema
        // authors mark internal-only members (sentinel "Unknown" buckets,
        // helper calc members) visible="false"; surface that on the DTO so
        // the discovery endpoints can filter them like hidden measures (#778).
        sm.setVisible(memberVisible(m));
        return sm;
    }

    /**
     * Read a member's {@code $visible} property, fail-open to {@code true} when the
     * property is unset, unsupported by the dialect, or the read throws — hiding a
     * member the author didn't hide is worse than showing one they did (saiku#835).
     */
    private static Boolean memberVisible(Member m) {
        try {
            return coerceVisible(m.getPropertyValue(Property.StandardMemberProperty.$visible));
        } catch (Exception unsupportedOrFailed) {
            return Boolean.TRUE;
        }
    }

    /**
     * Coerce a dialect-dependent {@code $visible} property value to a Boolean,
     * failing OPEN (visible) on anything unrecognised. Mondrian hands back a
     * {@link Boolean}; XMLA-style providers may hand back "true"/"false" strings
     * or 0/1 numerics. Public: pure utility, unit-tested from the dto package.
     */
    public static Boolean coerceVisible(Object v) {
        if (v == null) {
            return Boolean.TRUE;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue() != 0;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
                return Boolean.FALSE;
            }
            return Boolean.TRUE;
        }
        return Boolean.TRUE;
    }

    @NotNull
    public static SaikuMeasure convertMeasure(@NotNull Measure m) {
        Map<String, Property> props2 = m.getProperties().asMap();

        NamedList<Property> props = m.getProperties();
        // String f = m.getPropertyValue(Property.);
        String f = SaikuMondrianHelper.getMeasureGroup(m);

        SaikuMeasure sm = new SaikuMeasure(
                m.getName(),
                m.getUniqueName(),
                m.getCaption(),
                m.getDescription(),
                m.getDimension().getUniqueName(),
                m.getHierarchy().getUniqueName(),
                m.getLevel().getUniqueName(),
                m.isVisible(),
                m.isCalculated() | m.isCalculatedInQuery(),
                f);
        // saiku#818: surface the schema-level <Annotation> bag so AI schema
        // projection can read semantic hints (description, unit, synonyms, ...)
        // without reaching back into olap4j.
        sm.setAnnotations(annotationsAsStringMap(m));
        return sm;
    }

    /**
     * Pull a schema-level annotation bag off any olap4j metadata wrapper that
     * exposes a {@code mondrian.olap.Annotated} underneath. olap4j 1.2 doesn't
     * define a metadata-level annotations API, so we have to dig into Mondrian's
     * {@code mondrian.olap.Annotated} interface either:
     * <ol>
     *   <li>direct cast if the wrapper happens to implement it;</li>
     *   <li>reflected {@code getOlapElement()} call (some wrappers expose this);</li>
     *   <li>reflected {@code member} / {@code element} field access on
     *       {@code MondrianOlap4jMember} where the underlying mondrian
     *       {@code Member} is the package-private wrapped instance.</li>
     * </ol>
     * Non-Mondrian olap4j drivers (XMLA, etc.) silently return {@code null}.
     */
    private static Map<String, String> annotationsAsStringMap(Object wrapper) {
        if (wrapper == null) return null;
        try {
            Object annotated = wrapper instanceof mondrian.olap.Annotated ? wrapper : null;
            if (annotated == null) {
                annotated = tryInvoke(wrapper, "getOlapElement");
            }
            if (annotated == null || !(annotated instanceof mondrian.olap.Annotated)) {
                // MondrianOlap4jMember/Level/Hierarchy each store the underlying
                // mondrian.olap.* in a package-private field — name varies by
                // wrapper ({@code member}, {@code level}, {@code hierarchy},
                // {@code olapElement}, {@code element}, ...). Search any
                // declared field on the wrapper (and its superclass chain)
                // for one whose value implements {@code mondrian.olap.Annotated}.
                annotated = findAnnotatedField(wrapper);
            }
            if (!(annotated instanceof mondrian.olap.Annotated)) return null;
            Map<String, mondrian.olap.Annotation> raw = ((mondrian.olap.Annotated) annotated).getAnnotationMap();
            if (raw == null || raw.isEmpty()) return null;
            Map<String, String> out = new HashMap<>();
            for (Map.Entry<String, mondrian.olap.Annotation> e : raw.entrySet()) {
                Object v = e.getValue() == null ? null : e.getValue().getValue();
                out.put(e.getKey(), v == null ? null : v.toString());
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object tryInvoke(Object target, String methodName) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (NoSuchMethodException nsme) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Walk the declared fields of {@code target} (and its superclasses) and
     *  return the first one whose value implements {@code mondrian.olap.Annotated}.
     *  Cheaper than maintaining a name list because Mondrian wrapper field
     *  names ({@code member}, {@code level}, {@code hierarchy}, etc.) drift
     *  across forks. */
    private static Object findAnnotatedField(Object target) {
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(target);
                    if (v instanceof mondrian.olap.Annotated) return v;
                } catch (Throwable ignore) {
                    // keep scanning
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    @NotNull
    public static SaikuDimensionSelection convertDimensionSelection(
            @NotNull QueryDimension dim, @NotNull IQuery query) {
        List<SaikuSelection> selections = ObjectUtil.convertSelections(dim.getInclusions(), dim, query);
        return new SaikuDimensionSelection(
                dim.getName(),
                dim.getDimension().getUniqueName(),
                dim.getDimension().getCaption(),
                dim.getDimension().getDescription(),
                selections);
    }

    @NotNull
    private static List<SaikuDimensionSelection> convertDimensionSelections(
            @NotNull List<QueryDimension> dimensions, @NotNull IQuery query) {
        List<SaikuDimensionSelection> dims = new ArrayList<>();
        for (QueryDimension dim : dimensions) {
            dims.add(convertDimensionSelection(dim, query));
        }
        return dims;
    }

    @NotNull
    private static SaikuAxis convertQueryAxis(@NotNull QueryAxis axis, @NotNull IQuery query) {
        List<SaikuDimensionSelection> dims = ObjectUtil.convertDimensionSelections(axis.getDimensions(), query);
        Axis location = axis.getLocation();
        String so = axis.getSortOrder() == null ? null : axis.getSortOrder().name();
        SaikuAxis sax = new SaikuAxis(
                location.name(),
                location.axisOrdinal(),
                axis.getName(),
                dims,
                so,
                axis.getSortIdentifierNodeName(),
                query.getTotalFunction(axis.getName()));

        try {
            if (axis.getLimitFunction() != null) {
                sax.setLimitFunction(axis.getLimitFunction().toString());
                sax.setLimitFunctionN(axis.getLimitFunctionN().toPlainString());
                sax.setLimitFunctionSortLiteral(axis.getLimitFunctionSortLiteral());
            }
            if (StringUtils.isNotBlank(axis.getFilterCondition())) {
                sax.setFilterCondition(axis.getFilterCondition());
            }
        } catch (Error e) {
            LOG.error("Could not convert query axis", e);
        }

        return sax;
    }

    @NotNull
    public static SaikuQuery convert(@NotNull IQuery q) {
        List<SaikuAxis> axes = new ArrayList<>();
        if (q.getType().equals(IQuery.QueryType.QM)) {
            for (Axis axis : q.getAxes().keySet()) {
                if (axis != null) {
                    axes.add(convertQueryAxis(q.getAxis(axis), q));
                }
            }
        }
        return new SaikuQuery(
                q.getName(), q.getSaikuCube(), axes, q.getMdx(), q.getType().toString(), q.getProperties());
    }

    @NotNull
    public static List<SimpleCubeElement> convert2Simple(@Nullable Collection<? extends MetadataElement> mset) {
        List<SimpleCubeElement> elements = new ArrayList<>();
        if (mset != null) {
            for (MetadataElement e : mset) {
                elements.add(new SimpleCubeElement(e.getName(), e.getUniqueName(), e.getCaption()));
            }
        }
        return elements;
    }

    @NotNull
    public static List<SimpleCubeElement> convert2simple(@Nullable ResultSet rs) {
        try {
            int width = 0;
            boolean first = true;
            List<SimpleCubeElement> elements = new ArrayList<>();
            if (rs != null) {
                while (rs.next()) {
                    if (first) {
                        first = false;
                        width = rs.getMetaData().getColumnCount();
                    }
                    String[] row = new String[3];
                    for (int i = 0; i < width; i++) {
                        row[i] = rs.getString(i + 1);
                    }
                    SimpleCubeElement s = new SimpleCubeElement(row[0], row[1], row[2]);
                    elements.add(s);
                }
            }
            return elements;

        } catch (Exception e) {
            throw new SaikuServiceException("Error converting ResultSet into SimpleCubeElement", e);
        } finally {
            if (rs != null) {
                Statement statement = null;
                Connection con = null;
                try {
                    statement = rs.getStatement();

                } catch (Exception e) {
                    throw new SaikuServiceException(e);
                } finally {
                    try {
                        rs.close();
                        if (statement != null) {
                            statement.close();
                        }
                    } catch (Exception ee) {
                        LOG.error("Could not close statement", ee);
                    }

                    rs = null;
                }
            }
        }
    }
}
