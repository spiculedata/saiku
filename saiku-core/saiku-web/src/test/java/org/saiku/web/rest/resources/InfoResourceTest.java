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
import java.util.HashMap;
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
        Response resp = new InfoResource().getMcpDxt(null);
        assertEquals(404, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("NOT_FOUND", body.get("status"));
        assertTrue(((String) body.get("error")).contains("saiku.mcp.url"));
    }

    @Test
    public void mcpDxt_returnsZipWithManifestAndShimWhenMcpConfigured() throws Exception {
        System.setProperty("saiku.mcp.url", "https://demo.saiku.bi/rest/saiku/api/mcp");
        System.setProperty("saiku.version", "4.2.0");

        Response resp = new InfoResource().getMcpDxt(null);
        assertEquals(200, resp.getStatus());
        assertEquals("attachment; filename=\"saiku.dxt\"", resp.getHeaderString("Content-Disposition"));
        byte[] zip = (byte[]) resp.getEntity();
        assertTrue("zip body must be non-empty", zip.length > 0);

        // The bundle must contain manifest.json + server.js entries.
        Map<String, byte[]> entries = readZip(zip);
        assertTrue("manifest.json present", entries.containsKey("manifest.json"));
        assertTrue("server.js shim present", entries.containsKey("server.js"));

        JsonNode manifest = new ObjectMapper().readTree(entries.get("manifest.json"));
        assertEquals("0.1", manifest.path("dxt_version").asText());
        assertEquals("saiku", manifest.path("name").asText());
        assertEquals("4.2.0", manifest.path("version").asText());
        // saiku#878: manifest must surface user_config so the host prompts
        // for credentials at install time.
        assertTrue("username field present", manifest.path("user_config").has("username"));
        assertTrue("password field present", manifest.path("user_config").has("password"));
        assertTrue(
                "password is marked sensitive so the host stores it securely",
                manifest.path("user_config").path("password").path("sensitive").asBoolean());
        // saiku#878: env block threads the user_config values into the
        // shim's process environment.
        JsonNode env = manifest.path("server").path("mcp_config").path("env");
        assertEquals("${user_config.username}", env.path("SAIKU_USER").asText());
        assertEquals("${user_config.password}", env.path("SAIKU_PASS").asText());

        String shim = new String(entries.get("server.js"), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("shim should reference the configured MCP URL", shim.contains("demo.saiku.bi"));
        assertTrue(
                "shim should compute Basic auth from SAIKU_USER / SAIKU_PASS env",
                shim.contains("SAIKU_USER") && shim.contains("SAIKU_PASS"));
        assertTrue("shim should attach the Authorization header to outgoing posts", shim.contains("Authorization"));
        assertTrue("shim should propagate the streamable-http session id", shim.contains("mcp-session-id"));
    }

    @Test
    public void mcpDxt_includesHostnameInDisplayName() {
        System.setProperty("saiku.mcp.url", "https://demo.saiku.bi/rest/saiku/api/mcp");
        InfoResource r = new InfoResource();
        Map<String, Object> manifest = r.buildDxtManifest("https://demo.saiku.bi/rest/saiku/api/mcp", "demo.saiku.bi");
        assertTrue(
                "display_name should mention the host so the install dialog is unambiguous",
                manifest.get("display_name").toString().contains("demo.saiku.bi"));
    }

    @Test
    public void zipBundle_isAValidTwoEntryArchive() throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("dxt_version", "0.1");
        manifest.put("name", "saiku");
        byte[] zip = InfoResource.zipBundle(manifest, "#!/usr/bin/env node\nconsole.log('hi');\n");
        Map<String, byte[]> entries = readZip(zip);
        assertEquals(2, entries.size());
        JsonNode body = new ObjectMapper().readTree(entries.get("manifest.json"));
        assertEquals("saiku", body.path("name").asText());
        assertTrue(new String(entries.get("server.js"), java.nio.charset.StandardCharsets.UTF_8)
                .startsWith("#!/usr/bin/env node"));
    }

    @Test
    public void mcpDxt_versionFallsBackTo000WhenUnset() throws Exception {
        System.setProperty("saiku.mcp.url", "https://demo.saiku.bi/rest/saiku/api/mcp");
        // Deliberately no saiku.version.
        Response resp = new InfoResource().getMcpDxt(null);
        Map<String, byte[]> entries = readZip((byte[]) resp.getEntity());
        JsonNode m = new ObjectMapper().readTree(entries.get("manifest.json"));
        assertEquals("0.0.0", m.path("version").asText());
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
    }

    @Test
    @SuppressWarnings("unchecked")
    public void capabilities_defaultDeployment_mcpDisabledDemoOff() {
        Response resp = new InfoResource().getCapabilities(null);
        assertEquals(200, resp.getStatus());
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        Map<String, Object> ai = (Map<String, Object>) body.get("ai");
        assertEquals(Boolean.TRUE, ai.get("enabled"));
        assertEquals("/rest/saiku/api/ai", ai.get("basePath"));
        Map<String, Object> mcp = (Map<String, Object>) body.get("mcp");
        // Without a -Dsaiku.mcp.url override AND without a UriInfo to derive
        // from, MCP reports disabled.
        assertEquals(Boolean.FALSE, mcp.get("enabled"));
        assertEquals(Boolean.FALSE, body.get("demoMode"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void capabilities_demoModeAndMcpUrl_surfaceCorrectly() {
        System.setProperty("saiku.demo", "true");
        System.setProperty("saiku.mcp.url", "https://demo.saiku.bi/rest/saiku/api/mcp");

        Response resp = new InfoResource().getCapabilities(null);
        assertEquals(200, resp.getStatus());
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals(Boolean.TRUE, body.get("demoMode"));
        Map<String, Object> mcp = (Map<String, Object>) body.get("mcp");
        assertEquals(Boolean.TRUE, mcp.get("enabled"));
        assertEquals("https://demo.saiku.bi/rest/saiku/api/mcp", mcp.get("url"));
        assertEquals("streamable-http", mcp.get("transport"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void capabilities_blankMcpUrl_treatedAsDisabled() {
        System.setProperty("saiku.mcp.url", "   ");
        Response resp = new InfoResource().getCapabilities(null);
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

    /* ----------------------------- helpers ------------------------------ */

    /** Read every entry of a zip into a name → bytes map. Order-preserving via LinkedHashMap. */
    private static Map<String, byte[]> readZip(byte[] zipBytes) throws java.io.IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                out.put(e.getName(), zin.readAllBytes());
            }
        }
        return out;
    }
}
