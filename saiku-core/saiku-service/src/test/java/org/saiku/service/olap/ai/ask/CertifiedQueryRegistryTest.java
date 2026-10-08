/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * saiku#1430 — catalogue scanning, error surfacing, id-collision handling, and the deterministic
 * intent matcher that decides which certified answer an ask gets.
 */
public class CertifiedQueryRegistryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String QUERY_BODY = "{\"name\":\"q\",\"cube\":{\"name\":\"%s\",\"connection\":\"conn\"},"
            + "\"type\":\"MDX\",\"mdx\":\"SELECT {} ON COLUMNS FROM [%s]\"}";

    private static String entry(String id, String cube, String intents) {
        return "{\"id\":\"" + id + "\",\"description\":\"d\",\"matchIntent\":[" + intents + "],\"query\":"
                + String.format(QUERY_BODY, cube, cube)
                + "}";
    }

    @Test
    public void emptyRootYieldsEmpty() throws Exception {
        CertifiedQueryRegistry reg =
                new CertifiedQueryRegistry(tmp.newFolder("certified").toPath());
        assertTrue(reg.list().isEmpty());
        assertTrue(reg.errors().isEmpty());
    }

    @Test
    public void missingRootYieldsEmpty() {
        CertifiedQueryRegistry reg =
                new CertifiedQueryRegistry(tmp.getRoot().toPath().resolve("nope"));
        assertTrue(reg.list().isEmpty());
        assertTrue(reg.match("what was monthly revenue").isEmpty());
    }

    @Test
    public void scansJsonFilesOrderedById() throws Exception {
        Path root = tmp.newFolder("certified").toPath();
        write(root.resolve("b.json"), entry("b-query", "Sales", "\"revenue\""));
        write(root.resolve("a.json"), entry("a-query", "Sales", "\"revenue\""));
        write(root.resolve("README.txt"), "not a certified query");
        CertifiedQueryRegistry reg = new CertifiedQueryRegistry(root);
        List<CertifiedQuery> queries = reg.list();
        assertEquals(2, queries.size());
        assertEquals("a-query", queries.get(0).id());
        assertEquals("b-query", queries.get(1).id());
    }

    @Test
    public void surfacesErrorsForBadFiles() throws Exception {
        Path root = tmp.newFolder("certified").toPath();
        write(root.resolve("ok.json"), entry("ok", "Sales", "\"revenue\""));
        write(root.resolve("bad.json"), "not json at all");
        write(root.resolve("no-query.json"), "{\"id\":\"nq\",\"matchIntent\":[\"revenue\"]}");
        CertifiedQueryRegistry reg = new CertifiedQueryRegistry(root);
        assertEquals(1, reg.list().size());
        // Directory walk order isn't contractual, so assert on the SET of codes, not their order.
        List<String> codes = reg.errors().stream()
                .map(CertifiedQueryRegistry.CertifiedError::code)
                .toList();
        assertEquals(2, codes.size());
        assertTrue(codes.toString(), codes.contains("MALFORMED_JSON"));
        assertTrue(codes.toString(), codes.contains("MISSING_QUERY"));
    }

    @Test
    public void dedupesOnIdCollision() throws Exception {
        Path root = tmp.newFolder("certified").toPath();
        write(root.resolve("first.json"), entry("dup", "Sales", "\"revenue\""));
        Path sub = Files.createDirectory(root.resolve("nested"));
        write(sub.resolve("second.json"), entry("dup", "Sales", "\"revenue\""));
        CertifiedQueryRegistry reg = new CertifiedQueryRegistry(root);
        assertEquals(1, reg.list().size());
        assertEquals(1, reg.errors().size());
        assertEquals("DUPLICATE_ID", reg.errors().get(0).code());
    }

    @Test
    public void detectsAddedFileWithoutForceRefresh() throws Exception {
        Path root = tmp.newFolder("certified").toPath();
        CertifiedQueryRegistry reg = new CertifiedQueryRegistry(root);
        assertTrue(reg.list().isEmpty());
        write(root.resolve("a.json"), entry("a", "Sales", "\"revenue\""));
        assertEquals(1, reg.list().size());
    }

    @Test
    public void matchesDeclaredIntent() throws Exception {
        Path root = tmp.newFolder("certified").toPath();
        write(
                root.resolve("a.json"),
                entry(
                        "monthly-net-revenue",
                        "Sales",
                        "\"monthly revenue\",\"monthly net revenue\",\"revenue by month\""));
        CertifiedQueryRegistry reg = new CertifiedQueryRegistry(root);
        assertEquals(
                "monthly-net-revenue",
                reg.match("what was monthly revenue?").get().id());
        assertEquals(
                "monthly-net-revenue",
                reg.match("Show me Monthly Net Revenue").get().id());
        assertEquals(
                "monthly-net-revenue",
                reg.match("can you show revenue by month?").get().id());
    }

    @Test
    public void doesNotMatchPartialOrUnrelatedIntent() throws Exception {
        Path root = tmp.newFolder("certified").toPath();
        write(root.resolve("a.json"), entry("monthly-net-revenue", "Sales", "\"monthly revenue\""));
        CertifiedQueryRegistry reg = new CertifiedQueryRegistry(root);
        assertTrue(reg.match("monthly headcount").isEmpty());
        assertTrue(reg.match("weekly revenue").isEmpty());
        assertTrue(reg.match("revenue").isEmpty()); // half the intent is not the intent
        assertTrue(reg.match("").isEmpty());
        assertTrue(reg.match(null).isEmpty());
    }

    @Test
    public void prefersTheMoreSpecificMatch() throws Exception {
        Path root = tmp.newFolder("certified").toPath();
        // Both match; the narrower approval must win regardless of file/id ordering.
        write(root.resolve("a-broad.json"), entry("broad", "Sales", "\"monthly sales\""));
        write(root.resolve("z-narrow.json"), entry("narrow", "Sales", "\"monthly store sales\""));
        CertifiedQueryRegistry reg = new CertifiedQueryRegistry(root);
        assertEquals("narrow", reg.match("monthly store sales by country").get().id());
    }

    @Test
    public void matchingIsPluralAndCaseInsensitive() throws Exception {
        Path root = tmp.newFolder("certified").toPath();
        write(root.resolve("a.json"), entry("a", "Sales", "\"monthly revenues\""));
        CertifiedQueryRegistry reg = new CertifiedQueryRegistry(root);
        assertEquals("a", reg.match("MONTHLY REVENUE").get().id());
    }

    @Test
    public void respectsTheCubeScope() throws Exception {
        Path root = tmp.newFolder("certified").toPath();
        write(root.resolve("a.json"), entry("sales-cert", "Sales", "\"monthly revenue\""));
        CertifiedQueryRegistry reg = new CertifiedQueryRegistry(root);
        assertTrue(reg.match("monthly revenue", "Sales").isPresent());
        assertTrue(reg.match("monthly revenue", "sales").isPresent()); // case-insensitive
        assertTrue(reg.match("monthly revenue", "Salesbook").isEmpty()); // a different cube
        assertTrue(reg.match("monthly revenue", null).isPresent()); // no cube in play
    }

    @Test
    public void getById() throws Exception {
        Path root = tmp.newFolder("certified").toPath();
        write(root.resolve("a.json"), entry("a", "Sales", "\"revenue\""));
        CertifiedQueryRegistry reg = new CertifiedQueryRegistry(root);
        Optional<CertifiedQuery> found = reg.get("a");
        assertTrue(found.isPresent());
        assertNotNull(found.get().query().getMdx());
        assertTrue(reg.get("nope").isEmpty());
        assertTrue(reg.get("").isEmpty());
    }

    @Test
    public void stopsMatchingWhenCubeDiffers() throws Exception {
        Path root = tmp.newFolder("certified").toPath();
        write(root.resolve("a.json"), entry("a", "Sales", "\"monthly revenue\""));
        CertifiedQueryRegistry reg = new CertifiedQueryRegistry(root);
        assertFalse(reg.match("monthly revenue", "Warehouse").isPresent());
    }

    private static void write(Path p, String content) throws Exception {
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }
}
