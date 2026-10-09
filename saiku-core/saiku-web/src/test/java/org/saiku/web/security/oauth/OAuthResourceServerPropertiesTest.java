/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.oauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Properties;
import org.junit.Test;

public class OAuthResourceServerPropertiesTest {

    @Test
    public void disabled_when_issuer_not_set() {
        OAuthResourceServerProperties props = OAuthResourceServerProperties.from(new Properties());
        assertFalse(props.isEnabled());
        assertNull(props.getIssuerUri());
        assertEquals(OAuthResourceServerProperties.DEFAULT_ROLE_CLAIM, props.getRoleClaim());
        assertTrue(props.getRoleMapping().isEmpty());
    }

    @Test
    public void enabled_when_issuer_set() {
        Properties raw = new Properties();
        raw.setProperty(OAuthResourceServerProperties.PROP_ISSUER_URI, "https://idp.example.com/realms/saiku");
        OAuthResourceServerProperties props = OAuthResourceServerProperties.from(raw);
        assertTrue(props.isEnabled());
        assertEquals("https://idp.example.com/realms/saiku", props.getIssuerUri());
    }

    @Test
    public void blank_issuer_treated_as_unset() {
        Properties raw = new Properties();
        raw.setProperty(OAuthResourceServerProperties.PROP_ISSUER_URI, "   ");
        OAuthResourceServerProperties props = OAuthResourceServerProperties.from(raw);
        assertFalse(props.isEnabled());
    }

    @Test
    public void reads_audience_resource_and_role_claim() {
        Properties raw = new Properties();
        raw.setProperty(OAuthResourceServerProperties.PROP_ISSUER_URI, "https://idp.example.com");
        raw.setProperty(OAuthResourceServerProperties.PROP_AUDIENCE, "saiku-mcp");
        raw.setProperty(OAuthResourceServerProperties.PROP_RESOURCE, "https://saiku.example.com/rest/saiku/api/mcp");
        raw.setProperty(OAuthResourceServerProperties.PROP_ROLE_CLAIM, "realm_access.roles");
        OAuthResourceServerProperties props = OAuthResourceServerProperties.from(raw);
        assertEquals("saiku-mcp", props.getAudience());
        assertEquals("https://saiku.example.com/rest/saiku/api/mcp", props.getResource());
        assertEquals("realm_access.roles", props.getRoleClaim());
    }

    @Test
    public void collects_role_mapping_entries_by_prefix() {
        Properties raw = new Properties();
        raw.setProperty(OAuthResourceServerProperties.PROP_ISSUER_URI, "https://idp.example.com");
        raw.setProperty(OAuthResourceServerProperties.PROP_ROLE_MAPPING_PREFIX + "analyst-usa", "ROLE_USA_ANALYST");
        raw.setProperty(OAuthResourceServerProperties.PROP_ROLE_MAPPING_PREFIX + "administrator", "saiku-admin");
        raw.setProperty("saiku.oauth.roleMapping", "not-an-entry");
        OAuthResourceServerProperties props = OAuthResourceServerProperties.from(raw);
        assertEquals(2, props.getRoleMapping().size());
        assertEquals("ROLE_USA_ANALYST", props.getRoleMapping().get("analyst-usa"));
        assertEquals("saiku-admin", props.getRoleMapping().get("administrator"));
    }
}
