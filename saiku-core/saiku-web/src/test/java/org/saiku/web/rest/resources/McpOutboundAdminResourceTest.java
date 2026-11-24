/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.mcp.outbound.McpOutboundServerRegistry;
import org.saiku.service.schedule.alert.WebhookUrlValidator;
import org.saiku.web.rest.resources.McpOutboundAdminResource.ServerDto;

/**
 * saiku#2542: the admin save endpoint applies the same webhook-grade gates as the on-disk parser —
 * https-only, SSRF-refusing URLs and restricted/invalid auth header names are refused at save time
 * (400) instead of failing every later discovery call.
 */
public class McpOutboundAdminResourceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Hermetic resolver: every host "resolves" to a public address — no live DNS in tests. */
    private static final WebhookUrlValidator.HostResolver RESOLVER = host -> {
        try {
            return new java.net.InetAddress[] {
                java.net.InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34})
            };
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    };

    private McpOutboundAdminResource resource;

    @Before
    public void setUp() throws Exception {
        Path root = tmp.newFolder("mcp-servers").toPath();
        McpOutboundServerRegistry registry = new McpOutboundServerRegistry(root, RESOLVER);
        resource = new McpOutboundAdminResource();
        resource.setRegistry(registry);
    }

    private static ServerDto dto(String url, String headerName) {
        ServerDto d = new ServerDto();
        d.id = "notion";
        d.name = "Notion";
        d.url = url;
        d.authHeaderName = headerName;
        d.authHeaderValue = headerName == null ? null : "Bearer token";
        return d;
    }

    @Test
    public void plainHttpUrlIsRefused() {
        Response resp = resource.save("notion", dto("http://mcp.notion.com/mcp", null));
        assertEquals(400, resp.getStatus());
        assertTrue(registryIsEmpty());
    }

    @Test
    public void loopbackAndPrivateHostsAreRefused() {
        assertEquals(
                400,
                resource.save("notion", dto("https://localhost:3000/mcp", null)).getStatus());
        assertEquals(
                400,
                resource.save("notion", dto("https://127.0.0.1:3000/mcp", null)).getStatus());
        assertEquals(
                400,
                resource.save("notion", dto("https://192.168.1.10/mcp", null)).getStatus());
        assertEquals(
                400,
                resource.save("notion", dto("https://169.254.169.254/latest/meta-data", null))
                        .getStatus());
        assertTrue(registryIsEmpty());
    }

    @Test
    public void validHttpsUrlSaves() {
        Response resp = resource.save("notion", dto("https://mcp.notion.com/mcp", null));
        assertEquals(200, resp.getStatus());
        assertFalse(registryIsEmpty());
    }

    @Test
    public void restrictedAndNonTokenHeaderNamesAreRefused() {
        assertEquals(
                400,
                resource.save("notion", dto("https://mcp.notion.com/mcp", "Host"))
                        .getStatus());
        assertEquals(
                400,
                resource.save("notion", dto("https://mcp.notion.com/mcp", "Content-Length"))
                        .getStatus());
        assertEquals(
                400,
                resource.save("notion", dto("https://mcp.notion.com/mcp", "bad header name"))
                        .getStatus());
        assertTrue(registryIsEmpty());
    }

    private boolean registryIsEmpty() {
        return resource.list().isEmpty();
    }
}
