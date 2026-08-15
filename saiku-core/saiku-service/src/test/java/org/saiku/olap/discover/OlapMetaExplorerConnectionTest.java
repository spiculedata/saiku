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
package org.saiku.olap.discover;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.olap4j.OlapConnection;
import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.datasource.IDatasourceManager;

/**
 * saiku#1857 — an unknown connection name must fail as a typed, named error rather than as a raw
 * {@link NullPointerException} several frames downstream.
 *
 * <p>{@code getNativeConnection} used to answer {@code null} for a name it did not recognise, while
 * {@code getNativeCube} in the very same class threw. Not one of the ~25 production call sites
 * null-checked the result — every one dereferences it on the next line — so a typo'd or stale
 * connection name surfaced to the user as {@code Cannot invoke "OlapConnection.setCatalog(String)"
 * because "con" is null}, with no mention of the connection that was actually missing.
 */
class OlapMetaExplorerConnectionTest {

    /** Minimal in-memory connection manager holding a fixed set of names. */
    private static final class StubConnectionManager implements IConnectionManager {

        private final Map<String, OlapConnection> olap = new LinkedHashMap<>();

        StubConnectionManager(String... names) {
            for (String n : names) {
                // A null value is exactly the shape the real manager returns for a datasource that
                // is registered but whose connection failed to open — the name is known, the
                // connection is not available.
                olap.put(n, null);
            }
        }

        @Override
        public void init() {}

        @Override
        public void setDataSourceManager(IDatasourceManager ds) {}

        @Override
        public IDatasourceManager getDataSourceManager() {
            return null;
        }

        @Override
        public void refreshConnection(String name) {}

        @Override
        public void refreshAllConnections() {}

        @Override
        public OlapConnection getOlapConnection(String name) {
            return olap.get(name);
        }

        @Override
        public Map<String, OlapConnection> getAllOlapConnections() {
            return olap;
        }

        @Override
        public ISaikuConnection getConnection(String name) {
            return null;
        }

        @Override
        public Map<String, ISaikuConnection> getAllConnections() {
            return new LinkedHashMap<>();
        }
    }

    @Test
    void anUnknownConnectionThrowsInsteadOfReturningNull() {
        OlapMetaExplorer explorer = new OlapMetaExplorer(new StubConnectionManager("unknown_foodmart"));

        assertThrows(SaikuOlapException.class, () -> explorer.getNativeConnection("designer_e2e"));
    }

    @Test
    void theRejectionNamesTheConnectionThatWasAskedFor() {
        OlapMetaExplorer explorer = new OlapMetaExplorer(new StubConnectionManager("unknown_foodmart"));

        SaikuOlapException e =
                assertThrows(SaikuOlapException.class, () -> explorer.getNativeConnection("designer_e2e"));

        assertNotNull(e.getMessage(), "the rejection carried no message");
        assertTrue(
                e.getMessage().contains("designer_e2e"),
                "the rejection did not name the missing connection: " + e.getMessage());
    }

    @Test
    void theRejectionListsTheConnectionsThatDoExist() {
        OlapMetaExplorer explorer =
                new OlapMetaExplorer(new StubConnectionManager("unknown_foodmart", "unknown_bank"));

        SaikuOlapException e =
                assertThrows(SaikuOlapException.class, () -> explorer.getNativeConnection("designer_e2e"));

        // Without the candidate list the caller cannot tell a typo from a genuinely missing
        // datasource — this is the same self-correction contract the AI validators carry.
        assertTrue(
                e.getMessage().contains("unknown_foodmart") && e.getMessage().contains("unknown_bank"),
                "the rejection did not list the available connections: " + e.getMessage());
    }

    @Test
    void aBlankNameIsRejectedRatherThanLookedUp() {
        OlapMetaExplorer explorer = new OlapMetaExplorer(new StubConnectionManager("unknown_foodmart"));

        assertThrows(SaikuOlapException.class, () -> explorer.getNativeConnection(null));
        assertThrows(SaikuOlapException.class, () -> explorer.getNativeConnection("  "));
    }

    @Test
    void aKnownNameWhoseConnectionIsUnavailableStillFailsTyped() {
        // The stub registers the name but has no live connection behind it. Returning null here
        // was the original defect; the name being known does not make a null safe to hand back.
        OlapMetaExplorer explorer = new OlapMetaExplorer(new StubConnectionManager("unknown_foodmart"));

        SaikuOlapException e =
                assertThrows(SaikuOlapException.class, () -> explorer.getNativeConnection("unknown_foodmart"));

        assertTrue(
                e.getMessage().contains("unknown_foodmart"),
                "the rejection did not name the connection: " + e.getMessage());
    }
}
