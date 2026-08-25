/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class PiiFilterTest {

    private static Map<String, List<String>> samples() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("customers.ssn", Arrays.asList("123-45-6789", "987-65-4321"));
        m.put("customers.password", Arrays.asList("hunter2", "letmein"));
        m.put("customers.email_address", Arrays.asList("a@x.com", "b@y.com"));
        m.put("customers.customer_name", Arrays.asList("Alice", "Bob"));
        m.put("customers.id", Arrays.asList("1", "2"));
        return m;
    }

    @Test
    public void removesStandardPiiColumns() {
        PiiFilter filter = new PiiFilter();
        Map<String, List<String>> out = filter.filter(samples());

        assertFalse("ssn must be dropped", out.containsKey("customers.ssn"));
        assertFalse("password must be dropped", out.containsKey("customers.password"));
        assertFalse("email_address must be dropped", out.containsKey("customers.email_address"));
        assertTrue("customer_name must be kept", out.containsKey("customers.customer_name"));
        assertTrue("id must be kept", out.containsKey("customers.id"));
        assertEquals(2, out.size());
    }

    @Test
    public void matchIsCaseInsensitive() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("CUSTOMERS.SSN", Arrays.asList("x"));
        m.put("CUSTOMERS.Password", Arrays.asList("x"));
        m.put("customers.CUSTOMER_NAME", Arrays.asList("x"));

        Map<String, List<String>> out = new PiiFilter().filter(m);
        assertFalse(out.containsKey("CUSTOMERS.SSN"));
        assertFalse(out.containsKey("CUSTOMERS.Password"));
        assertTrue(out.containsKey("customers.CUSTOMER_NAME"));
    }

    @Test
    public void wildcardSuffixMatchesEmailColumns() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("users.contact_email", Arrays.asList("a@x.com"));
        m.put("users.work_email", Arrays.asList("a@x.com"));
        m.put("users.name", Arrays.asList("Alice"));

        Map<String, List<String>> out = new PiiFilter().filter(m);
        assertFalse(out.containsKey("users.contact_email"));
        assertFalse(out.containsKey("users.work_email"));
        assertTrue(out.containsKey("users.name"));
    }

    @Test
    public void phoneAndCreditCardAndTokenDropped() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("t.phone_number", Arrays.asList("x"));
        m.put("t.credit_card", Arrays.asList("x"));
        m.put("t.auth_token", Arrays.asList("x"));
        m.put("t.cvv", Arrays.asList("x"));
        m.put("t.ip_address", Arrays.asList("x"));
        m.put("t.age", Arrays.asList("x"));

        Map<String, List<String>> out = new PiiFilter().filter(m);
        assertEquals(1, out.size());
        assertTrue(out.containsKey("t.age"));
    }

    @Test
    public void extraPatternsFromConstructorAreApplied() {
        PiiFilter filter = new PiiFilter(Set.of("tax_id"));
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("t.tax_id", Arrays.asList("x"));
        m.put("t.name", Arrays.asList("x"));

        Map<String, List<String>> out = filter.filter(m);
        assertFalse(out.containsKey("t.tax_id"));
        assertTrue(out.containsKey("t.name"));
    }

    @Test
    public void keyWithoutDotIsTreatedAsColumnName() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("ssn", Arrays.asList("x"));
        m.put("name", Arrays.asList("x"));

        Map<String, List<String>> out = new PiiFilter().filter(m);
        assertFalse(out.containsKey("ssn"));
        assertTrue(out.containsKey("name"));
    }

    @Test
    public void nullOrEmptyReturnsEmpty() {
        assertTrue(new PiiFilter().filter(null).isEmpty());
        assertTrue(new PiiFilter().filter(Map.of()).isEmpty());
    }
}
