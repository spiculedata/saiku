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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
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
    private org.saiku.web.demo.DemoGate demoGate;

    // @Autowired
    public void setPlatformUtilsService(PlatformUtilsService ps) {
        this.platformService = ps;
    }

    /**
     * Optional demo email-gate holder (saiku#1029). Null in unit runs / non-demo
     * deployments — the capabilities endpoint then reports {@code emailGate:false}.
     */
    public void setDemoGate(org.saiku.web.demo.DemoGate demoGate) {
        this.demoGate = demoGate;
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
     *       endpoint (StreamableHTTP). Overrides the auto-derived URL when
     *       set; the auto-derived form points at this launcher's own
     *       {@code /rest/saiku/api/mcp} so demo + dev work without
     *       per-env config (saiku#878).</li>
     * </ul>
     *
     * <p>The AI Query API is always present in the codebase, so
     * {@code ai.enabled} is always true — the UI still benefits from a
     * machine-readable URL + sample bodies.
     */
    @GET
    @Path("/capabilities")
    @Produces({"application/json"})
    public Response getCapabilities(@Context UriInfo uriInfo) {
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
        String mcpUrl = resolveMcpUrl(uriInfo);
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
        // saiku#1029: tell the SPA whether the demo email gate is in front of
        // login, and which provider, so the login page can render the gate form.
        boolean emailGate = demoGate != null && demoGate.isEnabled();
        body.put("emailGate", emailGate);
        body.put("emailGateProvider", emailGate ? demoGate.providerName() : null);
        return Response.ok(body).build();
    }

    /**
     * Build and stream a Claude Desktop / Cursor DXT bundle for the running
     * MCP endpoint so the SPA can offer a "Download .dxt" button on the
     * connection-info panel. The bundle is generated on the fly so the
     * embedded {@code url} matches whatever the operator configured via
     * {@code -Dsaiku.mcp.url=...} (or, since saiku#878, the request's
     * own scheme + host when the property is unset).
     *
     * <p>A DXT file is a ZIP archive with a {@code manifest.json} at the
     * root describing how the agent host should connect, plus a
     * {@code server.js} stdio shim that forwards MCP frames to the
     * remote streamable-http endpoint with Basic-auth credentials the
     * user supplied at install time (saiku#878 — per-user creds → per-user
     * Mondrian roles).
     */
    @GET
    @Path("/mcp.dxt")
    @Produces("application/octet-stream")
    public Response getMcpDxt(@Context UriInfo uriInfo) {
        String mcpUrl = resolveMcpUrl(uriInfo);
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
     * Resolve the MCP URL for this launcher. Operator-supplied
     * {@code -Dsaiku.mcp.url} wins (useful when the public URL goes through
     * a reverse proxy with a different hostname); otherwise we derive
     * {@code <scheme>://<host>:<port>/rest/saiku/api/mcp} from the inbound
     * request so demo + dev work without per-env config (saiku#878).
     * Package-visible for unit tests.
     */
    static String resolveMcpUrl(UriInfo uriInfo) {
        String override = System.getProperty("saiku.mcp.url", "");
        if (override != null && !override.isBlank()) return override;
        if (uriInfo == null) return null;
        try {
            URI base = uriInfo.getBaseUri();
            // baseUri is like http://host:port/rest/ — strip everything
            // from /rest onward and rebuild against /rest/saiku/api/mcp.
            String scheme = base.getScheme();
            String host = base.getHost();
            int port = base.getPort();
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(host);
            if (port > 0 && !isDefaultPort(scheme, port)) {
                sb.append(":").append(port);
            }
            sb.append("/rest/saiku/api/mcp");
            return sb.toString();
        } catch (RuntimeException e) {
            log.debug("Failed to derive MCP URL from request — caller can set -Dsaiku.mcp.url to override", e);
            return null;
        }
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return ("http".equalsIgnoreCase(scheme) && port == 80) || ("https".equalsIgnoreCase(scheme) && port == 443);
    }

    /**
     * Build the DXT manifest payload. Package-visible for unit tests.
     *
     * <p>Uses the node-shim form rather than the newer {@code type: "remote"}
     * shape: Claude Desktop's manifest validator (as of the 2026-05 release
     * train) only accepts {@code type} in {@code python | node | binary},
     * and rejects {@code remote} outright even though the spec documents it.
     * The shim is a Node stdio↔streamable-http bridge (see {@link #dxtShim}).
     *
     * <p>saiku#878: {@code user_config} prompts the user for Saiku
     * credentials at install time. Claude Desktop substitutes the values
     * into the {@code mcp_config.env} block, which the shim reads to
     * compute {@code Authorization: Basic} on every request — giving each
     * user their own Mondrian role propagation without any deployment-side
     * secrets.
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

        Map<String, Object> userConfig = new LinkedHashMap<>();
        Map<String, Object> usernameField = new LinkedHashMap<>();
        usernameField.put("type", "string");
        usernameField.put("title", "Saiku username");
        usernameField.put("description", "The Saiku account this DXT will run queries as.");
        usernameField.put("required", true);
        userConfig.put("username", usernameField);
        Map<String, Object> passwordField = new LinkedHashMap<>();
        passwordField.put("type", "string");
        passwordField.put("title", "Saiku password");
        passwordField.put("description", "Stored in the host's secret store, not on disk.");
        passwordField.put("sensitive", true);
        passwordField.put("required", true);
        userConfig.put("password", passwordField);
        manifest.put("user_config", userConfig);

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("type", "node");
        server.put("entry_point", "server.js");
        Map<String, Object> mcpConfig = new LinkedHashMap<>();
        mcpConfig.put("command", "node");
        mcpConfig.put("args", List.of("${__dirname}/server.js"));
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("SAIKU_USER", "${user_config.username}");
        env.put("SAIKU_PASS", "${user_config.password}");
        mcpConfig.put("env", env);
        server.put("mcp_config", mcpConfig);
        manifest.put("server", server);
        return manifest;
    }

    /**
     * Node shim shipped inside the DXT zip as {@code server.js}: a
     * self-contained stdio↔streamable-http MCP bridge that uses only
     * Node stdlib (no npm deps). Runs under Claude Desktop's built-in
     * Node. The URL is JSON-encoded so embedded quotes or unicode survive
     * the round-trip into JavaScript source.
     *
     * <p>saiku#878: the shim reads {@code SAIKU_USER} / {@code SAIKU_PASS}
     * from its environment (populated by the host from the user_config
     * the user supplied at install time) and attaches
     * {@code Authorization: Basic <base64(user:pass)>} to every request,
     * so per-user Mondrian roles flow end-to-end.
     *
     * <p>The shim also survives mid-session server cycles — when the
     * remote loses our streamable-http session we transparently
     * re-handshake and replay the in-flight request.
     *
     * <p>Package-visible for unit tests.
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
                + "// Reads SAIKU_USER / SAIKU_PASS from env (populated by the\n"
                + "// host from user_config at install time) and threads\n"
                + "// Authorization: Basic on every request so the launcher's\n"
                + "// Spring Security chain sees per-user credentials (#878).\n"
                + "// Survives mid-session server cycles: when the remote loses\n"
                + "// our streamable-http session (operator restarted the\n"
                + "// launcher, container recreated, etc.) we transparently\n"
                + "// re-handshake and replay the in-flight request.\n"
                + "const https = require('https');\n"
                + "const http = require('http');\n"
                + "const { URL } = require('url');\n"
                + "const url = new URL("
                + urlLiteral
                + ");\n"
                + "const lib = url.protocol === 'https:' ? https : http;\n"
                + "const SAIKU_USER = process.env.SAIKU_USER || '';\n"
                + "const SAIKU_PASS = process.env.SAIKU_PASS || '';\n"
                + "const basicAuth = SAIKU_USER\n"
                + "  ? 'Basic ' + Buffer.from(SAIKU_USER + ':' + SAIKU_PASS).toString('base64')\n"
                + "  : null;\n"
                + "let sessionId = null;\n"
                + "function post(body) {\n"
                + "  return new Promise((resolve, reject) => {\n"
                + "    const headers = {\n"
                + "      'Content-Type': 'application/json',\n"
                + "      'Accept': 'application/json, text/event-stream'\n"
                + "    };\n"
                + "    if (basicAuth) headers['Authorization'] = basicAuth;\n"
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
