/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.saiku.olap.query2.Parameter;
import org.saiku.service.util.exception.SaikuServiceException;

/**
 * Typed-parameter counterpart to {@link MdxParameterSubstitutorTest} (saiku#832). Covers the same
 * shape of injection vectors — brackets, quotes, braces, semicolons, control characters — but
 * proves each is bound <em>safely</em> for its declared type rather than rejected outright, plus
 * the type-mismatch and malformed-value cases that are unique to typed binding.
 */
public class TypedMdxParameterBinderTest {

    @Test
    public void memberValueSubstitutedVerbatim() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("year", new Parameter("year", Parameter.ParameterType.MEMBER, "[Time].[1997]"));
        String out = TypedMdxParameterBinder.bind("SELECT FROM [Sales] WHERE :year", params);
        assertEquals("SELECT FROM [Sales] WHERE [Time].[1997]", out);
    }

    @Test
    public void multiSegmentMemberValueSubstituted() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("q", new Parameter("q", Parameter.ParameterType.MEMBER, "[Time].[1997].[Q1]"));
        String out = TypedMdxParameterBinder.bind("WHERE :q", params);
        assertEquals("WHERE [Time].[1997].[Q1]", out);
    }

    @Test
    public void malformedMemberValueRejected() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("v", new Parameter("v", Parameter.ParameterType.MEMBER, "[Time]; DROP CUBE [Sales]"));
        SaikuServiceException ex =
                assertThrows(SaikuServiceException.class, () -> TypedMdxParameterBinder.bind("WHERE :v", params));
        assertTrue(ex.getMessage().contains("'v'"));
    }

    @Test
    public void memberValueCannotEscapeItsOwnBrackets() {
        Map<String, Parameter> params = new HashMap<>();
        // Trailing "} DROP" outside the bracket segments must not be accepted as part of the member.
        params.put("v", new Parameter("v", Parameter.ParameterType.MEMBER, "[Time].[1997]} DROP"));
        assertThrows(SaikuServiceException.class, () -> TypedMdxParameterBinder.bind("WHERE :v", params));
    }

    @Test
    public void stringValueQuotedAndEmbeddedQuoteEscapedByDoubling() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("s", new Parameter("s", Parameter.ParameterType.STRING, "she said \"hi\""));
        String out = TypedMdxParameterBinder.bind("Filter(:s)", params);
        assertEquals("Filter(\"she said \"\"hi\"\"\")", out);
    }

    @Test
    public void stringValueWithBracketsRoundTripsWithoutRejection() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("s", new Parameter("s", Parameter.ParameterType.STRING, "[not a member] {or a set}"));
        String out = TypedMdxParameterBinder.bind(":s", params);
        assertEquals("\"[not a member] {or a set}\"", out);
    }

    @Test
    public void stringValueWithControlCharactersRejected() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("s", new Parameter("s", Parameter.ParameterType.STRING, "line1\nline2"));
        assertThrows(SaikuServiceException.class, () -> TypedMdxParameterBinder.bind(":s", params));
    }

    @Test
    public void numberValueSubstitutedBare() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("n", new Parameter("n", Parameter.ParameterType.NUMBER, "42.5"));
        String out = TypedMdxParameterBinder.bind("[Measures].DefaultMember + :n", params);
        assertEquals("[Measures].DefaultMember + 42.5", out);
    }

    @Test
    public void numberValueAsJsonNumberSubstitutedBare() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("n", new Parameter("n", Parameter.ParameterType.NUMBER, 1997));
        String out = TypedMdxParameterBinder.bind(":n", params);
        assertEquals("1997", out);
    }

    @Test
    public void nonNumericValueDeclaredAsNumberRejected() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("n", new Parameter("n", Parameter.ParameterType.NUMBER, "1997; DROP CUBE"));
        assertThrows(SaikuServiceException.class, () -> TypedMdxParameterBinder.bind(":n", params));
    }

    @Test
    public void setValueRenderedAsBracedMemberList() {
        Map<String, Parameter> params = new HashMap<>();
        params.put(
                "years",
                new Parameter("years", Parameter.ParameterType.SET, Arrays.asList("[Time].[1997]", "[Time].[1998]")));
        String out = TypedMdxParameterBinder.bind("SELECT :years ON 0 FROM [Sales]", params);
        assertEquals("SELECT {[Time].[1997], [Time].[1998]} ON 0 FROM [Sales]", out);
    }

    @Test
    public void tupleValueRenderedAsParenthesizedMemberList() {
        Map<String, Parameter> params = new HashMap<>();
        params.put(
                "t",
                new Parameter(
                        "t", Parameter.ParameterType.TUPLE, Arrays.asList("[Time].[1997]", "[Measures].[Unit Sales]")));
        String out = TypedMdxParameterBinder.bind("WHERE :t", params);
        assertEquals("WHERE ([Time].[1997], [Measures].[Unit Sales])", out);
    }

    @Test
    public void setValueWithMalformedMemberRejected() {
        Map<String, Parameter> params = new HashMap<>();
        params.put(
                "years",
                new Parameter("years", Parameter.ParameterType.SET, Arrays.asList("[Time].[1997]", "not a member")));
        assertThrows(SaikuServiceException.class, () -> TypedMdxParameterBinder.bind(":years", params));
    }

    @Test
    public void emptySetValueRejected() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("years", new Parameter("years", Parameter.ParameterType.SET, Arrays.asList()));
        assertThrows(SaikuServiceException.class, () -> TypedMdxParameterBinder.bind(":years", params));
    }

    @Test
    public void scalarValueDeclaredAsSetRejected() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("years", new Parameter("years", Parameter.ParameterType.SET, "[Time].[1997]"));
        assertThrows(SaikuServiceException.class, () -> TypedMdxParameterBinder.bind(":years", params));
    }

    @Test
    public void listValueDeclaredAsMemberRejected() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("v", new Parameter("v", Parameter.ParameterType.MEMBER, Arrays.asList("[Time].[1997]")));
        assertThrows(SaikuServiceException.class, () -> TypedMdxParameterBinder.bind(":v", params));
    }

    @Test
    public void unmatchedPlaceholderLeftUntouched() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("known", new Parameter("known", Parameter.ParameterType.MEMBER, "[Time].[1997]"));
        String out = TypedMdxParameterBinder.bind("WHERE :known and :unknown", params);
        assertEquals("WHERE [Time].[1997] and :unknown", out);
    }

    @Test
    public void mdxRangeOperatorNotMistakenForPlaceholder() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("bogus", new Parameter("bogus", Parameter.ParameterType.STRING, "x"));
        String out = TypedMdxParameterBinder.bind("WHERE [Time].[1997] : [Time].[1999]", params);
        assertEquals("WHERE [Time].[1997] : [Time].[1999]", out);
    }

    @Test
    public void caseInsensitiveLookup() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("Year", new Parameter("Year", Parameter.ParameterType.MEMBER, "[Time].[1997]"));
        String out = TypedMdxParameterBinder.bind(":YEAR", params);
        assertEquals("[Time].[1997]", out);
    }

    @Test
    public void multipleParametersAllSubstituted() {
        Map<String, Parameter> params = new LinkedHashMap<>();
        params.put("dim", new Parameter("dim", Parameter.ParameterType.MEMBER, "[Product].[Brand Name]"));
        params.put("y", new Parameter("y", Parameter.ParameterType.NUMBER, "1997"));
        String out = TypedMdxParameterBinder.bind("WHERE (:dim, :y)", params);
        assertEquals("WHERE ([Product].[Brand Name], 1997)", out);
    }

    @Test
    public void nullInputsReturnInputUnchanged() {
        assertNull(TypedMdxParameterBinder.bind(null, new HashMap<>()));
        assertEquals("", TypedMdxParameterBinder.bind("", new HashMap<>()));
        assertEquals("a:bc", TypedMdxParameterBinder.bind("a:bc", null));
    }

    @Test
    public void missingTypeRejected() {
        Map<String, Parameter> params = new HashMap<>();
        params.put("v", new Parameter("v", null, "x"));
        assertThrows(SaikuServiceException.class, () -> TypedMdxParameterBinder.bind(":v", params));
    }
}
