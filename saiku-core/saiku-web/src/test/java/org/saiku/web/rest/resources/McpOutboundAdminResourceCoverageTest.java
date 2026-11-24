/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.mcp.outbound.McpOutboundClient;
import org.saiku.service.mcp.outbound.McpOutboundServerRegistry;

/** Unit tests for {@link McpOutboundAdminResource}. */
public class McpOutboundAdminResourceCoverageTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private McpOutboundAdminResource resource;
    private McpOutboundServerRegistry registry;

    @Before
    public void setUp() throws Exception {
        registry = new McpOutboundServerRegistry(
                Files.createTempDirectory(folder.getRoot().toPath(), "mcp").toString());
        resource = new McpOutboundAdminResource();
        resource.setRegistry(registry);
        // A real client; the unreachable-server test swaps in one whose resolver fails.
        resource.setClient(new McpOutboundClient(java.time.Duration.ofMillis(250)));
        resource.setCatalog(null);
    }

    private static McpOutboundAdminResource.ServerDto server(String id, String name, String url) {
        McpOutboundAdminResource.ServerDto dto = new McpOutboundAdminResource.ServerDto();
        dto.id = id;
        dto.name = name;
        dto.url = url;
        dto.enabledTools = List.of("search");
        return dto;
    }

    @Test
    public void save_then_list_round_trips_a_server() {
        Response saved = resource.save("docs-server", server("docs-server", "Docs", "https://mcp.notion.com/mcp"));
        assertEquals(200, saved.getStatus());

        List<McpOutboundAdminResource.ServerDto> listed = resource.list();
        assertEquals(1, listed.size());
        assertEquals("Docs", listed.get(0).name);
        assertEquals("docs-server", listed.get(0).id);
    }

    @Test
    public void save_validates_id_name_and_url() {
        assertEquals(
                400,
                resource.save("Bad Id", server("Bad Id", "n", "https://mcp.notion.com/mcp"))
                        .getStatus());
        assertEquals(400, resource.save("ok", null).getStatus());

        McpOutboundAdminResource.ServerDto noName = server("ok", null, "https://mcp.notion.com/mcp");
        assertEquals(400, resource.save("ok", noName).getStatus());

        McpOutboundAdminResource.ServerDto noUrl = server("ok", "n", " ");
        assertEquals(400, resource.save("ok", noUrl).getStatus());
    }

    @Test
    public void save_requires_auth_header_name_and_value_together() {
        McpOutboundAdminResource.ServerDto orphan = server("ok", "n", "https://mcp.notion.com/mcp");
        orphan.authHeaderName = "Authorization";
        assertEquals(400, resource.save("ok", orphan).getStatus());
    }

    @Test
    public void save_reuses_the_stored_auth_header_when_the_value_is_omitted() {
        McpOutboundAdminResource.ServerDto withAuth = server("keeper", "n", "https://mcp.notion.com/mcp");
        withAuth.authHeaderName = "Authorization";
        withAuth.authHeaderValue = "Bearer s3cret";
        assertEquals(200, resource.save("keeper", withAuth).getStatus());

        McpOutboundAdminResource.ServerDto update = server("keeper", "renamed", "https://mcp.notion.com/mcp");
        Response response = resource.save("keeper", update);
        assertEquals(200, response.getStatus());
        McpOutboundAdminResource.ServerDto dto = resource.list().get(0);
        assertEquals("renamed", dto.name);
        assertTrue(dto.hasAuthHeader);
    }

    @Test
    public void a_saved_server_with_an_invalid_url_is_skipped_and_reported() {
        // saiku#2542: save() now refuses a non-https URL up front, so nothing is
        // persisted and the server never reaches list() or errors().
        McpOutboundAdminResource.ServerDto odd = server("odd", "n", "not-a-url");
        assertEquals(400, resource.save("odd", odd).getStatus());
        assertTrue(resource.list().isEmpty());
        assertTrue(resource.errors().isEmpty());
    }

    @Test
    public void delete_removes_a_saved_server_and_404s_the_second_time() {
        resource.save("temp", server("temp", "n", "https://mcp.notion.com/mcp"));
        assertEquals(200, resource.delete("temp").getStatus());
        assertEquals(404, resource.delete("temp").getStatus());
        assertTrue(resource.list().isEmpty());
    }

    @Test
    public void refresh_reports_ok() {
        Response response = resource.refresh();
        assertEquals(200, response.getStatus());
        assertEquals(true, ((Map<?, ?>) response.getEntity()).get("refreshed"));
    }

    @Test
    public void discovery_for_an_unknown_server_is_not_found() {
        assertEquals(404, resource.discoverTools("nope").getStatus());
    }

    @Test
    public void discovery_against_an_unreachable_server_reports_bad_gateway() {
        // Saved against a resolvable host, then sent through a client whose resolver
        // always fails: the send-time URL check refuses, which the tools endpoint
        // reports as a bad gateway — no live connection is attempted.
        resource.save("down", server("down", "Down", "https://mcp.notion.com/mcp"));
        resource.setClient(new McpOutboundClient(
                java.net.http.HttpClient.newHttpClient(), java.time.Duration.ofMillis(250), host -> {
                    throw new java.net.UnknownHostException(host);
                }));
        Response response = resource.discoverTools("down");
        assertEquals(502, response.getStatus());
    }

    @Test
    public void errors_lists_unparseable_files() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of(
                Files.createTempDirectory(folder.getRoot().toPath(), "broken").toString());
        Files.write(root.resolve("bad id.json"), "not json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        McpOutboundAdminResource broken = new McpOutboundAdminResource();
        broken.setRegistry(new McpOutboundServerRegistry(root.toString()));
        List<Map<String, String>> errors = broken.errors();
        assertEquals(1, errors.size());
        assertEquals("bad id.json", errors.get(0).get("source"));
    }
}
