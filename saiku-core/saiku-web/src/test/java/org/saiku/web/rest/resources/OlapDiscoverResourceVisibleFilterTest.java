/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuMeasure;
import org.saiku.olap.dto.SaikuMember;
import org.saiku.service.olap.OlapDiscoverService;

/**
 * saiku#778 — server-side filtering of hidden measures in
 * {@link OlapDiscoverResource#getCubeMeasures}. Default ({@code includeHidden=false})
 * must drop measures marked {@code visible=false}; admin clients pass
 * {@code includeHidden=true} to see them all.
 */
public class OlapDiscoverResourceVisibleFilterTest {

    private OlapDiscoverResource resource;

    @Before
    public void setUp() {
        resource = new OlapDiscoverResource();
        resource.setOlapDiscoverService(new OlapDiscoverService() {
            @Override
            public List<SaikuMember> getMeasures(SaikuCube cube) {
                return Arrays.asList(
                        measure("Store Sales", true), measure("Hidden Helper", false), measure("Unit Sales", true));
            }
        });
    }

    @Test
    public void hiddenMeasuresFilteredByDefault() {
        List<SaikuMember> out = resource.getCubeMeasures("foodmart", "FoodMart", "FoodMart", "Sales", false);
        assertEquals("two visible measures kept", 2, out.size());
        assertTrue("Store Sales present", contains(out, "Store Sales"));
        assertTrue("Unit Sales present", contains(out, "Unit Sales"));
    }

    @Test
    public void includeHiddenTrueReturnsAll() {
        List<SaikuMember> out = resource.getCubeMeasures("foodmart", "FoodMart", "FoodMart", "Sales", true);
        assertEquals("all three measures kept", 3, out.size());
        assertTrue("Hidden Helper present when includeHidden=true", contains(out, "Hidden Helper"));
    }

    private static SaikuMember measure(String name, boolean visible) {
        return new SaikuMeasure(
                name,
                "[Measures].[" + name + "]",
                name,
                "",
                "[Measures]",
                "[Measures].[MeasuresLevel]",
                "[Measures].[MeasuresLevel]",
                visible,
                false,
                null);
    }

    private static boolean contains(List<SaikuMember> list, String name) {
        for (SaikuMember m : list) {
            if (name.equals(m.getName())) return true;
        }
        return false;
    }
}
