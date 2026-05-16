/*
 *   Copyright 2012 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.web.rest.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qmino.miredot.annotations.ReturnType;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.saiku.service.PlatformUtilsService;
import org.saiku.service.util.dto.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Info Resource to get platform information.
 */
@Path("/saiku/info")
@XmlAccessorType(XmlAccessType.NONE)
public class InfoResource {

    private static final Logger log = LoggerFactory.getLogger(InfoResource.class);

    private PlatformUtilsService platformService;

    // @Autowired
    public void setPlatformUtilsService(PlatformUtilsService ps) {
        this.platformService = ps;
    }

    /**
     * Get a list of available plugins.
     * @summary Get plugins
     * @return A response containing a list of plugins.
     */
    @GET
    @Produces({"application/json"})
    @ReturnType("java.util.List<Plugin>")
    public Response getAvailablePlugins() {

        GenericEntity<List<Plugin>> entity = new GenericEntity<List<Plugin>>(platformService.getAvailablePlugins()) {};
        return Response.ok(entity).build();
    }

    /**
     * Cheap HEAD for monitors / health checks.
     *
     * <p>saiku#866: without this, Jersey's auto-HEAD handler runs the
     * full GET (including the plugin directory walk in
     * {@link org.saiku.service.PlatformUtilsService#getAvailablePlugins()}),
     * then tries to strip the body. On the launcher the plugin dir is
     * often unset and {@code File.list} returns null, leaving the
     * response writer stalled on a Content-Length that never matches.
     * HEAD callers see a 30-second hang followed by a connection reset.
     */
    @HEAD
    public Response head() {
        return Response.ok().build();
    }

    /**
     * Connection-info surface for the admin console + (when demo mode is on)
     * the login page. Reports which agent-facing APIs are reachable on this
     * deployment so the UI can render copy-pasteable connection snippets
     * without each page having to probe the endpoints itself.
     *
     * <p>Driven by system properties so a launcher operator can toggle the
     * panel without rebuilding:
     * <ul>
     *   <li>{@code saiku.demo=true} — flips the {@code demoMode} flag on,
     *       which the SPA reads to expose the same info on the login page.</li>
     *   <li>{@code saiku.mcp.url=https://.../mcp} — public URL of the MCP
     *       endpoint (StreamableHTTP). Absent / blank means MCP is not
     *       exposed (default for vanilla launcher deployments). The
     *       container ships {@code saiku-mcp} but it's stdio-only by
     *       default; setting this property tells the UI an HTTP front-end
     *       is in place (e.g. demo.saiku.bi runs mcp-proxy in front).</li>
     * </ul>
     *
     * <p>The AI Query API is always present in the codebase, so
     * {@code ai.enabled} is always true — the UI still benefits from a
     * machine-readable URL + sample bodies.
     */
    @GET
    @Path("/capabilities")
    @Produces({"application/json"})
    public Response getCapabilities() {
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("enabled", true);
        ai.put("basePath", "/rest/saiku/api/ai");
        ai.put(
                "endpoints",
                Map.of(
                        "cubes", "/rest/saiku/api/ai/cubes",
                        "schema", "/rest/saiku/api/ai/schema/{connection}/{catalog}/{schema}/{cube}",
                        "query", "/rest/saiku/api/ai/query"));

        Map<String, Object> mcp = new LinkedHashMap<>();
        String mcpUrl = System.getProperty("saiku.mcp.url", "");
        if (mcpUrl != null && !mcpUrl.isBlank()) {
            mcp.put("enabled", true);
            mcp.put("url", mcpUrl);
            mcp.put("transport", "streamable-http");
        } else {
            mcp.put("enabled", false);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ai", ai);
        body.put("mcp", mcp);
        body.put("demoMode", Boolean.parseBoolean(System.getProperty("saiku.demo", "false")));
        return Response.ok(body).build();
    }

    /**
     * Build and stream a Claude Desktop / Cursor DXT bundle for the running
     * MCP endpoint so the SPA can offer a "Download .dxt" button on the
     * connection-info panel. The bundle is generated on the fly so the
     * embedded {@code url} matches whatever the operator configured via
     * {@code -Dsaiku.mcp.url=...}; no static asset to keep in sync.
     *
     * <p>A DXT file is a ZIP archive with a {@code manifest.json} at the
     * root describing how the agent host should connect. For a remote /
     * streamable-http MCP server like Saiku's, the manifest carries the
     * URL plus a small amount of human-readable metadata for the install
     * dialog.
     *
     * <p>Returns 404 when MCP isn't enabled (i.e. {@code saiku.mcp.url}
     * is unset or blank). Vanilla launcher deployments ship saiku-mcp
     * as stdio only — no URL means no bundle to hand out.
     */
    @GET
    @Path("/mcp.dxt")
    @Produces("application/octet-stream")
    public Response getMcpDxt() {
        String mcpUrl = System.getProperty("saiku.mcp.url", "");
        if (mcpUrl == null || mcpUrl.isBlank()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of(
                            "status",
                            "NOT_FOUND",
                            "error",
                            "MCP server is not exposed over HTTP on this deployment. "
                                    + "Set -Dsaiku.mcp.url to an HTTPS URL to enable the DXT download."))
                    .build();
        }

        // Derive a friendly origin host for the display_name (e.g. "demo.saiku.bi")
        // so the install dialog tells the user which Saiku they're wiring up.
        String host = mcpUrl;
        try {
            host = URI.create(mcpUrl).getHost();
            if (host == null || host.isBlank()) host = mcpUrl;
        } catch (Exception ignored) {
            // mcpUrl isn't a URI — fall through and let the raw string render.
        }

        Map<String, Object> manifest = buildDxtManifest(mcpUrl, host);
        byte[] zipBytes;
        try {
            zipBytes = zipManifest(manifest);
        } catch (IOException e) {
            log.error("Failed to assemble MCP DXT bundle", e);
            return Response.serverError()
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("status", "ERROR", "error", "Failed to build DXT bundle"))
                    .build();
        }

        return Response.ok(zipBytes)
                .header("Content-Disposition", "attachment; filename=\"saiku.dxt\"")
                .header("Content-Length", String.valueOf(zipBytes.length))
                .type("application/octet-stream")
                .build();
    }

    /** Build the DXT manifest payload. Package-visible for unit tests. */
    Map<String, Object> buildDxtManifest(String mcpUrl, String host) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("dxt_version", "0.1");
        manifest.put("name", "saiku");
        manifest.put("display_name", "Saiku OLAP — " + host);
        manifest.put("version", saikuVersion());
        manifest.put(
                "description",
                "Query Saiku OLAP cubes through MCP. Backed by the AI Query API at "
                        + host
                        + " — typed schema discovery, validated MDX generation, and structured cell results.");
        manifest.put("author", Map.of("name", "Saiku Analytics", "url", "https://saiku.bi"));
        manifest.put("homepage", "https://github.com/spiculedata/saiku");
        manifest.put("license", "Apache-2.0");
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("type", "remote");
        Map<String, Object> transport = new LinkedHashMap<>();
        transport.put("type", "streamable-http");
        transport.put("url", mcpUrl);
        server.put("transport", transport);
        manifest.put("server", server);
        return manifest;
    }

    /** Zip a manifest map into a single-entry {@code manifest.json} archive.
     *  Package-visible for unit tests. */
    static byte[] zipManifest(Map<String, Object> manifest) throws IOException {
        byte[] json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buf)) {
            ZipEntry entry = new ZipEntry("manifest.json");
            zip.putNextEntry(entry);
            zip.write(json);
            zip.closeEntry();
        }
        return buf.toByteArray();
    }

    /**
     * Best-effort Saiku version for the DXT manifest. Reads
     * {@code -Dsaiku.version=...} (the launcher can set this from the
     * shaded JAR's Implementation-Version) and falls back to {@code 0.0.0}
     * so the manifest stays a valid semver string.
     */
    private static String saikuVersion() {
        String v = System.getProperty("saiku.version", "");
        if (v == null || v.isBlank()) return "0.0.0";
        return v;
    }
}
