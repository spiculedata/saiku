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
package org.saiku.service.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * saiku#1864 — reading a datasource, changing a field and writing it back must land on the same
 * datasource, not create a second one under a doubly-decorated name.
 */
class DatasourceNameDecorationTest {

    @Test
    void aDecoratedNameIsStrippedBackToWhatWasStored() {
        assertEquals("designer_e2e", DatasourceNameDecoration.undecorate("unknown_designer_e2e", "unknown"));
    }

    @Test
    void anUndecoratedNameIsLeftAlone() {
        assertEquals("designer_e2e", DatasourceNameDecoration.undecorate("designer_e2e", "unknown"));
    }

    /**
     * The round trip is the whole point: decorate → undecorate → decorate must be stable, or every
     * edit spawns another prefix (this is exactly how {@code unknown_unknown_designer_e2e} appeared).
     */
    @Test
    void theRoundTripIsStableAcrossRepeatedEdits() {
        String stored = "designer_e2e";
        String workspace = "unknown";

        for (int i = 0; i < 5; i++) {
            String asRead = workspace + "_" + stored; // what the load path decorates it to
            stored = DatasourceNameDecoration.undecorate(asRead, workspace);
        }

        assertEquals("designer_e2e", stored, "the name drifted across repeated read-modify-writes");
    }

    /** Only ONE prefix comes off — a datasource really called `unknown_foo` keeps its own word. */
    @Test
    void onlyASinglePrefixIsRemoved() {
        assertEquals("unknown_foo", DatasourceNameDecoration.undecorate("unknown_unknown_foo", "unknown"));
    }

    /** A different workspace's prefix is not ours to strip. */
    @Test
    void aPrefixFromAnotherWorkspaceIsNotStripped() {
        assertEquals("tenant_b_sales", DatasourceNameDecoration.undecorate("tenant_b_sales", "unknown"));
    }

    /** The prefix alone is a real name, not a decoration wrapping nothing. */
    @Test
    void thePrefixOnItsOwnIsLeftIntact() {
        assertEquals("unknown_", DatasourceNameDecoration.undecorate("unknown_", "unknown"));
    }

    @Test
    void aBlankWorkspaceMeansThereIsNothingToStrip() {
        assertEquals("unknown_foo", DatasourceNameDecoration.undecorate("unknown_foo", ""));
    }

    // ── decorate: the inverse, used to key the in-memory cache and to alias lookups (saiku#1869)

    @Test
    void decorateAddsThePrefixAStoredNameWillBeSurfacedUnder() {
        assertEquals("unknown_designer_e2e", DatasourceNameDecoration.decorate("designer_e2e", "unknown"));
    }

    /** Idempotent — decorating an already-decorated name must not stack a second prefix. */
    @Test
    void decorateIsIdempotent() {
        assertEquals("unknown_designer_e2e", DatasourceNameDecoration.decorate("unknown_designer_e2e", "unknown"));
    }

    /**
     * decorate and undecorate are inverses for every name that can actually be STORED.
     *
     * <p>Since saiku#1864 the save path strips the decoration before writing, so a stored name never
     * begins with the workspace prefix — which is what makes the pair well-defined.
     */
    @Test
    void decorateAndUndecorateAreInversesForStorableNames() {
        for (String stored : new String[] {"foodmart", "designer_e2e", "a.b-c", "unknownish"}) {
            String decorated = DatasourceNameDecoration.decorate(stored, "unknown");
            assertEquals(
                    stored, DatasourceNameDecoration.undecorate(decorated, "unknown"), "round trip lost " + stored);
        }
    }

    /**
     * The one name the pair CANNOT round-trip, documented rather than wished away: a datasource
     * literally called {@code unknown_foo} is indistinguishable from {@code foo} decorated, so
     * decorate leaves it alone (no double prefix) while undecorate strips it to {@code foo}.
     *
     * <p>Harmless in practice — the save-side strip means such a name is never stored — and the
     * alias lookup tries both spellings anyway, so either reading resolves.
     */
    @Test
    void aNameThatLooksAlreadyDecoratedIsAmbiguousAndIsNotRoundTripped() {
        assertEquals("unknown_foo", DatasourceNameDecoration.decorate("unknown_foo", "unknown"));
        assertEquals("foo", DatasourceNameDecoration.undecorate("unknown_foo", "unknown"));
    }

    @Test
    void decorateToleratesNulls() {
        assertNull(DatasourceNameDecoration.decorate(null, "unknown"));
        assertEquals("foo", DatasourceNameDecoration.decorate("foo", null));
        assertEquals("foo", DatasourceNameDecoration.decorate("foo", ""));
    }

    @Test
    void nullsAreToleratedRatherThanThrowing() {
        assertNull(DatasourceNameDecoration.undecorate(null, "unknown"));
        assertEquals("foo", DatasourceNameDecoration.undecorate("foo", null));
    }
}
