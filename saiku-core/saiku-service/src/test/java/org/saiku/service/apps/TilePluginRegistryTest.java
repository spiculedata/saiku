/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.apps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Registry tests: bundle scanning, id-slug validation (path-traversal defence), missing-html /
 * duplicate-id / unparseable-manifest error surfacing, html retrieval, and mtime-based refresh.
 */
public class TilePluginRegistryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void emptyRootYieldsEmptyCatalogue() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        TilePluginRegistry reg = new TilePluginRegistry(root);
        assertTrue(reg.list().isEmpty());
        assertTrue(reg.errors().isEmpty());
    }

    @Test
    public void missingRootYieldsEmptyCatalogue() {
        TilePluginRegistry reg = new TilePluginRegistry(tmp.getRoot().toPath().resolve("nope"));
        assertTrue(reg.list().isEmpty());
        assertTrue(reg.errors().isEmpty());
    }

    @Test
    public void scansValidBundle() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        writePlugin(root, "bar-chart", "{\"id\":\"bar-chart\",\"label\":\"Bars\"}", "<html>bars</html>");
        TilePluginRegistry reg = new TilePluginRegistry(root);

        List<TilePluginManifest> list = reg.list();
        assertEquals(1, list.size());
        assertEquals("bar-chart", list.get(0).id());
        assertEquals("Bars", list.get(0).label());
        assertTrue(reg.errors().isEmpty());
        assertEquals("<html>bars</html>", reg.html("bar-chart"));
    }

    @Test
    public void manifestOptionSchemaIsExposed() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        writePlugin(
                root,
                "opts",
                "{\"id\":\"opts\",\"label\":\"Opts\",\"optionSchema\":{\"type\":\"object\"}}",
                "<html>x</html>");
        TilePluginRegistry reg = new TilePluginRegistry(root);
        TilePluginManifest m = reg.list().get(0);
        assertNotNull(m.optionSchema());
        assertEquals("object", m.optionSchema().get("type").asText());
        assertTrue(m.asSummary().containsKey("optionSchema"));
    }

    @Test
    public void summaryOmitsOptionSchemaWhenAbsent() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        writePlugin(root, "plain", "{\"id\":\"plain\",\"label\":\"Plain\"}", "<html>x</html>");
        TilePluginRegistry reg = new TilePluginRegistry(root);
        assertFalse(reg.list().get(0).asSummary().containsKey("optionSchema"));
    }

    @Test
    public void missingHtmlSurfacesError() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        Path dir = Files.createDirectories(root.resolve("no-html"));
        write(dir.resolve("plugin.json"), "{\"id\":\"no-html\",\"label\":\"X\"}");
        TilePluginRegistry reg = new TilePluginRegistry(root);

        assertTrue(reg.list().isEmpty());
        assertEquals(1, reg.errors().size());
        assertEquals("MISSING_HTML", reg.errors().get(0).code());
        assertNull(reg.html("no-html"));
    }

    @Test
    public void unparseableManifestSurfacesError() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        Path dir = Files.createDirectories(root.resolve("broken"));
        write(dir.resolve("plugin.json"), "{ this is not json ");
        write(dir.resolve("plugin.html"), "<html></html>");
        TilePluginRegistry reg = new TilePluginRegistry(root);

        assertTrue(reg.list().isEmpty());
        assertEquals(1, reg.errors().size());
        assertEquals("UNPARSEABLE_MANIFEST", reg.errors().get(0).code());
    }

    @Test
    public void missingManifestSurfacesError() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        Path dir = Files.createDirectories(root.resolve("html-only"));
        write(dir.resolve("plugin.html"), "<html></html>");
        TilePluginRegistry reg = new TilePluginRegistry(root);

        assertTrue(reg.list().isEmpty());
        assertEquals(1, reg.errors().size());
        assertEquals("UNPARSEABLE_MANIFEST", reg.errors().get(0).code());
    }

    @Test
    public void badManifestIdSurfacesInvalidId() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        // A slug-invalid id inside the manifest — rejected by the parser.
        Path dir = Files.createDirectories(root.resolve("weird"));
        write(dir.resolve("plugin.json"), "{\"id\":\"../etc/passwd\",\"label\":\"X\"}");
        write(dir.resolve("plugin.html"), "<html></html>");
        TilePluginRegistry reg = new TilePluginRegistry(root);

        assertTrue(reg.list().isEmpty());
        assertEquals(1, reg.errors().size());
        assertEquals("INVALID_ID", reg.errors().get(0).code());
    }

    @Test
    public void missingLabelSurfacesUnparseable() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        Path dir = Files.createDirectories(root.resolve("nolabel"));
        write(dir.resolve("plugin.json"), "{\"id\":\"nolabel\"}");
        write(dir.resolve("plugin.html"), "<html></html>");
        TilePluginRegistry reg = new TilePluginRegistry(root);

        assertTrue(reg.list().isEmpty());
        assertEquals(1, reg.errors().size());
        assertEquals("UNPARSEABLE_MANIFEST", reg.errors().get(0).code());
    }

    @Test
    public void duplicateIdSurfacesError() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        // Two distinct bundle dirs whose manifests both declare id 'dup'. The manifest id is
        // authoritative, so the second is rejected as a DUPLICATE_ID while the first still loads.
        Path a = Files.createDirectories(root.resolve("bundle-a"));
        write(a.resolve("plugin.json"), "{\"id\":\"dup\",\"label\":\"A\"}");
        write(a.resolve("plugin.html"), "<html>a</html>");
        Path b = Files.createDirectories(root.resolve("bundle-b"));
        write(b.resolve("plugin.json"), "{\"id\":\"dup\",\"label\":\"B\"}");
        write(b.resolve("plugin.html"), "<html>b</html>");
        TilePluginRegistry reg = new TilePluginRegistry(root);

        assertEquals(1, reg.list().size());
        assertEquals("dup", reg.list().get(0).id());
        assertEquals(1, reg.errors().size());
        assertEquals("DUPLICATE_ID", reg.errors().get(0).code());
    }

    @Test
    public void directoryNameNeedNotMatchId() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        // Bundle in dir 'container' declaring id 'chart' — allowed; html resolves by id via the
        // captured directory path (not by concatenating the id into the filesystem path).
        Path dir = Files.createDirectories(root.resolve("container"));
        write(dir.resolve("plugin.json"), "{\"id\":\"chart\",\"label\":\"Chart\"}");
        write(dir.resolve("plugin.html"), "<html>chart</html>");
        TilePluginRegistry reg = new TilePluginRegistry(root);

        assertEquals(1, reg.list().size());
        assertEquals("chart", reg.list().get(0).id());
        assertEquals("<html>chart</html>", reg.html("chart"));
    }

    @Test
    public void htmlRejectsTraversalIds() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        writePlugin(root, "ok", "{\"id\":\"ok\",\"label\":\"OK\"}", "<html>ok</html>");
        // Plant a sensitive file OUTSIDE the plugins root to prove it can't be reached.
        Path secret = tmp.getRoot().toPath().resolve("secret.txt");
        write(secret, "TOP SECRET");
        TilePluginRegistry reg = new TilePluginRegistry(root);

        assertNull(reg.html("../secret"));
        assertNull(reg.html("../../secret"));
        assertNull(reg.html("..%2f..%2fsecret"));
        assertNull(reg.html("/etc/passwd"));
        assertNull(reg.html(".."));
        assertNull(reg.html(""));
        assertNull(reg.html(null));
        // Unknown but slug-shaped id → null (not a file reach).
        assertNull(reg.html("does-not-exist"));
        // The one real plugin still resolves.
        assertEquals("<html>ok</html>", reg.html("ok"));
    }

    @Test
    public void getByIdValidatesSlug() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        writePlugin(root, "ok", "{\"id\":\"ok\",\"label\":\"OK\"}", "<html>ok</html>");
        TilePluginRegistry reg = new TilePluginRegistry(root);
        assertTrue(reg.get("ok").isPresent());
        assertFalse(reg.get("../etc").isPresent());
        assertFalse(reg.get("nope").isPresent());
        assertFalse(reg.get("").isPresent());
        assertFalse(reg.get(null).isPresent());
    }

    @Test
    public void refreshesOnHtmlEdit() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        writePlugin(root, "live", "{\"id\":\"live\",\"label\":\"L\"}", "<html>v1</html>");
        TilePluginRegistry reg = new TilePluginRegistry(root);
        assertEquals("<html>v1</html>", reg.html("live"));

        Path html = root.resolve("live").resolve("plugin.html");
        write(html, "<html>v2</html>");
        Files.setLastModifiedTime(
                html, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        assertEquals("<html>v2</html>", reg.html("live"));
    }

    @Test
    public void detectsAddedBundleWithoutForceRefresh() throws Exception {
        Path root = tmp.newFolder("tile-plugins").toPath();
        writePlugin(root, "a", "{\"id\":\"a\",\"label\":\"A\"}", "<html>a</html>");
        TilePluginRegistry reg = new TilePluginRegistry(root);
        assertEquals(1, reg.list().size());
        writePlugin(root, "b", "{\"id\":\"b\",\"label\":\"B\"}", "<html>b</html>");
        assertEquals(2, reg.list().size());
    }

    @Test
    public void stringPathConstructor() {
        TilePluginRegistry reg = new TilePluginRegistry(
                tmp.getRoot().toPath().resolve("tile-plugins").toString());
        assertNotNull(reg);
        assertTrue(reg.list().isEmpty());
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
