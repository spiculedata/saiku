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

import java.lang.reflect.Constructor;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import mondrian.olap.Annotation;
import mondrian.olap4j.Checker;
import mondrian.olap4j.LevelInterface;
import mondrian.olap4j.SaikuMondrianHelper;
import org.apache.commons.lang.StringUtils;
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
        return new SaikuDimension(
                dim.getName(),
                dim.getUniqueName(),
                dim.getCaption(),
                dim.getDescription(),
                dim.isVisible(),
                convertHierarchies(dim.getHierarchies()));
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
        Checker c = new Checker();
        try {
            try {
                Class.forName("mondrian.olap4j.MondrianOlap4jLevelExtend");
                // Class.forName("bi.meteorite.CheckClass");
                Class<LevelInterface> _tempClass =
                        (Class<LevelInterface>) Class.forName("mondrian.olap4j.MondrianOlap4jLevelExtend");
                if (c.checker(level)) {
                    Constructor<LevelInterface> ctor =
                            _tempClass.getDeclaredConstructor(org.olap4j.metadata.Level.class);
                    LevelInterface test = ctor.newInstance(level);
                    HashMap<String, String> m = null;
                    if (test.getAnnotations() != null) {
                        m = new HashMap<>();
                        for (Map.Entry<String, Annotation> entry :
                                test.getAnnotations().entrySet()) {
                            m.put(entry.getKey(), (String) entry.getValue().getValue());
                        }
                    }
                    return new SaikuLevel(
                            test.getName(),
                            test.getUniqueName(),
                            test.getCaption(),
                            test.getDescription(),
                            test.getDimension().getUniqueName(),
                            test.getHierarchy().getUniqueName(),
                            test.isVisible(),
                            test.getLevelType().toString(),
                            m);
                } else {
                    return new SaikuLevel(
                            level.getName(),
                            level.getUniqueName(),
                            level.getCaption(),
                            level.getDescription(),
                            level.getDimension().getUniqueName(),
                            level.getHierarchy().getUniqueName(),
                            level.isVisible(),
                            null,
                            null);
                }
            } catch (ClassNotFoundException e) {
                return new SaikuLevel(
                        level.getName(),
                        level.getUniqueName(),
                        level.getCaption(),
                        level.getDescription(),
                        level.getDimension().getUniqueName(),
                        level.getHierarchy().getUniqueName(),
                        level.isVisible(),
                        null,
                        null);
            }

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
        return new SaikuMember(
                m.getName(),
                m.getUniqueName(),
                m.getCaption(),
                m.getDescription(),
                m.getDimension().getUniqueName(),
                m.getHierarchy().getUniqueName(),
                m.getLevel().getUniqueName(),
                m.isCalculated());
    }

    @NotNull
    public static SaikuMeasure convertMeasure(@NotNull Measure m) {
        Map<String, Property> props2 = m.getProperties().asMap();

        NamedList<Property> props = m.getProperties();
        // String f = m.getPropertyValue(Property.);
        String f = SaikuMondrianHelper.getMeasureGroup(m);

        return new SaikuMeasure(
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
