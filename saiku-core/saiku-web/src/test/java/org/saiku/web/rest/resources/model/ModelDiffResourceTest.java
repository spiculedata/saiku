/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.user.UserService;

/**
 * saiku#1434 — {@code POST /saiku/admin/model/diff}. Direct-method invocation, no servlet
 * container, matching the convention the other admin resource tests use.
 *
 * <p>The path-confinement tests are the important ones: this endpoint reads files named by the
 * request body, so an unconfined path would be an arbitrary-file-read and directory-enumeration
 * primitive for any admin.
 */
public class ModelDiffResourceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path repositoryRoot;
    private ModelDiffResource resource;

    private static final String SCHEMA = "<Schema name='FoodMart'><Cube name='Sales'>"
            + "<Measure name='Unit Sales' column='us' aggregator='sum'/>"
            + "<Measure name='Store Cost' column='sc' aggregator='sum'/>"
            + "<Dimension name='Product'><Hierarchy><Level name='Product Family' column='pf'/>"
            + "</Hierarchy></Dimension></Cube></Schema>";

    @Before
    public void setUp() throws IOException {
        repositoryRoot = tmp.newFolder("repository", "data").toPath();
        resource = new ModelDiffResource(repositoryRoot);
    }

    private ModelDiffRequest request(String before, String after) {
        ModelDiffRequest req = new ModelDiffRequest();
        req.before = new ModelDiffRequest.Side();
        req.after = new ModelDiffRequest.Side();
        req.before.content = before;
        req.after.content = after;
        return req;
    }

    @Test
    public void diffsTwoInlineModelsAndReportsBrokenReferences() throws IOException {
        Path homes = Files.createDirectories(repositoryRoot.resolve("homes/admin"));
        Files.writeString(
                homes.resolve("trend.saiku"),
                "{\"cube\":{\"name\":\"Sales\"},"
                        + "\"mdx\":\"SELECT {[Measures].[Store Cost]} ON COLUMNS FROM [Sales]\"}",
                StandardCharsets.UTF_8);

        ModelDiffRequest req = request(SCHEMA, SCHEMA.replace("name='Store Cost'", "name='Cost of Goods'"));
        try (Response response = resource.diff("json", req)) {
            assertEquals(200, response.getStatus());
            ModelDiffResponse body = (ModelDiffResponse) response.getEntity();
            assertEquals("MONDRIAN_XML", body.getFormat());
            assertEquals(1, body.getChanges().size());
            assertEquals(1, body.getAffectedFiles());
            assertFalse(body.isClean());
            assertTrue(body.isBreaking());
        }
    }

    @Test
    public void returnsMarkdownOnRequest() {
        ModelDiffRequest req = request(SCHEMA, SCHEMA.replace("name='Store Cost'", "name='Cost of Goods'"));
        try (Response response = resource.diff("markdown", req)) {
            assertEquals(200, response.getStatus());
            String markdown = (String) response.getEntity();
            assertTrue(markdown, markdown.contains("## FoodMart / Sales"));
            assertTrue(markdown, markdown.contains("## Broken references"));
        }
    }

    @Test
    public void acceptsPathsRelativeToTheRepositoryRoot() throws IOException {
        Files.writeString(repositoryRoot.resolve("FoodMart4.xml"), SCHEMA, StandardCharsets.UTF_8);
        Files.writeString(
                repositoryRoot.resolve("FoodMart4.proposed.xml"),
                SCHEMA.replace("name='Store Cost'", "name='Cost of Goods'"),
                StandardCharsets.UTF_8);

        ModelDiffRequest req = new ModelDiffRequest();
        req.before = new ModelDiffRequest.Side();
        req.after = new ModelDiffRequest.Side();
        req.before.path = "FoodMart4.xml";
        req.after.path = "FoodMart4.proposed.xml";
        try (Response response = resource.diff("json", req)) {
            assertEquals(200, response.getStatus());
            ModelDiffResponse body = (ModelDiffResponse) response.getEntity();
            assertEquals(1, body.getChanges().size());
            assertTrue("no repository files exist yet, so nothing is broken", body.isClean());
        }
    }

    @Test
    public void crossFormatDiffIsRejectedWithItsReasonCode() {
        String yaml = "semantic_model:\n- name: F\n  metrics:\n  - name: m\n";
        try (Response response = resource.diff("json", request(SCHEMA, yaml))) {
            assertEquals(400, response.getStatus());
            assertTrue(
                    String.valueOf(response.getEntity()),
                    String.valueOf(response.getEntity()).startsWith("CROSS_FORMAT"));
        }
    }

    @Test
    public void malformedInputIsRejectedWithItsReasonCode() {
        try (Response response = resource.diff("json", request(SCHEMA, "<Schema>"))) {
            assertEquals(400, response.getStatus());
            assertTrue(String.valueOf(response.getEntity()).startsWith("MALFORMED"));
        }
    }

    @Test
    public void aMissingRequestBodyIsRejected() {
        try (Response response = resource.diff("json", null)) {
            assertEquals(400, response.getStatus());
        }
        try (Response response = resource.diff("json", new ModelDiffRequest())) {
            assertEquals(400, response.getStatus());
        }
    }

    @Test
    public void pathsOutsideTheRepositoryRootAreRejected() throws IOException {
        Path secret = tmp.getRoot().toPath().resolve("secret.xml");
        Files.writeString(secret, SCHEMA, StandardCharsets.UTF_8);

        ModelDiffRequest req = new ModelDiffRequest();
        req.before = new ModelDiffRequest.Side();
        req.after = new ModelDiffRequest.Side();
        req.before.content = SCHEMA;
        req.after.path = secret.toString();
        try (Response response = resource.diff("json", req)) {
            assertEquals(400, response.getStatus());
            assertTrue(String.valueOf(response.getEntity()).contains("outside the repository root"));
        }
    }

    @Test
    public void relativeTraversalOutsideTheRepositoryRootIsRejected() {
        ModelDiffRequest req = new ModelDiffRequest();
        req.before = new ModelDiffRequest.Side();
        req.after = new ModelDiffRequest.Side();
        req.before.content = SCHEMA;
        req.after.path = "../../../../etc/passwd";
        try (Response response = resource.diff("json", req)) {
            assertEquals(400, response.getStatus());
        }
    }

    @Test
    public void theRepositoryArgumentIsConfinedToTheRepositoryRootToo() {
        ModelDiffRequest req = request(SCHEMA, SCHEMA);
        req.repository = "../../..";
        try (Response response = resource.diff("json", req)) {
            assertEquals(400, response.getStatus());
        }
    }

    @Test
    public void aRepositorySubdirectoryScansOnlyThatSubtree() throws IOException {
        Path homes = Files.createDirectories(repositoryRoot.resolve("homes/admin"));
        Path other = Files.createDirectories(repositoryRoot.resolve("homes/other"));
        String stale =
                "{\"cube\":{\"name\":\"Sales\"}," + "\"mdx\":\"SELECT {[Measures].[Ghost]} ON COLUMNS FROM [Sales]\"}";
        Files.writeString(homes.resolve("stale.saiku"), stale, StandardCharsets.UTF_8);
        Files.writeString(other.resolve("stale.saiku"), stale, StandardCharsets.UTF_8);

        ModelDiffRequest scoped = request(SCHEMA, SCHEMA);
        scoped.repository = "homes/admin";
        try (Response response = resource.diff("json", scoped)) {
            ModelDiffResponse body = (ModelDiffResponse) response.getEntity();
            assertEquals(1, body.getAffectedFiles());
            // Paths in the report are relative to the directory that was scanned, so a scoped
            // request reports the same file under a shorter name.
            assertEquals("stale.saiku", body.getBrokenReferences().get(0).file());
        }

        try (Response response = resource.diff("json", request(SCHEMA, SCHEMA))) {
            ModelDiffResponse body = (ModelDiffResponse) response.getEntity();
            assertEquals("unscoped, both homes are walked", 2, body.getAffectedFiles());
        }
    }

    @Test
    public void nonAdminsAreRefused() {
        ModelDiffResource guarded = new ModelDiffResource(repositoryRoot);
        UserService userService = org.mockito.Mockito.mock(UserService.class);
        org.mockito.Mockito.when(userService.isAdmin()).thenReturn(false);
        guarded.setUserService(userService);
        try (Response response = guarded.diff("json", request(SCHEMA, SCHEMA))) {
            assertEquals(403, response.getStatus());
        }
    }

    @Test
    public void adminsAreAllowedThrough() {
        ModelDiffResource guarded = new ModelDiffResource(repositoryRoot);
        UserService userService = org.mockito.Mockito.mock(UserService.class);
        org.mockito.Mockito.when(userService.isAdmin()).thenReturn(true);
        guarded.setUserService(userService);
        try (Response response = guarded.diff("json", request(SCHEMA, SCHEMA))) {
            assertEquals(200, response.getStatus());
        }
    }
}
