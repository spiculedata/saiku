/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.mail.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Absolute link construction: trailing-slash-normalised base, URL-encoded params, angle-bracket header. */
public class MailLinkBuilderTest {

    private static final String BASE = "https://analytics.example.com";

    @Test
    public void unconfigured_isNotConfigured_andYieldsNullLinks() {
        MailLinkBuilder b = new MailLinkBuilder((String) null);
        assertFalse(b.isConfigured());
        assertNull(b.unsubscribeUrl("a@example.com", "tok"));
        assertNull(b.unsubscribeHeader("a@example.com", "tok"));
        assertNull(b.confirmUrl("a@example.com", "tok"));
    }

    @Test
    public void configured_buildsUnsubscribeUrl() {
        MailLinkBuilder b = new MailLinkBuilder(BASE);
        assertTrue(b.isConfigured());
        assertEquals(
                BASE + "/rest/saiku/mail/unsubscribe?address=a%40example.com&token=tok",
                b.unsubscribeUrl("a@example.com", "tok"));
    }

    @Test
    public void unsubscribeHeader_isAngleBracketWrapped() {
        MailLinkBuilder b = new MailLinkBuilder(BASE);
        assertEquals(
                "<" + BASE + "/rest/saiku/mail/unsubscribe?address=a%40example.com&token=tok>",
                b.unsubscribeHeader("a@example.com", "tok"));
    }

    @Test
    public void confirmUrl_encodesTokenAndAddress() {
        MailLinkBuilder b = new MailLinkBuilder(BASE);
        assertEquals(BASE + "/rest/saiku/mail/consent/confirm?t=ab%2Bcd&e=x%40y.com", b.confirmUrl("x@y.com", "ab+cd"));
    }

    @Test
    public void trailingSlash_isStripped() {
        MailLinkBuilder b = new MailLinkBuilder(BASE + "///");
        assertEquals(BASE + "/rest/saiku/mail/unsubscribe?address=a%40b.com&token=t", b.unsubscribeUrl("a@b.com", "t"));
    }

    @Test
    public void missingTokenOrAddress_yieldsNull() {
        MailLinkBuilder b = new MailLinkBuilder(BASE);
        assertNull(b.unsubscribeUrl("a@b.com", null));
        assertNull(b.unsubscribeUrl(null, "t"));
        assertNull(b.confirmUrl("a@b.com", " "));
    }

    @Test
    public void resolvesFromEnvOverProp() {
        MailLinkBuilder b = new MailLinkBuilder(
                k -> MailLinkBuilder.ENV_KEY.equals(k) ? "https://env.example.com" : null,
                k -> MailLinkBuilder.PROP_KEY.equals(k) ? "https://prop.example.com" : null);
        assertTrue(b.unsubscribeUrl("a@b.com", "t").startsWith("https://env.example.com/"));
    }
}
