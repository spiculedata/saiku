/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.security;

import static org.junit.Assert.*;

import java.util.regex.Pattern;
import org.junit.Test;

public class NoReHashPasswordEncoderTest {
    private NoReHashPasswordEncoder encoder = new NoReHashPasswordEncoder();
    private String hash = "$2a$10$X7TU2pKkR6sOCeotubuxjOsmYMCuPbLGfN1/yk2yRoUObAykZ.//K";
    private Pattern pattern;

    public NoReHashPasswordEncoderTest() {
        String regex = "\\A\\$2a?\\$\\d\\d\\$[./0-9A-Za-z]{53}";
        pattern = Pattern.compile(regex);
    }

    @Test
    public void testEncodeReturnsEncoded() {
        String actual = encoder.encode("foo");
        assertTrue(pattern.matcher(actual).matches());
    }

    @Test
    public void testEncoderReturnsUnchanged() {
        String actual = encoder.encode(hash);
        assertEquals(hash, actual);
    }

    @Test
    public void testMatchesReturnsTrue() {
        assertTrue(encoder.matches("foo", hash));
    }
}
