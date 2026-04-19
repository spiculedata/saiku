package org.saiku.olap.query2.common;

import java.util.List;
import org.saiku.olap.query2.filter.ThinFilter;

public interface ThinQuerySet {

    String getName();

    void setMdx(String mdxSetExpression);

    String getMdx();

    void addFilter(ThinFilter filter);

    void setFilter(int index, ThinFilter filter);

    List<ThinFilter> getFilters();

    void clearFilters();
}
