/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mcp.outbound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class McpOutboundServerRegistryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String GOLDEN = "{\"id\":\"%s\",\"name\":\"%s\",\"url\":\"https://example.com/mcp\"}";

    @Test
    public void emptyRootYieldsEmpty() throws Exception {
        Path root = tmp.newFolder("mcp-servers").toPath();
        McpOutboundServerRegistry reg = new McpOutboundServerRegistry(root);
        assertTrue(reg.list().isEmpty());
        assertTrue(reg.errors().isEmpty());
    }

    @Test
    public void missingRootYieldsEmpty() {
        McpOutboundServerRegistry reg =
                new McpOutboundServerRegistry(tmp.getRoot().toPath().resolve("nope"));
        assertTrue(reg.list().isEmpty());
    }

    @Test
    public void scansJsonFiles() throws Exception {
        Path root = tmp.newFolder("mcp-servers").toPath();
        write(root.resolve("a.json"), String.format(GOLDEN, "server-a", "A"));
        write(root.resolve("b.json"), String.format(GOLDEN, "server-b", "B"));
        write(root.resolve("README.txt"), "ignore me");
        McpOutboundServerRegistry reg = new McpOutboundServerRegistry(root);
        List<McpOutboundServer> servers = reg.list();
        assertEquals(2, servers.size());
        assertEquals("server-a", servers.get(0).id());
        assertEquals("server-b", servers.get(1).id());
    }

    @Test
    public void surfacesErrorsForBadFiles() throws Exception {
        Path root = tmp.newFolder("mcp-servers").toPath();
        write(root.resolve("ok.json"), String.format(GOLDEN, "ok", "OK"));
        write(root.resolve("bad.json"), "not json at all");
        McpOutboundServerRegistry reg = new McpOutboundServerRegistry(root);
        assertEquals(1, reg.list().size());
        assertEquals(1, reg.errors().size());
        assertEquals("MALFORMED_JSON", reg.errors().get(0).code());
    }

    @Test
    public void dedupesOnId() throws Exception {
        Path root = tmp.newFolder("mcp-servers").toPath();
        write(root.resolve("first.json"), String.format(GOLDEN, "dup", "First"));
        Path sub = Files.createDirectory(root.resolve("nested"));
        write(sub.resolve("second.json"), String.format(GOLDEN, "dup", "Second"));
        McpOutboundServerRegistry reg = new McpOutboundServerRegistry(root);
        assertEquals(1, reg.list().size());
        assertEquals(1, reg.errors().size());
        assertEquals("DUPLICATE_ID", reg.errors().get(0).code());
    }

    @Test
    public void getById() throws Exception {
        Path root = tmp.newFolder("mcp-servers").toPath();
        write(root.resolve("a.json"), String.format(GOLDEN, "server-a", "A"));
        McpOutboundServerRegistry reg = new McpOutboundServerRegistry(root);
        assertTrue(reg.get("server-a").isPresent());
        assertFalse(reg.get("nope").isPresent());
        assertFalse(reg.get("").isPresent());
        assertFalse(reg.get(null).isPresent());
    }

    @Test
    public void stringPathCtor() {
        McpOutboundServerRegistry reg = new McpOutboundServerRegistry(
                tmp.getRoot().toPath().resolve("mcp-servers").toString());
        assertNotNull(reg);
        assertTrue(reg.list().isEmpty());
    }

    @Test
    public void saveWritesFileAndRoundTrips() throws Exception {
        Path root = tmp.newFolder("mcp-servers").toPath();
        McpOutboundServerRegistry reg = new McpOutboundServerRegistry(root);
        McpOutboundServer s = new McpOutboundServer(
                "notion",
                "Notion",
                "https://mcp.notion.com/mcp",
                "Authorization",
                "sk-plaintext-secret",
                Set.of("search_docs"),
                "notion.json");
        reg.save(s);

        assertTrue(Files.exists(root.resolve("notion.json")));
        McpOutboundServer back = reg.get("notion").orElseThrow();
        assertEquals("Notion", back.name());
        assertEquals("https://mcp.notion.com/mcp", back.url());
        assertEquals("Authorization", back.authHeaderName());
        assertEquals("sk-plaintext-secret", back.authHeaderValue()); // decrypted transparently on read
        assertEquals(Set.of("search_docs"), back.enabledTools());
    }

    @Test
    public void savePersistsCredentialEncryptedAtRest() throws Exception {
        Path root = tmp.newFolder("mcp-servers").toPath();
        McpOutboundServerRegistry reg = new McpOutboundServerRegistry(root);
        McpOutboundServer s = new McpOutboundServer(
                "notion",
                "Notion",
                "https://mcp.notion.com/mcp",
                "Authorization",
                "sk-plaintext-secret",
                Set.of(),
                "x");
        reg.save(s);

        String raw = Files.readString(root.resolve("notion.json"), StandardCharsets.UTF_8);
        assertFalse("plaintext credential must never hit disk", raw.contains("sk-plaintext-secret"));
        com.fasterxml.jackson.databind.JsonNode onDisk =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
        String storedValue = onDisk.path("authHeaderValue").asText();
        assertTrue("stored value should carry the v2 encryption prefix", storedValue.startsWith("v2:"));
    }

    @Test
    public void saveWithoutCredentialOmitsAuthFields() throws Exception {
        Path root = tmp.newFolder("mcp-servers").toPath();
        McpOutboundServerRegistry reg = new McpOutboundServerRegistry(root);
        McpOutboundServer s = new McpOutboundServer("open", "Open", "https://e.com/mcp", null, null, Set.of(), "x");
        reg.save(s);
        McpOutboundServer back = reg.get("open").orElseThrow();
        assertFalse(back.hasAuthHeader());
    }

    @Test
    public void deleteRemovesFileAndRescans() throws Exception {
        Path root = tmp.newFolder("mcp-servers").toPath();
        write(root.resolve("gone.json"), String.format(GOLDEN, "gone", "Gone"));
        McpOutboundServerRegistry reg = new McpOutboundServerRegistry(root);
        assertEquals(1, reg.list().size());
        assertTrue(reg.delete("gone"));
        assertFalse(Files.exists(root.resolve("gone.json")));
        assertTrue(reg.list().isEmpty());
        assertFalse("deleting a missing server returns false", reg.delete("gone"));
    }

    @Test
    public void saveRejectsUnsafeId() {
        Path root = tmp.getRoot().toPath().resolve("mcp-servers");
        McpOutboundServerRegistry reg = new McpOutboundServerRegistry(root);
        McpOutboundServer evil = new McpOutboundServer("../evil", "x", "https://e.com", null, null, Set.of(), "x.json");
        try {
            reg.save(evil);
            org.junit.Assert.fail("a traversal id must be rejected, not written");
        } catch (Exception expected) {
            // IllegalArgumentException from the record ctor OR the registry's own guard — either
            // way the write must never happen.
        }
    }

    @Test
    public void idValidationIsKebabOnly() {
        assertTrue(McpOutboundServerRegistry.isValidId("notion-workspace"));
        assertFalse(McpOutboundServerRegistry.isValidId("../x"));
        assertFalse(McpOutboundServerRegistry.isValidId("Foo Bar"));
        assertFalse(McpOutboundServerRegistry.isValidId("a/b"));
        assertFalse(McpOutboundServerRegistry.isValidId(""));
        assertFalse(McpOutboundServerRegistry.isValidId(null));
    }

    private static void write(Path p, String content) throws Exception {
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }
}
