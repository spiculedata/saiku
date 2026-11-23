/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.util.formatter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.olap4j.OlapException;
import org.olap4j.impl.Named;
import org.olap4j.impl.NamedListImpl;
import org.olap4j.metadata.Member;
import org.olap4j.metadata.NamedList;
import org.olap4j.metadata.Property;

/**
 * Unit tests for {@link MemberPropertyExtractor} — pulls every olap4j
 * {@link Property} value {@link Member#getProperties()} reports (schema-defined
 * custom hierarchy properties plus the standard ones worth keeping — description,
 * member key, caption, ...) off a {@link Member} and surfaces them as a stable
 * map, skipping the standard identifiers already covered by other {@code
 * MemberCell} fields (saiku#827).
 *
 * <p>Uses JDK {@link Proxy} stubs, same convention as
 * {@link CellPropertyExtractorTest}, so the test doesn't need a real Mondrian
 * member.
 */
public class MemberPropertyExtractorTest {

    @Test
    public void extractsCustomAndUsefulStandardProperties() {
        Property currencyCode = property("Currency Code");
        Property description = property("DESCRIPTION");
        Property memberCaption = property("MEMBER_CAPTION");

        Map<Property, Object> values = new HashMap<>();
        values.put(currencyCode, "GBP");
        values.put(description, "Northern region stores");
        values.put(memberCaption, "Store 12 (i18n)");

        Member member = stubMember(namedList(currencyCode, description, memberCaption), values);
        Map<String, String> out = MemberPropertyExtractor.extract(member);

        assertEquals("GBP", out.get("Currency Code"));
        assertEquals("Northern region stores", out.get("DESCRIPTION"));
        assertEquals("Store 12 (i18n)", out.get("MEMBER_CAPTION"));
        assertEquals(3, out.size());
    }

    @Test
    public void skipsNoiseStandardPropertiesAlreadyOnMemberCell() {
        Property uniqueName = property("MEMBER_UNIQUE_NAME");
        Property hierarchyUniqueName = property("HIERARCHY_UNIQUE_NAME");
        Property visible = property("$visible");
        Property currencyCode = property("Currency Code");

        Map<Property, Object> values = new HashMap<>();
        values.put(uniqueName, "[Store].[USA].[CA]");
        values.put(hierarchyUniqueName, "[Store]");
        values.put(visible, Boolean.TRUE);
        values.put(currencyCode, "USD");

        Member member = stubMember(namedList(uniqueName, hierarchyUniqueName, visible, currencyCode), values);
        Map<String, String> out = MemberPropertyExtractor.extract(member);

        assertFalse("MEMBER_UNIQUE_NAME already on MemberCell.uniqueName", out.containsKey("MEMBER_UNIQUE_NAME"));
        assertFalse("HIERARCHY_UNIQUE_NAME already on MemberCell.hierarchy", out.containsKey("HIERARCHY_UNIQUE_NAME"));
        assertFalse("$visible is a bookkeeping flag, not member metadata", out.containsKey("$visible"));
        assertEquals("USD", out.get("Currency Code"));
        assertEquals(1, out.size());
    }

    @Test
    public void absentAndEmptyValuesAreOmitted() {
        Property currencyCode = property("Currency Code");
        Property region = property("Region Grouping");

        Map<Property, Object> values = new HashMap<>();
        values.put(currencyCode, "");
        // region intentionally absent from `values` -> null value

        Member member = stubMember(namedList(currencyCode, region), values);
        Map<String, String> out = MemberPropertyExtractor.extract(member);

        assertTrue("empty-string property dropped", out.isEmpty());
    }

    @Test
    public void thrownPropertyAccessSilentlyOmitsThatPropertyOnly() {
        Property broken = property("Broken Property");
        Property currencyCode = property("Currency Code");
        NamedList<Property> properties = namedList(broken, currencyCode);

        Member member = (Member) Proxy.newProxyInstance(
                MemberPropertyExtractorTest.class.getClassLoader(),
                new Class<?>[] {Member.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String name = method.getName();
                        if ("getProperties".equals(name)) return properties;
                        if ("getPropertyValue".equals(name) && args != null && args.length == 1) {
                            Property p = (Property) args[0];
                            if (p == broken) throw new OlapException("evaluator not materialised");
                            if (p == currencyCode) return "EUR";
                            return null;
                        }
                        return null;
                    }
                });

        Map<String, String> out = MemberPropertyExtractor.extract(member);
        assertEquals("EUR", out.get("Currency Code"));
        assertFalse("the property that threw is silently absent", out.containsKey("Broken Property"));
    }

    @Test
    public void nullMemberReturnsEmptyMap() {
        Map<String, String> out = MemberPropertyExtractor.extract(null);
        assertNotNull(out);
        assertEquals(0, out.size());
    }

    @Test
    public void memberWithNoPropertiesReturnsEmptyMap() {
        Member member = stubMember(namedList(), new HashMap<>());
        Map<String, String> out = MemberPropertyExtractor.extract(member);
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    /* ------------------------ test helpers ------------------------ */

    /**
     * {@link NamedListImpl} requires its elements to implement {@link Named}, but olap4j's
     * {@link Property} interface doesn't — {@code property()} below proxies both so its stubs
     * satisfy that bound.
     */
    private interface NamedProperty extends Property, Named {}

    private static NamedList<Property> namedList(Property... properties) {
        NamedList<NamedProperty> nl = new NamedListImpl<>();
        for (Property p : properties) {
            nl.add((NamedProperty) p);
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        NamedList<Property> result = (NamedList) nl;
        return result;
    }

    private static Property property(String name) {
        return (Property) Proxy.newProxyInstance(
                MemberPropertyExtractorTest.class.getClassLoader(),
                new Class<?>[] {NamedProperty.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        // Proxy instances don't inherit Object's defaults — every method,
                        // including hashCode/equals, is routed through invoke(). The tests use
                        // these as HashMap keys (identity semantics), so both must be handled
                        // explicitly or hashCode()'s unboxing of a null return NPEs.
                        if ("getName".equals(method.getName())) return name;
                        if ("toString".equals(method.getName())) return name;
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        return null;
                    }
                });
    }

    private static Member stubMember(NamedList<Property> properties, Map<Property, Object> values) {
        return (Member) Proxy.newProxyInstance(
                MemberPropertyExtractorTest.class.getClassLoader(),
                new Class<?>[] {Member.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getProperties".equals(name)) return properties;
                        if ("getPropertyValue".equals(name) && args != null && args.length == 1) {
                            return values.get(args[0]);
                        }
                        return null;
                    }
                });
    }
}
