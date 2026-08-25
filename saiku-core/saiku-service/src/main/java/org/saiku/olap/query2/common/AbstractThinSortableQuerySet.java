/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query2.common;

public abstract class AbstractThinSortableQuerySet extends AbstractThinQuerySet implements ThinSortableQuerySet {

    private SortOrder sortOrder;
    private String sortEvaluationLiteral;
    private HierarchizeMode hierarchizeMode;

    public void sort(SortOrder order) {
        this.sortOrder = order;
    }

    public void sort(SortOrder order, String sortEvaluationLiteral) {
        this.sortOrder = order;
        this.sortEvaluationLiteral = sortEvaluationLiteral;
    }

    public SortOrder getSortOrder() {
        return sortOrder;
    }

    public String getSortEvaluationLiteral() {
        return sortEvaluationLiteral;
    }

    public void clearSort() {
        this.sortOrder = null;
        this.sortEvaluationLiteral = null;
    }

    public HierarchizeMode getHierarchizeMode() {
        return this.hierarchizeMode;
    }

    public void setHierarchizeMode(HierarchizeMode hierarchizeMode) {
        this.hierarchizeMode = hierarchizeMode;
    }

    public void clearHierarchizeMode() {
        this.hierarchizeMode = null;
    }
}
