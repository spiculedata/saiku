/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query2.filter;

import java.util.ArrayList;
import java.util.List;

public class ThinFilter {

    private FilterFlavour flavour;
    private FilterOperator operator;
    private FilterFunction function;
    private List<String> expressions = new ArrayList<>();

    public enum FilterFlavour {
        Generic,
        Measure,
        Name,
        NameLike,
        N
    }

    public enum FilterOperator {
        EQUALS,
        NOTEQUAL,
        GREATER,
        GREATER_EQUALS,
        SMALLER,
        SMALLER_EQUALS,
        LIKE
    }

    public enum FilterFunction {
        Filter,
        TopCount,
        TopPercent,
        TopSum,
        BottomCount,
        BottomPercent,
        BottomSum
    }

    public ThinFilter() {}

    public ThinFilter(
            FilterFlavour flavour, FilterOperator operator, FilterFunction function, List<String> expressions) {
        this.flavour = flavour;
        this.operator = operator;
        this.function = function;
        this.expressions = expressions;
    }

    /**
     * @return the flavour
     */
    public FilterFlavour getFlavour() {
        return flavour;
    }

    /**
     * @return the operator
     */
    public FilterOperator getOperator() {
        return operator;
    }

    /**
     * @return the function
     */
    public FilterFunction getFunction() {
        return function;
    }

    /**
     * @return the expressions
     */
    public List<String> getExpressions() {
        return expressions;
    }
}
