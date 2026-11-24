/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class SponsorLinkTest {

    private static final Map<String, String> NONE = Map.of();

    @Test
    public void onByDefault() {
        assertTrue(SponsorLink.enabled(NONE::get, NONE::get));
    }

    @Test
    public void environmentOrPropertyOptsOut() {
        for (String off : List.of("false", "off", "0", "no", " FALSE ")) {
            assertFalse(off, SponsorLink.enabled(Map.of("SAIKU_SPONSOR_LINK", off)::get, NONE::get));
            assertFalse(off, SponsorLink.enabled(NONE::get, Map.of("saiku.sponsorLink", off)::get));
        }
    }

    @Test
    public void blankOrOtherValuesLeaveItOn() {
        assertTrue(SponsorLink.enabled(Map.of("SAIKU_SPONSOR_LINK", "true")::get, NONE::get));
        assertTrue(SponsorLink.enabled(NONE::get, Map.of("saiku.sponsorLink", "")::get));
    }

    @Test
    public void pointsAtTheSpiculedataProfile() {
        assertEquals("https://github.com/sponsors/spiculedata", SponsorLink.URL);
    }
}
