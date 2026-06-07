/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

/**
 * saiku#1165 — the datasource backend password must never be serialised back to
 * a client (it was leaking via GET .../org.saiku.datasources/{id}), but must
 * still be accepted inbound on create/update. {@code @JsonProperty(WRITE_ONLY)}
 * enforces both.
 */
public class DataSourceMapperSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void passwordIsNeverSerialisedOut() throws Exception {
        DataSourceMapper m = new DataSourceMapper();
        m.setConnectionname("foodmart");
        m.setUsername("dbuser");
        m.setJdbcurl("jdbc:postgresql://db/foodmart");
        m.setPassword("super-secret-db-pw");

        String json = mapper.writeValueAsString(m);

        assertFalse("'password' key must not appear in the response: " + json, json.contains("password"));
        assertFalse("the secret value must not leak: " + json, json.contains("super-secret-db-pw"));
        assertTrue("non-secret fields still serialise", json.contains("foodmart"));
        assertTrue("username still serialises", json.contains("dbuser"));
    }

    @Test
    public void passwordIsStillAcceptedInbound() throws Exception {
        String json = "{\"connectionname\":\"ds\",\"username\":\"u\",\"password\":\"inbound-pw\"}";
        DataSourceMapper m = mapper.readValue(json, DataSourceMapper.class);
        assertEquals("inbound-pw", m.getPassword());
        assertEquals("ds", m.getConnectionname());
    }
}
