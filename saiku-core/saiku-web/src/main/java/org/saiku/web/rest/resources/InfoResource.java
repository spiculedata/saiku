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

import com.qmino.miredot.annotations.ReturnType;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.Response;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
}
