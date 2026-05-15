/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.util.exception.SaikuServiceException;

/**
 * Covers the three injection surfaces the substitutor was created to close
 * (saiku#780) plus parity behaviour with the prior hand-rolled impls:
 *
 * <ul>
 *   <li>Happy-path substitution + multi-param + unset-drop + case-insensitive
 *       lookup + null-safety.</li>
 *   <li>MDX-meta deny list — brackets, quotes, braces, semicolons, control
 *       chars must reject so the value can't break member-ref / set / string
 *       boundaries.</li>
 *   <li>Regex / replacement injection — a value containing {@code $0} must
 *       be inserted literally; a parameter NAME containing regex meta must
 *       still match a literal placeholder body.</li>
 * </ul>
 */
public class MdxParameterSubstitutorTest {

    @Test
    public void simpleAlphanumericSubstitutionWorks() {
        Map<String, String> params = new HashMap<>();
        params.put("year", "1997");
        String out = MdxParameterSubstitutor.substitute("SELECT FROM [Sales] WHERE [Time].[${year}]", params);
        assertEquals("SELECT FROM [Sales] WHERE [Time].[1997]", out);
    }

    @Test
    public void multipleParametersAllSubstituted() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dim", "Product");
        params.put("hier", "Brand Name");
        String out =
                MdxParameterSubstitutor.substitute("SELECT { } ON 0 FROM [Sales] WHERE [${dim}].[${hier}]", params);
        assertEquals("SELECT { } ON 0 FROM [Sales] WHERE [Product].[Brand Name]", out);
    }

    @Test
    public void unsetPlaceholderDroppedToEmpty() {
        String out = MdxParameterSubstitutor.substitute("WHERE [${missing}] keep", new HashMap<>());
        assertEquals("WHERE [${missing}] keep", out);
        Map<String, String> params = new HashMap<>();
        params.put("other", "x");
        out = MdxParameterSubstitutor.substitute("a${missing}b", params);
        assertEquals("ab", out);
    }

    @Test
    public void caseInsensitiveLookupForParityWithPriorImpl() {
        Map<String, String> params = new HashMap<>();
        params.put("Year", "1997");
        String out = MdxParameterSubstitutor.substitute("[${YEAR}]", params);
        assertEquals("[1997]", out);
    }

    @Test
    public void nullInputsReturnInputUnchanged() {
        assertNull(MdxParameterSubstitutor.substitute(null, new HashMap<>()));
        assertEquals("", MdxParameterSubstitutor.substitute("", new HashMap<>()));
        assertEquals("a${b}c", MdxParameterSubstitutor.substitute("a${b}c", null));
    }

    @Test
    public void bracketCharactersRejected() {
        Map<String, String> params = new HashMap<>();
        params.put("v", "Drink].[Beverages");
        SaikuServiceException ex = assertThrows(
                SaikuServiceException.class, () -> MdxParameterSubstitutor.substitute("WHERE [${v}]", params));
        assertTrue(ex.getMessage().contains("'v'"));
    }

    @Test
    public void quoteCharactersRejected() {
        Map<String, String> params = new HashMap<>();
        params.put("s", "she said \"hi\"");
        assertThrows(SaikuServiceException.class, () -> MdxParameterSubstitutor.substitute("${s}", params));
        Map<String, String> params2 = new HashMap<>();
        params2.put("s", "single'quote");
        assertThrows(SaikuServiceException.class, () -> MdxParameterSubstitutor.substitute("${s}", params2));
    }

    @Test
    public void braceCharactersRejected() {
        Map<String, String> params = new HashMap<>();
        params.put("v", "{[Product]}");
        assertThrows(SaikuServiceException.class, () -> MdxParameterSubstitutor.substitute("${v}", params));
    }

    @Test
    public void semicolonRejected() {
        Map<String, String> params = new HashMap<>();
        params.put("v", "1997; DROP CUBE");
        assertThrows(SaikuServiceException.class, () -> MdxParameterSubstitutor.substitute("${v}", params));
    }

    @Test
    public void controlCharactersRejected() {
        Map<String, String> params = new HashMap<>();
        params.put("v", "1997\nfoo");
        assertThrows(SaikuServiceException.class, () -> MdxParameterSubstitutor.substitute("${v}", params));
    }

    @Test
    public void dollarSignInValueDoesNotRegexInject() {
        Map<String, String> params = new HashMap<>();
        params.put("v", "$0 should be literal");
        String out = MdxParameterSubstitutor.substitute("X${v}Y", params);
        assertEquals("X$0 should be literalY", out);
        Map<String, String> params2 = new HashMap<>();
        params2.put("v", "a\\b");
        out = MdxParameterSubstitutor.substitute("${v}", params2);
        assertEquals("a\\b", out);
    }

    @Test
    public void parameterNameWithRegexMetaIsLiteral() {
        Map<String, String> params = new HashMap<>();
        params.put(".*", "wildcard");
        String out = MdxParameterSubstitutor.substitute("before ${.*} after [Time].[1997]", params);
        assertEquals("before wildcard after [Time].[1997]", out);
    }
}
