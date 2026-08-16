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
package org.saiku.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * saiku#1871 — datasource names are no longer decorated with the workspace directory.
 *
 * <p>In single-tenant OSS that directory is always the default {@code unknown}, so
 * {@code foodmart.sds} was surfaced to every user, every URL and every MDX unique name as
 * {@code unknown_foodmart} — a multi-tenant artefact carrying no information.
 *
 * <p>The escape hatch stays for anyone whose own tooling matches on the prefixed spelling beyond
 * what the lookup alias from saiku#1869 already covers.
 */
class FilesystemRepositoryWorkspacePrefixTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty(FilesystemRepositoryManager.WORKSPACE_PREFIX_PROPERTY);
    }

    /** THE change: off unless explicitly asked for. */
    @Test
    void theWorkspacePrefixIsOffByDefault() {
        assertFalse(FilesystemRepositoryManager.isWorkspacePrefixEnabled());
    }

    @Test
    void theLegacyPrefixCanBeRestored() {
        System.setProperty(FilesystemRepositoryManager.WORKSPACE_PREFIX_PROPERTY, "true");

        assertTrue(FilesystemRepositoryManager.isWorkspacePrefixEnabled());
    }

    /** Anything that is not literally true leaves it off — no accidental re-enabling on a typo. */
    @Test
    void onlyAnExplicitTrueEnablesIt() {
        for (String v : new String[] {"false", "", "yes", "1", "TRUE-ish", "no"}) {
            System.setProperty(FilesystemRepositoryManager.WORKSPACE_PREFIX_PROPERTY, v);
            boolean expected = "true".equalsIgnoreCase(v);
            org.junit.jupiter.api.Assertions.assertEquals(
                    expected,
                    FilesystemRepositoryManager.isWorkspacePrefixEnabled(),
                    "unexpected reading of " + FilesystemRepositoryManager.WORKSPACE_PREFIX_PROPERTY + "=" + v);
        }
    }

    /** Case-insensitive, because operators type TRUE as often as true. */
    @Test
    void theFlagIsCaseInsensitive() {
        System.setProperty(FilesystemRepositoryManager.WORKSPACE_PREFIX_PROPERTY, "TRUE");

        assertTrue(FilesystemRepositoryManager.isWorkspacePrefixEnabled());
    }
}
