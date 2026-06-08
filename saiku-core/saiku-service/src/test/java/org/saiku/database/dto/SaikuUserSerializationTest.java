/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.database.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

/**
 * saiku#1165 — the admin user-management endpoints serialise {@link SaikuUser}
 * back to the client. Its {@code password} field (a {bcrypt} hash loaded from
 * the DB) must never leave the server, but must still be accepted inbound on
 * create/update. {@code @JsonProperty(access = WRITE_ONLY)} enforces both.
 */
public class SaikuUserSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void passwordIsNeverSerialisedOut() throws Exception {
        SaikuUser u = new SaikuUser();
        u.setUsername("admin");
        u.setEmail("admin@example.com");
        u.setRoles(new String[] {"ROLE_ADMIN"});
        u.setPassword("{bcrypt}$2a$10$4lJwsvvv..UqK31JmBSoCOf7hYgKurOB2sEacVVIrqA97BXc4cFju");

        String json = mapper.writeValueAsString(u);

        assertFalse("the 'password' key must not appear in the response: " + json, json.contains("password"));
        assertFalse("the bcrypt hash must not leak: " + json, json.contains("bcrypt"));
        assertTrue("non-secret fields must still serialise", json.contains("admin"));
        assertTrue("roles must still serialise", json.contains("ROLE_ADMIN"));
    }

    @Test
    public void passwordIsStillAcceptedInbound() throws Exception {
        // create/update must still be able to set a password from the request body.
        String json = "{\"username\":\"bob\",\"password\":\"newSecret\",\"roles\":[\"ROLE_USER\"]}";
        SaikuUser u = mapper.readValue(json, SaikuUser.class);
        assertEquals("newSecret", u.getPassword());
        assertEquals("bob", u.getUsername());
    }
}
