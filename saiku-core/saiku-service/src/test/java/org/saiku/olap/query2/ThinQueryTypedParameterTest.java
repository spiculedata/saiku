/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query2;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * {@link ThinQuery#getParameterResolvedMdx()} wiring for the typed parameter path (saiku#832):
 * typed {@code :name} parameters are bound first, then the untyped {@code ${name}} parameters are
 * substituted, so a query using only one style behaves exactly as that style's binder alone would.
 */
public class ThinQueryTypedParameterTest {

    @Test
    public void typedParametersAreBoundWhenPresent() {
        ThinQuery tq = new ThinQuery("q", null, "SELECT FROM [Sales] WHERE :year");
        tq.setTypedParameters(Arrays.asList(new Parameter("year", Parameter.ParameterType.MEMBER, "[Time].[1997]")));
        assertEquals("SELECT FROM [Sales] WHERE [Time].[1997]", tq.getParameterResolvedMdx());
    }

    @Test
    public void untypedParametersStillWorkWhenNoTypedParametersSet() {
        ThinQuery tq = new ThinQuery("q", null, "SELECT FROM [Sales] WHERE [Time].[${year}]");
        Map<String, String> params = new HashMap<>();
        params.put("year", "1997");
        tq.setParameters(params);
        assertEquals("SELECT FROM [Sales] WHERE [Time].[1997]", tq.getParameterResolvedMdx());
    }

    @Test
    public void mdxWithNoParametersIsUnchanged() {
        ThinQuery tq = new ThinQuery("q", null, "SELECT FROM [Sales]");
        assertEquals("SELECT FROM [Sales]", tq.getParameterResolvedMdx());
    }

    @Test
    public void bothStylesCanBeUsedInTheSameQuery() {
        ThinQuery tq = new ThinQuery("q", null, "SELECT FROM [Sales] WHERE (:year, [Product].[${brand}])");
        tq.setTypedParameters(Arrays.asList(new Parameter("year", Parameter.ParameterType.MEMBER, "[Time].[1997]")));
        Map<String, String> params = new HashMap<>();
        params.put("brand", "Denny");
        tq.setParameters(params);
        assertEquals("SELECT FROM [Sales] WHERE ([Time].[1997], [Product].[Denny])", tq.getParameterResolvedMdx());
    }
}
