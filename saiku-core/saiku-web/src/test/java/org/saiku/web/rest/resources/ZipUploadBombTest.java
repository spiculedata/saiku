/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.junit.Test;

/**
 * saiku#1165 — zip-bomb / unbounded-decompression hardening for
 * {@code POST /repository/zipupload}. The size guards must reject an oversized
 * archive with a 400 before any entry is written. The #1157 entry-name guard
 * does not bound sizes; this does.
 *
 * <p>Each test shrinks a limit via system property so the FIRST entry trips the
 * guard before {@code saveResource} is ever consulted — so a bare resource
 * instance (no repository wiring) is enough.
 */
public class ZipUploadBombTest {

    @Test
    public void perEntryOverLimitReturns400() throws Exception {
        String prev = System.getProperty("saiku.repo.zipMaxEntryBytes");
        try {
            System.setProperty("saiku.repo.zipMaxEntryBytes", "100");
            byte[] zip = zipWithSingleEntry("ok.txt", bytes(1000));
            Response r = upload(zip);
            assertEquals(400, r.getStatus());
            assertTrue(r.getEntity().toString().contains("per-entry"));
        } finally {
            restore("saiku.repo.zipMaxEntryBytes", prev);
        }
    }

    @Test
    public void totalOverLimitReturns400() throws Exception {
        String prev = System.getProperty("saiku.repo.zipMaxTotalBytes");
        try {
            System.setProperty("saiku.repo.zipMaxTotalBytes", "50");
            // 200 bytes is under the (default) per-entry cap but over the tiny total cap.
            byte[] zip = zipWithSingleEntry("ok.txt", bytes(200));
            Response r = upload(zip);
            assertEquals(400, r.getStatus());
            assertTrue(r.getEntity().toString().contains("total"));
        } finally {
            restore("saiku.repo.zipMaxTotalBytes", prev);
        }
    }

    @Test
    public void tooManyEntriesReturns400() throws Exception {
        String prev = System.getProperty("saiku.repo.zipMaxEntries");
        try {
            System.setProperty("saiku.repo.zipMaxEntries", "0");
            byte[] zip = zipWithSingleEntry("ok.txt", bytes(10));
            Response r = upload(zip);
            assertEquals(400, r.getStatus());
            assertTrue(r.getEntity().toString().contains("too many entries"));
        } finally {
            restore("saiku.repo.zipMaxEntries", prev);
        }
    }

    @Test
    public void readCappedThrowsWhenExceeded() throws Exception {
        try {
            BasicRepositoryResource2.readCapped(new ByteArrayInputStream(bytes(1000)), 100);
            org.junit.Assert.fail("expected ZipLimitExceededException");
        } catch (BasicRepositoryResource2.ZipLimitExceededException expected) {
            // good
        }
    }

    @Test
    public void readCappedReturnsContentWhenWithinLimit() throws Exception {
        byte[] out = BasicRepositoryResource2.readCapped(new ByteArrayInputStream(bytes(50)), 100);
        assertEquals(50, out.length);
    }

    // ---------- helpers ----------

    private static Response upload(byte[] zip) throws Exception {
        FormDataContentDisposition fd =
                FormDataContentDisposition.name("file").fileName("a.zip").build();
        return new BasicRepositoryResource2().uploadArchiveZip(null, new ByteArrayInputStream(zip), fd, "homes/smith");
    }

    private static byte[] bytes(int n) {
        byte[] a = new byte[n];
        java.util.Arrays.fill(a, (byte) 'x');
        return a;
    }

    private static byte[] zipWithSingleEntry(String name, byte[] content) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(content);
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private static void restore(String key, String prev) {
        if (prev == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, prev);
        }
    }
}
