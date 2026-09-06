/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** saiku#1907 (CWE-178): the single username canonicalisation point. */
public class UsernamesTest {

    @Test
    public void canonicalize_lowercases_locale_root_and_is_null_safe() {
        assertEquals("admin", Usernames.canonicalize("Admin"));
        assertEquals("admin", Usernames.canonicalize("ADMIN"));
        assertEquals("jsmith", Usernames.canonicalize("JSmith"));
        assertNull(Usernames.canonicalize(null));
        assertEquals("", Usernames.canonicalize(""));
    }

    @Test
    public void sameUser_is_case_insensitive() {
        assertTrue(Usernames.sameUser("admin", "Admin"));
        assertTrue(Usernames.sameUser("ADMIN", "admin"));
        assertTrue(Usernames.sameUser("bob", "bob"));
    }

    @Test
    public void sameUser_distinguishes_different_users_and_nulls() {
        assertFalse(Usernames.sameUser("admin", "bob"));
        assertFalse("two nulls never match", Usernames.sameUser(null, null));
        assertFalse(Usernames.sameUser("admin", null));
        assertFalse(Usernames.sameUser(null, "admin"));
    }
}
