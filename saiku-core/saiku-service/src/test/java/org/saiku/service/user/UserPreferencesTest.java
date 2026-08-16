/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.service.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** saiku#1868 — one user's preferences must never resolve into another user's home. */
class UserPreferencesTest {

    @Test
    void preferencesLiveUnderTheUsersOwnHome() {
        assertEquals("/homes/admin/.preferences.json", UserPreferences.pathFor("admin"));
    }

    /**
     * THE security property. The username is resolved server-side from the security context, but a
     * directory-traversing name must still not be able to walk out of the home it names.
     */
    @Test
    void aTraversingUsernameCannotEscapeItsHome() {
        String path = UserPreferences.pathFor("../../etc/passwd");

        assertTrue(path.startsWith("/homes/"), "escaped the homes root: " + path);
        assertEquals(-1, path.indexOf(".."), "path still contains a traversal: " + path);
    }

    @Test
    void separatorsInAUsernameCannotIntroduceExtraPathSegments() {
        String path = UserPreferences.pathFor("a/b\\c");

        // /homes/<one segment>/.preferences.json — exactly three slashes, no more.
        assertEquals(3, path.chars().filter(c -> c == '/').count(), "extra path segments in " + path);
    }

    /** Realistic usernames survive intact — an email login must not be mangled. */
    @Test
    void ordinaryUsernamesAreLeftAlone() {
        assertEquals(
                "/homes/tom.barber@spicule.co.uk/.preferences.json",
                UserPreferences.pathFor("tom.barber@spicule.co.uk"));
        assertEquals("/homes/user-1_2/.preferences.json", UserPreferences.pathFor("user-1_2"));
    }

    /** Two different users must never collapse onto the same file. */
    @Test
    void differentUsernamesDoNotCollideAfterSanitising() {
        assertNotEquals(UserPreferences.pathFor("bob"), UserPreferences.pathFor("alice"));
        assertNotEquals(UserPreferences.pathFor("a b"), UserPreferences.pathFor("a-b"));
    }

    /**
     * A name made only of unsafe characters would sanitise to a run of underscores that another
     * such name also maps to. Refusing is the only safe answer.
     */
    @Test
    void aUsernameWithNoUsableCharactersIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> UserPreferences.pathFor("///"));
        assertThrows(IllegalArgumentException.class, () -> UserPreferences.pathFor("$$$"));
    }

    @Test
    void aMissingUsernameIsRefusedRatherThanDefaulted() {
        assertThrows(IllegalArgumentException.class, () -> UserPreferences.pathFor(null));
        assertThrows(IllegalArgumentException.class, () -> UserPreferences.pathFor("  "));
    }
}
