/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util;

import java.util.Locale;
import java.util.function.Function;

/**
 * saiku#2577 — Saiku has no paid edition; it is supported through GitHub Sponsors. The SPA's
 * top bar and the launcher's startup banner point there unless the operator opts out (for a
 * white-label or locked-down deployment) with {@code SAIKU_SPONSOR_LINK=false|off|0|no} or
 * {@code -Dsaiku.sponsorLink=false|off|0|no}. A blank or unrecognised value leaves it on.
 */
public final class SponsorLink {

    /** The spiculedata GitHub Sponsors profile. */
    public static final String URL = "https://github.com/sponsors/spiculedata";

    private SponsorLink() {}

    /** Whether the sponsor link is shown in this JVM's environment. */
    public static boolean enabled() {
        return enabled(System::getenv, System::getProperty);
    }

    /** Whether the sponsor link is shown, given environment and system-property lookups. */
    public static boolean enabled(Function<String, String> env, Function<String, String> props) {
        return !isOptOut(env.apply("SAIKU_SPONSOR_LINK")) && !isOptOut(props.apply("saiku.sponsorLink"));
    }

    private static boolean isOptOut(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("false") || v.equals("off") || v.equals("0") || v.equals("no");
    }
}
