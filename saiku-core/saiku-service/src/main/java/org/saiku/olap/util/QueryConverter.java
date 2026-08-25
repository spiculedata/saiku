package org.saiku.olap.util;

import org.olap4j.mdx.SelectNode;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Member;
import org.olap4j.query.Query;
import org.olap4j.query.QueryAxis;
import org.olap4j.query.QueryDimension;
import org.olap4j.query.Selection;
import org.olap4j.query.Selection.Operator;
import org.saiku.olap.util.exception.SaikuIncompatibleException;
import org.saiku.query.QueryHierarchy;
import org.saiku.query.SortOrder;
import org.saiku.query.mdx.GenericFilter;
import org.saiku.query.mdx.IFilterFunction.MdxFunctionType;
import org.saiku.query.mdx.NFilter;

public class QueryConverter {

    public static SelectNode convert(Query query) throws Exception {
        org.saiku.query.Query sQuery = convertQuery(query);
        return sQuery.getSelect();
    }

    public static org.saiku.query.Query convertQuery(Query query) throws Exception {
        org.saiku.query.Query sQuery = new org.saiku.query.Query(query.getName(), query.getCube());

        for (QueryAxis axis : query.getAxes().values()) {
            if (axis.getLocation() != null) {
                org.saiku.query.QueryAxis sAxis = sQuery.getAxis(axis.getLocation());
                convertAxis(axis, sAxis, sQuery);
            }
        }
        return sQuery;
    }

    private static void convertAxis(QueryAxis axis, org.saiku.query.QueryAxis sAxis, org.saiku.query.Query sQuery)
            throws Exception {

        for (QueryDimension qD : axis.getDimensions()) {
            convertDimension(qD, sAxis, sQuery);
        }

        if (axis.getSortOrder() != null) {
            SortOrder so = SortOrder.valueOf(axis.getSortOrder().toString());
            sAxis.sort(so, axis.getSortIdentifierNodeName());
        }

        if (axis.getFilterCondition() != null) {
            sAxis.addFilter(new GenericFilter(axis.getFilterCondition()));
        }

        if (axis.getLimitFunction() != null) {
            NFilter nf = new NFilter(
                    MdxFunctionType.valueOf(axis.getLimitFunction().toString()),
                    axis.getLimitFunctionN().intValue(),
                    axis.getLimitFunctionSortLiteral());
            sAxis.addFilter(nf);
        }

        sAxis.setNonEmpty(axis.isNonEmpty());
    }

    private static void convertDimension(
            QueryDimension qD, org.saiku.query.QueryAxis sAxis, org.saiku.query.Query sQuery) throws Exception {
        boolean first = true;
        QueryHierarchy qh = null;
        for (Selection sel : qD.getInclusions()) {
            if (first) {
                Hierarchy hierarchy = (sel.getRootElement() instanceof Member)
                        ? ((Member) sel.getRootElement()).getHierarchy()
                        : ((Level) sel.getRootElement()).getHierarchy();

                // Resolve through the metadata object rather than the unique name: getHierarchy(String)
                // matches on the key convention of the query's hierarchy map, which does not always
                // agree with Hierarchy.getUniqueName(). The name is kept as a fallback.
                qh = sQuery.getHierarchy(hierarchy);
                if (qh == null) {
                    qh = sQuery.getHierarchy(hierarchy.getUniqueName());
                }
                if (qh == null) {
                    throw new SaikuIncompatibleException(
                            "Cannot convert query: hierarchy not found in the target cube: "
                                    + hierarchy.getUniqueName());
                }
                first = false;
            }

            if (sel.getSelectionContext() != null) {
                throw new SaikuIncompatibleException("Cannot convert queries with selection context");
            }
            if ((sel.getRootElement() instanceof Member)) {
                if (sel.getOperator().equals(Operator.MEMBER)) {
                    qh.includeMember(sel.getRootElement().getUniqueName());
                } else {
                    throw new SaikuIncompatibleException(
                            "Cannot convert member selection using operator: " + sel.getOperator());
                }
            } else {
                qh.includeLevel(sel.getRootElement().getName());
            }
        }
        if (qh == null) {
            // A dimension carrying no inclusion at all - filter-only, or exclusions alone - never
            // enters the loop above, and addHierarchy(null) fails inside QueryAxis. There is
            // nothing to place on the axis, so leave it alone.
            return;
        }
        sAxis.addHierarchy(qh);
    }
}
