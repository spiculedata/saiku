/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.*;

import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.saiku.service.PlatformUtilsService;
import org.saiku.service.util.dto.Plugin;

public class InfoResourceTest {

    @Test
    public void getAvailablePlugins_returns200WithPluginList() {
        PlatformUtilsService stub = new PlatformUtilsService() {
            @Override
            public ArrayList<Plugin> getAvailablePlugins() {
                ArrayList<Plugin> out = new ArrayList<>();
                out.add(new Plugin("alpha", "", "js/saiku/plugins/alpha/plugin.js"));
                return out;
            }
        };
        InfoResource r = new InfoResource();
        r.setPlatformUtilsService(stub);

        Response resp = r.getAvailablePlugins();
        assertEquals(200, resp.getStatus());
        // Response.ok(GenericEntity) unwraps the generic, so the entity is the
        // raw List. The GenericEntity wrapper is only used to carry the type.
        @SuppressWarnings("unchecked")
        List<Plugin> body = (List<Plugin>) resp.getEntity();
        assertEquals(1, body.size());
        assertEquals("alpha", body.get(0).getName());
    }

    @After
    public void clearCapabilityProps() {
        System.clearProperty("saiku.demo");
        System.clearProperty("saiku.mcp.url");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void capabilities_defaultDeployment_mcpDisabledDemoOff() {
        Response resp = new InfoResource().getCapabilities();
        assertEquals(200, resp.getStatus());
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        Map<String, Object> ai = (Map<String, Object>) body.get("ai");
        assertEquals(Boolean.TRUE, ai.get("enabled"));
        assertEquals("/rest/saiku/api/ai", ai.get("basePath"));
        Map<String, Object> mcp = (Map<String, Object>) body.get("mcp");
        assertEquals(Boolean.FALSE, mcp.get("enabled"));
        assertEquals(Boolean.FALSE, body.get("demoMode"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void capabilities_demoModeAndMcpUrl_surfaceCorrectly() {
        System.setProperty("saiku.demo", "true");
        System.setProperty("saiku.mcp.url", "https://demo.saiku.bi/mcp");

        Response resp = new InfoResource().getCapabilities();
        assertEquals(200, resp.getStatus());
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals(Boolean.TRUE, body.get("demoMode"));
        Map<String, Object> mcp = (Map<String, Object>) body.get("mcp");
        assertEquals(Boolean.TRUE, mcp.get("enabled"));
        assertEquals("https://demo.saiku.bi/mcp", mcp.get("url"));
        assertEquals("streamable-http", mcp.get("transport"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void capabilities_blankMcpUrl_treatedAsDisabled() {
        System.setProperty("saiku.mcp.url", "   ");
        Response resp = new InfoResource().getCapabilities();
        assertEquals(200, resp.getStatus());
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        Map<String, Object> mcp = (Map<String, Object>) body.get("mcp");
        assertEquals(Boolean.FALSE, mcp.get("enabled"));
        assertFalse("blank URL must not surface a url field", mcp.containsKey("url"));
    }

    @Test
    public void getAvailablePlugins_emptyListStillReturns200() {
        PlatformUtilsService stub = new PlatformUtilsService() {
            @Override
            public ArrayList<Plugin> getAvailablePlugins() {
                return new ArrayList<>();
            }
        };
        InfoResource r = new InfoResource();
        r.setPlatformUtilsService(stub);

        Response resp = r.getAvailablePlugins();
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        List<Plugin> body = (List<Plugin>) resp.getEntity();
        assertTrue(body.isEmpty());
    }
}
