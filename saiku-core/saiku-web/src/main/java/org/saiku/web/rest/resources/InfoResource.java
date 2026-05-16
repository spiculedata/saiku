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
            zipBytes = zipBundle(manifest, dxtShim(mcpUrl));
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

    /**
     * Build the DXT manifest payload. Package-visible for unit tests.
     *
     * <p>Uses the node-shim form rather than the newer {@code type: "remote"}
     * shape: Claude Desktop's manifest validator (as of the 2026-05 release
     * train Tom tested against) only accepts {@code type} in
     * {@code python | node | binary}, and rejects {@code remote} outright
     * even though the spec documents it. The shim is a one-line Node
     * script (see {@link #dxtShim}) that spawns {@code npx mcp-remote}
     * pointed at this Saiku's URL — gives us a streamable-http bridge
     * without forcing a real Node MCP server into the bundle.
     */
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
        server.put("type", "node");
        server.put("entry_point", "server.js");
        Map<String, Object> mcpConfig = new LinkedHashMap<>();
        mcpConfig.put("command", "node");
        mcpConfig.put("args", List.of("${__dirname}/server.js"));
        server.put("mcp_config", mcpConfig);
        manifest.put("server", server);
        return manifest;
    }

    /**
     * Node shim shipped inside the DXT zip as {@code server.js}: a
     * self-contained stdio↔streamable-http MCP bridge that uses only
     * Node stdlib (no npm deps). Runs under Claude Desktop's built-in
     * Node. Earlier revisions delegated to {@code npx mcp-remote}, but
     * Claude Desktop's node sandbox doesn't have {@code npx} on the
     * PATH — the spawn failed silently and the host reported only
     * "server disconnected". The URL is JSON-encoded so embedded quotes
     * or unicode survive the round-trip into JavaScript source.
     * Package-visible for unit tests.
     */
    static String dxtShim(String mcpUrl) {
        String urlLiteral;
        try {
            urlLiteral = new ObjectMapper().writeValueAsString(mcpUrl);
        } catch (IOException e) {
            // Encoding a String can't actually throw, but the API forces
            // us to handle it. Fall back to an empty string and let the
            // bridge fail loudly at runtime.
            urlLiteral = "\"\"";
        }
        return "#!/usr/bin/env node\n"
                + "// Saiku DXT shim — generated by /rest/saiku/info/mcp.dxt.\n"
                + "// Self-contained stdio↔streamable-http MCP bridge. No npm deps.\n"
                + "// Survives mid-session server cycles: when the remote loses\n"
                + "// our streamable-http session (operator restarted mcp-proxy,\n"
                + "// container recreated, etc.) we transparently re-handshake\n"
                + "// and replay the in-flight request so the client never sees\n"
                + "// the dropped session.\n"
                + "const https = require('https');\n"
                + "const http = require('http');\n"
                + "const { URL } = require('url');\n"
                + "const url = new URL("
                + urlLiteral
                + ");\n"
                + "const lib = url.protocol === 'https:' ? https : http;\n"
                + "let sessionId = null;\n"
                + "function post(body) {\n"
                + "  return new Promise((resolve, reject) => {\n"
                + "    const headers = {\n"
                + "      'Content-Type': 'application/json',\n"
                + "      'Accept': 'application/json, text/event-stream'\n"
                + "    };\n"
                + "    if (sessionId) headers['mcp-session-id'] = sessionId;\n"
                + "    const req = lib.request({\n"
                + "      hostname: url.hostname,\n"
                + "      port: url.port || (url.protocol === 'https:' ? 443 : 80),\n"
                + "      path: url.pathname + url.search,\n"
                + "      method: 'POST',\n"
                + "      headers\n"
                + "    }, (res) => {\n"
                + "      // Always honour the latest session id — covers the\n"
                + "      // initial handshake AND any reissue the server hands\n"
                + "      // us when our cached one is no longer valid.\n"
                + "      const sid = res.headers['mcp-session-id'];\n"
                + "      if (sid) sessionId = sid;\n"
                + "      let data = '';\n"
                + "      res.on('data', (c) => { data += c.toString(); });\n"
                + "      res.on('end', () => resolve({\n"
                + "        status: res.statusCode,\n"
                + "        body: data,\n"
                + "        contentType: res.headers['content-type'] || ''\n"
                + "      }));\n"
                + "    });\n"
                + "    req.on('error', reject);\n"
                + "    req.write(body);\n"
                + "    req.end();\n"
                + "  });\n"
                + "}\n"
                + "function isSessionLoss(r) {\n"
                + "  if (r.status === 404) return true;\n"
                + "  const txt = r.body || '';\n"
                + "  if (r.status === 400 && /session/i.test(txt)) return true;\n"
                + "  try {\n"
                + "    const p = JSON.parse(txt);\n"
                + "    const msg = p && p.error && p.error.message;\n"
                + "    if (msg && /session/i.test(msg)) return true;\n"
                + "  } catch (_) {}\n"
                + "  return false;\n"
                + "}\n"
                + "async function rehandshake() {\n"
                + "  sessionId = null;\n"
                + "  await post(JSON.stringify({\n"
                + "    jsonrpc: '2.0', id: 0, method: 'initialize',\n"
                + "    params: {\n"
                + "      protocolVersion: '2024-11-05',\n"
                + "      capabilities: {},\n"
                + "      clientInfo: { name: 'saiku-shim', version: '1' }\n"
                + "    }\n"
                + "  }));\n"
                + "  await post(JSON.stringify({\n"
                + "    jsonrpc: '2.0', method: 'notifications/initialized'\n"
                + "  }));\n"
                + "}\n"
                + "function parseMethod(line) {\n"
                + "  try { return (JSON.parse(line) || {}).method || null; }\n"
                + "  catch (_) { return null; }\n"
                + "}\n"
                + "async function handleLine(line) {\n"
                + "  const method = parseMethod(line);\n"
                + "  // A client-driven initialize should start an entirely new\n"
                + "  // server session — drop the cached id so we don't replay a\n"
                + "  // stale one in the header.\n"
                + "  if (method === 'initialize') sessionId = null;\n"
                + "  let r = await post(line);\n"
                + "  if (method && method !== 'initialize' && isSessionLoss(r)) {\n"
                + "    process.stderr.write('[saiku-shim] session lost — re-handshaking and replaying ' + method + '\\n');\n"
                + "    try {\n"
                + "      await rehandshake();\n"
                + "      r = await post(line);\n"
                + "    } catch (e) {\n"
                + "      process.stderr.write('[saiku-shim] re-handshake failed: ' + (e.message || e) + '\\n');\n"
                + "    }\n"
                + "  }\n"
                + "  if (r.contentType.indexOf('text/event-stream') !== -1) {\n"
                + "    for (const ln of r.body.split('\\n')) {\n"
                + "      if (ln.indexOf('data:') === 0) {\n"
                + "        const d = ln.slice(5).trim();\n"
                + "        if (d) process.stdout.write(d + '\\n');\n"
                + "      }\n"
                + "    }\n"
                + "  } else if (r.body && r.body.trim()) {\n"
                + "    process.stdout.write(r.body.trim() + '\\n');\n"
                + "  }\n"
                + "}\n"
                + "let buf = '';\n"
                + "process.stdin.setEncoding('utf8');\n"
                + "process.stdin.on('data', async (chunk) => {\n"
                + "  buf += chunk;\n"
                + "  let nl;\n"
                + "  while ((nl = buf.indexOf('\\n')) !== -1) {\n"
                + "    const line = buf.slice(0, nl).trim();\n"
                + "    buf = buf.slice(nl + 1);\n"
                + "    if (!line) continue;\n"
                + "    try {\n"
                + "      await handleLine(line);\n"
                + "    } catch (e) {\n"
                + "      process.stderr.write('[saiku-shim] ' + (e.message || e) + '\\n');\n"
                + "    }\n"
                + "  }\n"
                + "});\n"
                + "process.stdin.on('end', () => process.exit(0));\n";
    }

    /**
     * Zip a manifest map plus the Node shim into a two-entry DXT bundle
     * ({@code manifest.json} + {@code server.js}). Package-visible for
     * unit tests.
     */
    static byte[] zipBundle(Map<String, Object> manifest, String shimJs) throws IOException {
        byte[] json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buf)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(json);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("server.js"));
            zip.write(shimJs.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
