/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** saiku#2348 (b): a user-supplied export name must not shape the Content-Disposition header. */
public class ContentDispositionsTest {

    @Test
    public void attachment_sanitisesAndQuotes() {
        String header = ContentDispositions.attachment("a\"b;c/d\\e\r\nX-Injected: 1", "png");

        assertEquals(
                "attachment; filename=\"abcdeX-Injected 1.png\"; filename*=UTF-8''abcdeX-Injected%201.png", header);
        assertFalse(header.contains("\r"));
        assertFalse(header.contains("\n"));
    }

    @Test
    public void attachment_plainNameKeepsItsExtension() {
        assertEquals(
                "attachment; filename=\"report.csv\"; filename*=UTF-8''report.csv",
                ContentDispositions.attachment("report", "csv"));
    }

    @Test
    public void attachment_fullFilenameIsSanitisedWithoutAddingAnExtension() {
        assertEquals(
                "attachment; filename=\"saiku-export.xlsx\"; filename*=UTF-8''saiku-export.xlsx",
                ContentDispositions.attachment("saiku-export.xlsx"));
    }

    @Test
    public void attachment_stripsLeadingDotsAndPathTraversal() {
        String header = ContentDispositions.attachment("../../.hidden", "txt");
        assertTrue(header, header.startsWith("attachment; filename=\"hidden.txt\""));
    }

    @Test
    public void attachment_nonAsciiGoesInTheExtendedParameterOnly() {
        String header = ContentDispositions.attachment("Année", "csv");
        assertEquals("attachment; filename=\"Anne.csv\"; filename*=UTF-8''Ann%C3%A9e.csv", header);
    }

    @Test
    public void attachment_blankFallsBackToADefault() {
        assertEquals(
                "attachment; filename=\"download.csv\"; filename*=UTF-8''download.csv",
                ContentDispositions.attachment("  ///  ", "csv"));
        assertEquals(
                "attachment; filename=\"download.csv\"; filename*=UTF-8''download.csv",
                ContentDispositions.attachment(null, "csv"));
    }

    @Test
    public void attachment_capsTheLength() {
        String header = ContentDispositions.attachment("x".repeat(500), "csv");
        String ascii = header.substring(header.indexOf('"') + 1, header.indexOf("\";"));
        assertTrue(ascii, ascii.length() <= ContentDispositions.MAX_NAME_LENGTH + ".csv".length());
        assertTrue(ascii.endsWith(".csv"));
    }
}
