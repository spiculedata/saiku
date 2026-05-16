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
    public void mcpDxt_returnsZipWithManifestAndShimWhenMcpConfigured() throws Exception {
        System.setProperty("saiku.mcp.url", "https://demo.saiku.bi/mcp");
        System.setProperty("saiku.version", "4.2.0");

        Response resp = new InfoResource().getMcpDxt();
        assertEquals(200, resp.getStatus());
        assertEquals("attachment; filename=\"saiku.dxt\"", resp.getHeaderString("Content-Disposition"));
        byte[] zip = (byte[]) resp.getEntity();
        assertTrue("zip body must be non-empty", zip.length > 0);

        // Drain the zip into a name→bytes map so assertions don't depend
        // on entry order.
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries.put(entry.getName(), zin.readAllBytes());
            }
        }
        assertTrue("manifest.json must be present", entries.containsKey("manifest.json"));
        assertTrue("server.js shim must be present", entries.containsKey("server.js"));
        assertEquals("bundle should contain exactly manifest + shim", 2, entries.size());

        JsonNode manifest = new ObjectMapper().readTree(entries.get("manifest.json"));
        assertEquals("0.1", manifest.path("dxt_version").asText());
        assertEquals("saiku", manifest.path("name").asText());
        assertEquals("4.2.0", manifest.path("version").asText());

        // Node-shim shape: Claude Desktop's validator only accepts
        // python|node|binary for server.type. The shim runs via
        // node ${__dirname}/server.js and spawns mcp-remote internally.
        assertEquals("node", manifest.path("server").path("type").asText());
        assertEquals("server.js", manifest.path("server").path("entry_point").asText());
        assertEquals(
                "node",
                manifest.path("server").path("mcp_config").path("command").asText());
        JsonNode args = manifest.path("server").path("mcp_config").path("args");
        assertTrue("args must include a ${__dirname}/server.js entry", args.isArray() && args.size() >= 1);
        assertEquals("${__dirname}/server.js", args.get(0).asText());

        String shim = new String(entries.get("server.js"), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("shim should reference the configured MCP URL", shim.contains("https://demo.saiku.bi/mcp"));
        assertTrue("shim should be a self-contained bridge using node stdlib", shim.contains("require('https')"));
        assertTrue("shim should propagate the streamable-http session id", shim.contains("mcp-session-id"));
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
    public void zipBundle_isAValidZipArchive() throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("dxt_version", "0.1");
        manifest.put("name", "saiku");
        String shim = "console.log('shim');";
        byte[] zip = InfoResource.zipBundle(manifest, shim);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                entries.put(e.getName(), zin.readAllBytes());
            }
        }
        JsonNode body = new ObjectMapper().readTree(entries.get("manifest.json"));
        assertEquals("saiku", body.path("name").asText());
        assertEquals("console.log('shim');", new String(entries.get("server.js")));
    }

    @Test
    public void dxtShim_jsonEncodesUrlAndBridgesStdioToHttp() {
        String shim = InfoResource.dxtShim("https://demo.saiku.bi/mcp?token=ab\"cd");
        // URL must be safely JSON-encoded so the embedded quote doesn't
        // break out of the JS string literal that gets passed to new URL().
        assertTrue(
                "shim should embed the JSON-encoded URL",
                shim.contains("\"https://demo.saiku.bi/mcp?token=ab\\\"cd\""));
        // Self-contained bridge contract: reads stdin, POSTs to /mcp,
        // threads the mcp-session-id header on subsequent requests.
        assertTrue("shim should read from stdin", shim.contains("process.stdin"));
        assertTrue("shim should write to stdout", shim.contains("process.stdout"));
        assertTrue("shim should use the streamable-http session header", shim.contains("mcp-session-id"));
        assertTrue("shim should propagate exit code", shim.contains("process.exit"));
    }

    @Test
    public void mcpDxt_versionFallsBackTo000WhenUnset() throws Exception {
        System.setProperty("saiku.mcp.url", "https://demo.saiku.bi/mcp");
        // Deliberately no saiku.version.
        Response resp = new InfoResource().getMcpDxt();
        byte[] zip = (byte[]) resp.getEntity();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if ("manifest.json".equals(e.getName())) {
                    JsonNode m = new ObjectMapper().readTree(zin.readAllBytes());
                    assertEquals("0.0.0", m.path("version").asText());
                    return;
                }
            }
            throw new AssertionError("manifest.json not found in bundle");
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
