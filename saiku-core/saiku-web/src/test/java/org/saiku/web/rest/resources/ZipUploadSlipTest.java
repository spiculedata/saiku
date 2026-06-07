/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.junit.Test;

/**
 * saiku#1157 — zip-slip defense-in-depth for {@code POST /repository/zipupload}.
 * The entry-name guard must reject traversal / absolute archive entries before
 * {@code saveResource} runs, independent of the downstream
 * {@code resolveWithinDatadir} guard.
 */
public class ZipUploadSlipTest {

    @Test
    public void rejectsParentTraversalEntry() {
        assertTrue(BasicRepositoryResource2.isUnsafeZipEntryName("../../etc/passwd"));
    }

    @Test
    public void rejectsLeadingSlashAbsolute() {
        assertTrue(BasicRepositoryResource2.isUnsafeZipEntryName("/etc/passwd"));
    }

    @Test
    public void rejectsBackslashTraversal() {
        assertTrue(BasicRepositoryResource2.isUnsafeZipEntryName("..\\..\\windows\\system32\\drivers\\etc\\hosts"));
    }

    @Test
    public void rejectsWindowsDriveAbsolute() {
        assertTrue(BasicRepositoryResource2.isUnsafeZipEntryName("C:\\windows\\evil"));
        assertTrue(BasicRepositoryResource2.isUnsafeZipEntryName("c:/windows/evil"));
    }

    @Test
    public void rejectsLeadingBackslashUnc() {
        assertTrue(BasicRepositoryResource2.isUnsafeZipEntryName("\\\\server\\share\\x"));
    }

    @Test
    public void rejectsBlankOrNull() {
        assertTrue(BasicRepositoryResource2.isUnsafeZipEntryName(null));
        assertTrue(BasicRepositoryResource2.isUnsafeZipEntryName("   "));
    }

    @Test
    public void rejectsMidPathTraversalSegment() {
        assertTrue(BasicRepositoryResource2.isUnsafeZipEntryName("homes/smith/../../../etc/x"));
    }

    @Test
    public void allowsNormalRelativeNames() {
        assertFalse(BasicRepositoryResource2.isUnsafeZipEntryName("report.saiku"));
        assertFalse(BasicRepositoryResource2.isUnsafeZipEntryName("homes/smith/report.saiku"));
        assertFalse(BasicRepositoryResource2.isUnsafeZipEntryName("nested/dir/chart.json"));
        // ".." embedded inside a name (not a standalone path segment) is fine.
        assertFalse(BasicRepositoryResource2.isUnsafeZipEntryName("a..b/file.txt"));
    }

    @Test
    public void endpointReturns400AndWritesNothingOnTraversalEntry() throws Exception {
        byte[] zip = zipWithSingleEntry("../../etc/passwd", "pwned");
        FormDataContentDisposition fd =
                FormDataContentDisposition.name("file").fileName("evil.zip").build();

        // No repository wiring on the resource: the guard must fire and return
        // 400 BEFORE saveResource is ever consulted, so a bare instance is enough.
        Response r =
                new BasicRepositoryResource2().uploadArchiveZip(null, new ByteArrayInputStream(zip), fd, "homes/smith");

        assertEquals(400, r.getStatus());
        assertTrue(
                "response names the offending entry", r.getEntity().toString().contains("../../etc/passwd"));
    }

    private static byte[] zipWithSingleEntry(String name, String content) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }
}
