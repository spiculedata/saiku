/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
        System.clearProperty("saiku.version");
    }

    /* ------------------------------ DXT bundle ------------------------------ */

    @Test
    public void mcpDxt_returns404WhenMcpNotConfigured() {
        Response resp = new InfoResource().getMcpDxt();
        assertEquals(404, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("NOT_FOUND", body.get("status"));
        assertTrue(((String) body.get("error")).contains("saiku.mcp.url"));
    }

    @Test
    public void mcpDxt_returnsZipWithManifestJsonWhenMcpConfigured() throws Exception {
        System.setProperty("saiku.mcp.url", "https://demo.saiku.bi/mcp");
        System.setProperty("saiku.version", "4.2.0");

        Response resp = new InfoResource().getMcpDxt();
        assertEquals(200, resp.getStatus());
        assertEquals("attachment; filename=\"saiku.dxt\"", resp.getHeaderString("Content-Disposition"));
        byte[] zip = (byte[]) resp.getEntity();
        assertTrue("zip body must be non-empty", zip.length > 0);

        // The bundle must contain a single manifest.json entry.
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry = zin.getNextEntry();
            assertNotNull("zip must have at least one entry", entry);
            assertEquals("manifest.json", entry.getName());

            byte[] body = zin.readAllBytes();
            JsonNode manifest = new ObjectMapper().readTree(body);

            assertEquals("0.1", manifest.path("dxt_version").asText());
            assertEquals("saiku", manifest.path("name").asText());
            assertEquals("4.2.0", manifest.path("version").asText());
            assertEquals(
                    "https://demo.saiku.bi/mcp",
                    manifest.path("server").path("transport").path("url").asText());
            assertEquals(
                    "streamable-http",
                    manifest.path("server").path("transport").path("type").asText());

            // No further entries — keep the bundle minimal.
            assertNull("only one entry expected", zin.getNextEntry());
        }
    }

    @Test
    public void mcpDxt_includesHostnameInDisplayName() {
        System.setProperty("saiku.mcp.url", "https://demo.saiku.bi/mcp");
        InfoResource r = new InfoResource();
        Map<String, Object> manifest = r.buildDxtManifest("https://demo.saiku.bi/mcp", "demo.saiku.bi");
        assertTrue(
                "display_name should mention the host so the install dialog is unambiguous",
                manifest.get("display_name").toString().contains("demo.saiku.bi"));
    }

    @Test
    public void zipManifest_isAValidZipArchive() throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("dxt_version", "0.1");
        manifest.put("name", "saiku");
        byte[] zip = InfoResource.zipManifest(manifest);
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e = zin.getNextEntry();
            assertNotNull(e);
            JsonNode body = new ObjectMapper().readTree(zin.readAllBytes());
            assertEquals("saiku", body.path("name").asText());
        }
    }

    @Test
    public void mcpDxt_versionFallsBackTo000WhenUnset() throws Exception {
        System.setProperty("saiku.mcp.url", "https://demo.saiku.bi/mcp");
        // Deliberately no saiku.version.
        Response resp = new InfoResource().getMcpDxt();
        byte[] zip = (byte[]) resp.getEntity();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            zin.getNextEntry();
            JsonNode m = new ObjectMapper().readTree(zin.readAllBytes());
            assertEquals("0.0.0", m.path("version").asText());
        }
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
