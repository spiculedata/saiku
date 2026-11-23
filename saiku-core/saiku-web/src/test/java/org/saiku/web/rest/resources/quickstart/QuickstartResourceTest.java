/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.quickstart;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.schema.generate.quickstart.QuickstartIngestService;
import org.saiku.service.user.UserService;

/**
 * Direct-method-invocation tests for {@link QuickstartResource}, mirroring the no-servlet-container
 * style {@code SchemaGeneratorResourceTest} uses for its sibling resource.
 */
public class QuickstartResourceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static InputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    private QuickstartResource newResource() {
        return new QuickstartResource(new QuickstartIngestService(tmp.getRoot().toPath()));
    }

    @Test
    public void successfulUploadReturns200WithConnectionInfo() {
        QuickstartResource resource = newResource();

        Response response = resource.upload(stream("amount,region\n10,east\n20,west\n"), null, "sales");

        assertEquals(200, response.getStatus());
        QuickstartUploadResponse body = (QuickstartUploadResponse) response.getEntity();
        assertEquals("sales", body.tableName());
        assertEquals(2, body.rowCount());
        assertEquals("org.h2.Driver", body.driver());
        assertTrue(body.jdbcUrl().startsWith("jdbc:h2:"));
        assertEquals(2, body.columns().size());
    }

    @Test
    public void missingFilePartReturns400() {
        QuickstartResource resource = newResource();

        Response response = resource.upload(null, null, "sales");

        assertEquals(400, response.getStatus());
    }

    @Test
    public void malformedCsvReturns400WithAUsableMessage() {
        QuickstartResource resource = newResource();

        Response response = resource.upload(stream(""), null, "sales");

        assertEquals(400, response.getStatus());
        assertNotNull(response.getEntity());
        assertTrue(response.getEntity().toString().toLowerCase().contains("empty"));
    }

    @Test
    public void nonAdminUserIsForbidden() {
        QuickstartResource resource = newResource();
        resource.setUserService(new StubUserService(false));

        Response response = resource.upload(stream("a\n1\n"), null, "t");

        assertEquals(403, response.getStatus());
    }

    @Test
    public void adminUserIsAllowedThrough() {
        QuickstartResource resource = newResource();
        resource.setUserService(new StubUserService(true));

        Response response = resource.upload(stream("a\n1\n"), null, "t");

        assertEquals(200, response.getStatus());
    }

    private static final class StubUserService extends UserService {
        private final boolean admin;

        StubUserService(boolean admin) {
            this.admin = admin;
        }

        @Override
        public boolean isAdmin() {
            return admin;
        }
    }
}
