/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.apps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.apps.TilePluginRegistry;

/**
 * Resource-level tests over a live {@link TilePluginRegistry} rooted at a temp dir: list works,
 * fetch-html works, a bad/missing/traversal id 404s safely (no filesystem escape).
 */
public class TilePluginResourceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TilePluginResource resource(Path root) {
        TilePluginResource r = new TilePluginResource();
        r.setRegistry(new TilePluginRegistry(root));
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void listReturnsInstalledManifests() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        writePlugin(root, "bars", "{\"id\":\"bars\",\"label\":\"Bars\"}", "<html>bars</html>");

        Response resp = resource(root).list(false);
        assertEquals(200, resp.getStatus());
        List<Map<String, Object>> body = (List<Map<String, Object>>) resp.getEntity();
        assertEquals(1, body.size());
        assertEquals("bars", body.get(0).get("id"));
        assertEquals("Bars", body.get(0).get("label"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void listErrorsSurfacesParseFailures() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        Path dir = Files.createDirectories(root.resolve("broken"));
        write(dir.resolve("plugin.json"), "not json");
        write(dir.resolve("plugin.html"), "<html></html>");

        Response resp = resource(root).list(true);
        assertEquals(200, resp.getStatus());
        List<Map<String, String>> body = (List<Map<String, String>>) resp.getEntity();
        assertEquals(1, body.size());
        assertEquals("UNPARSEABLE_MANIFEST", body.get(0).get("code"));
    }

    @Test
    public void htmlReturnsSrcdocForInstalledPlugin() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        writePlugin(root, "bars", "{\"id\":\"bars\",\"label\":\"Bars\"}", "<html>hello</html>");

        Response resp = resource(root).html("bars");
        assertEquals(200, resp.getStatus());
        assertEquals("<html>hello</html>", resp.getEntity());
        assertEquals("text/html", resp.getMediaType().toString());
    }

    @Test
    public void htmlMissingPlugin404s() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        Response resp = resource(root).html("nope");
        assertEquals(404, resp.getStatus());
    }

    @Test
    public void htmlTraversalIdIsRejectedWith404() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        writePlugin(root, "ok", "{\"id\":\"ok\",\"label\":\"OK\"}", "<html>ok</html>");
        // Plant a secret OUTSIDE the plugins root; a traversal id must not reach it.
        write(tmp.getRoot().toPath().resolve("secret.txt"), "TOP SECRET");
        TilePluginResource r = resource(root);

        assertEquals(404, r.html("../secret").getStatus());
        assertEquals(404, r.html("../../secret").getStatus());
        assertEquals(404, r.html("..%2fsecret").getStatus());
        assertEquals(404, r.html("/etc/passwd").getStatus());
        assertEquals(404, r.html("..").getStatus());
    }

    @Test
    public void nullRegistryDegradesGracefully() {
        TilePluginResource r = new TilePluginResource();
        Response list = r.list(false);
        assertEquals(200, list.getStatus());
        assertTrue(((List<?>) list.getEntity()).isEmpty());
        assertEquals(404, r.html("anything").getStatus());
    }

    // ---------- helpers ----------

    private static void writePlugin(Path root, String id, String json, String html) throws Exception {
        Path dir = Files.createDirectories(root.resolve(id));
        write(dir.resolve("plugin.json"), json);
        write(dir.resolve("plugin.html"), html);
    }

    private static void write(Path p, String content) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }
}
