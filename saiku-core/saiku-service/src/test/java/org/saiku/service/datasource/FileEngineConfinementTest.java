/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.service.datasource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** saiku#2320: DuckDB lock-down and H2 file-path confinement at the JdbcUrlPolicy chokepoint. */
public class FileEngineConfinementTest {

    private static final String[] PROPS = {
        "saiku.home",
        FileEngineConfinement.CONFINEMENT_PROPERTY,
        FileEngineConfinement.ALLOWED_ROOTS_PROPERTY,
        FileEngineConfinement.ALLOW_R2_PROPERTY
    };

    private final Properties saved = new Properties();
    private Path home;

    @Before
    public void setUp() throws Exception {
        for (String p : PROPS) {
            String v = System.getProperty(p);
            if (v != null) {
                saved.setProperty(p, v);
            }
            System.clearProperty(p);
        }
        home = Files.createTempDirectory("saiku-home").toRealPath();
        System.setProperty("saiku.home", home.toString());
    }

    @After
    public void tearDown() {
        for (String p : PROPS) {
            System.clearProperty(p);
            if (saved.containsKey(p)) {
                System.setProperty(p, saved.getProperty(p));
            }
        }
    }

    /* ------------------------------------------------------------------ DuckDB */

    @Test
    public void duckDbUrlGetsExternalAccessOffAndConfigurationLocked() {
        String out = JdbcUrlPolicy.confineFileEngines("jdbc:duckdb:/data/w.duckdb");
        assertTrue(out, out.startsWith("jdbc:duckdb:/data/w.duckdb;"));
        assertTrue(out, out.contains(";enable_external_access=false"));
        assertTrue(out, out.contains(";allowed_directories=['" + home + "']"));
        assertTrue(out, out.endsWith(";lock_configuration=true"));
    }

    @Test
    public void duckDbConfinementIsIdempotent() {
        String once = JdbcUrlPolicy.confineFileEngines("jdbc:duckdb:");
        assertEquals(once, JdbcUrlPolicy.confineFileEngines(once));
    }

    @Test
    public void duckDbOptionsWinOverUserSuppliedOnes() {
        String out = JdbcUrlPolicy.confineFileEngines("jdbc:duckdb:;enable_external_access=true");
        // The user-supplied copy is stripped outright, not merely overridden by position —
        // duckdb-java applies options case-insensitively in hash order.
        assertFalse(out, out.contains("enable_external_access=true"));
        assertTrue(out, out.contains("enable_external_access=false"));
    }

    @Test
    public void duckDbConfinementKeysAreStrippedInAnySpelling() {
        for (String supplied : new String[] {
            "jdbc:duckdb:db?ENABLE_EXTERNAL_ACCESS=TRUE&x=1",
            "jdbc:duckdb:db;Lock_Configuration=false;x=1",
            "jdbc:duckdb:db?allowed_directories=['/']&x=1",
            "jdbc:duckdb:db?allowed_paths=/etc;x=1",
            "jdbc:duckdb:db?allowed_configs=*;x=1",
            "jdbc:duckdb:?lock_configuration=false"
        }) {
            String out = JdbcUrlPolicy.confineFileEngines(supplied);
            assertTrue(out, out.endsWith(";lock_configuration=true"));
            assertFalse(supplied, out.contains("external_access=true"));
            assertFalse(supplied, out.contains("Lock_Configuration=false"));
            assertFalse(supplied, out.contains("allowed_directories=['/']"));
            assertFalse(supplied, out.contains("allowed_paths=/etc"));
            assertFalse(supplied, out.contains("allowed_configs=*"));
            if (supplied.contains("x=1")) {
                assertTrue(out, out.contains("x=1")); // other options survive
            }
        }
    }

    @Test
    public void aConfinedDuckDbUrlStillValidates() {
        String confined =
                JdbcUrlPolicy.confineFileEngines("jdbc:duckdb:/data/w.duckdb?x=1&enable_external_access=true");
        JdbcUrlPolicy.validate(confined); // our own defaults are the safe values
        assertEquals(confined, JdbcUrlPolicy.confineFileEngines(confined));
    }

    @Test
    public void duckDbUserConfinementKeysAreRefusedAtValidateTime() {
        for (String url : new String[] {
            "jdbc:duckdb:db?enable_external_access=true",
            "jdbc:duckdb:db;lock_configuration=false",
            "jdbc:duckdb:db?ALLOWED_DIRECTORIES=['/etc']",
            "jdbc:duckdb:db?allowed_paths=/etc",
            "jdbc:duckdb:db?allowed_configs=*",
            "jdbc:duckdb:db?allowed_paths=%2Fetc",
            "jdbc:mondrian:Jdbc=jdbc:duckdb:db;CatalogContent=<x/>;jdbc.enable_external_access=true"
        }) {
            try {
                JdbcUrlPolicy.validate(url);
                fail("expected rejection of " + url);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("confinement"));
            }
        }
    }

    @Test
    public void duckDbSafeUserCopiesOfTheDefaultsPass() {
        JdbcUrlPolicy.validate("jdbc:duckdb:db?enable_external_access=false");
        JdbcUrlPolicy.validate("jdbc:duckdb:db;lock_configuration=true");
        String ours = JdbcUrlPolicy.confineFileEngines("jdbc:duckdb:db");
        String dirs = ours.replaceAll(".*allowed_directories=([^;]*);.*", "$1");
        JdbcUrlPolicy.validate("jdbc:duckdb:db?allowed_directories=" + dirs);
    }

    @Test
    public void duckDbOperatorExtraRootsAreAllowListed() throws Exception {
        Path extra = Files.createTempDirectory("saiku-extra").toRealPath();
        System.setProperty(FileEngineConfinement.ALLOWED_ROOTS_PROPERTY, extra.toString());
        String out = JdbcUrlPolicy.confineFileEngines("jdbc:duckdb:");
        assertTrue(out, out.contains("'" + extra + "'"));
        assertTrue(out, out.contains("'" + home + "'"));
    }

    @Test
    public void duckDbOptOutLeavesUrlUntouched() {
        System.setProperty(FileEngineConfinement.CONFINEMENT_PROPERTY, "false");
        assertEquals("jdbc:duckdb:/x.db", JdbcUrlPolicy.confineFileEngines("jdbc:duckdb:/x.db"));
    }

    @Test
    public void r2UploadUrlIsConfinedUnlessExplicitlyOptedIn() {
        String url = "jdbc:duckdb:r2://bucket/db";
        assertTrue(JdbcUrlPolicy.confineFileEngines(url).contains("enable_external_access=false"));
        System.setProperty(FileEngineConfinement.ALLOW_R2_PROPERTY, "true");
        assertEquals(url, JdbcUrlPolicy.confineFileEngines(url));
    }

    @Test
    public void otherSchemesAreNotRewritten() {
        assertEquals("jdbc:postgresql://h/db", JdbcUrlPolicy.confineFileEngines("jdbc:postgresql://h/db"));
        assertEquals("jdbc:h2:mem:x", JdbcUrlPolicy.confineFileEngines("jdbc:h2:mem:x"));
        assertEquals(null, JdbcUrlPolicy.confineFileEngines(null));
    }

    @Test
    public void mondrianWrappedDuckDbIsConfinedAndStillParsesAsOneJdbcValue() throws Exception {
        String url = "jdbc:mondrian:Jdbc=jdbc:duckdb:/data/w.duckdb;Catalog=file:" + home
                + "/s.xml;JdbcDrivers=org.duckdb.DuckDBDriver";
        String out = JdbcUrlPolicy.confineFileEngines(url);
        JdbcUrlPolicy.validate(out); // the confined form must still pass the policy

        mondrian.olap.Util.PropertyList parsed =
                mondrian.olap.Util.parseConnectString(out.substring("jdbc:mondrian:".length()));
        String jdbc = parsed.get("Jdbc");
        assertTrue(jdbc, jdbc.startsWith("jdbc:duckdb:/data/w.duckdb;enable_external_access=false;"));
        assertTrue(jdbc, jdbc.endsWith(";lock_configuration=true"));
        assertEquals("org.duckdb.DuckDBDriver", parsed.get("JdbcDrivers"));
        assertTrue(parsed.get("Catalog"), parsed.get("Catalog").startsWith("file:"));
    }

    @Test
    public void mondrianWrappedOtherEngineIsNotRewritten() {
        String url = "jdbc:mondrian:Jdbc=jdbc:h2:mem:x;Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver";
        assertEquals(url, JdbcUrlPolicy.confineFileEngines(url));
    }

    @Test
    public void mondrianWrappedQuotedJdbcValueIsConfined() throws Exception {
        // A quoted inner URL — the ';'-bearing spelling Mondrian itself produces — must be read
        // with Mondrian's parser, not with a first-unquoted-match regex.
        String url = "jdbc:mondrian:Jdbc='jdbc:duckdb:/data/w.duckdb;EVENTS=x';Catalog=file:" + home
                + "/s.xml;JdbcDrivers=org.duckdb.DuckDBDriver";
        String out = JdbcUrlPolicy.confineFileEngines(url);
        JdbcUrlPolicy.validate(out);
        mondrian.olap.Util.PropertyList parsed =
                mondrian.olap.Util.parseConnectString(out.substring("jdbc:mondrian:".length()));
        String jdbc = parsed.get("Jdbc");
        assertTrue(jdbc, jdbc.startsWith("jdbc:duckdb:/data/w.duckdb;"));
        assertTrue(jdbc, jdbc.contains("EVENTS=x"));
        assertTrue(jdbc, jdbc.contains("enable_external_access=false"));
        assertTrue(jdbc, jdbc.endsWith(";lock_configuration=true"));
    }

    @Test
    public void mondrianWrapperKeysAroundJdbcDoNotCutTheValue() throws Exception {
        // The end-of-value bound must come from Mondrian's own grammar, not a key list that can
        // lag behind RolapConnectionProperties.
        String url = "jdbc:mondrian:Jdbc=jdbc:duckdb:/data/w.duckdb;Foo=bar;Catalog=file:" + home + "/s.xml";
        String out = JdbcUrlPolicy.confineFileEngines(url);
        JdbcUrlPolicy.validate(out);
        mondrian.olap.Util.PropertyList parsed =
                mondrian.olap.Util.parseConnectString(out.substring("jdbc:mondrian:".length()));
        String jdbc = parsed.get("Jdbc");
        assertTrue(jdbc, jdbc.endsWith(";lock_configuration=true"));
        assertEquals("bar", parsed.get("Foo"));
    }

    @Test
    public void mondrianWrappedDuckDbUserConfinementKeyIsRefused() {
        String url = "jdbc:mondrian:Jdbc='jdbc:duckdb:db;EVENTS=x;lock_configuration=false';Catalog=file:" + home
                + "/s.xml;JdbcDrivers=org.duckdb.DuckDBDriver";
        try {
            JdbcUrlPolicy.validate(url);
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("confinement"));
        }
    }

    /* ------------------------------------------------------------------ H2 */

    @Test
    public void h2FileOutsideSaikuHomeIsRejected() {
        for (String url : new String[] {
            "jdbc:h2:file:/etc/evil",
            "jdbc:h2:/etc/evil;MODE=MySQL",
            "jdbc:h2:nio:/etc/evil",
            "jdbc:h2:split:20:/etc/evil",
            "jdbc:h2:zip:/etc/evil.zip!/db",
            "jdbc:h2:file:" + home + "/../outside",
            "jdbc:h2:%66ile:/etc/evil",
            "jdbc:mondrian:Jdbc=jdbc:h2:/etc/evil;Catalog=file:" + home + "/s.xml;JdbcDrivers=org.h2.Driver"
        }) {
            try {
                JdbcUrlPolicy.validate(url);
                fail("expected rejection of " + url);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("saiku.home"));
            }
        }
    }

    @Test
    public void h2FileUnderSaikuHomeIsAccepted() {
        JdbcUrlPolicy.validate("jdbc:h2:" + home + "/data/foodmart;MODE=MySQL");
        JdbcUrlPolicy.validate("jdbc:h2:file:" + home + "/data/foodmart");
        JdbcUrlPolicy.validate("jdbc:mondrian:Jdbc=jdbc:h2:" + home
                + "/data/foodmart;MODE=MySQL;Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver");
    }

    @Test
    public void h2FileUnderSymlinkedSaikuHomeIsAccepted() throws Exception {
        // saiku#2436: an H2 URL names its database without .mv.db, so the candidate does not exist
        // when it is checked — containment must resolve the nearest existing ancestor to its real
        // path and append the remainder (what H2 itself does). With saiku.home reached through a
        // symlink, every database in the home — the seeded FoodMart included — used to be rejected.
        Path link = home.getParent().resolve("saiku-home-symlink-" + home.getFileName());
        try {
            Files.createSymbolicLink(link, home);
        } catch (UnsupportedOperationException | IOException e) {
            return; // no symlink support (e.g. Windows without privilege) — nothing to assert
        }
        try {
            System.setProperty("saiku.home", link.toString());
            JdbcUrlPolicy.validate("jdbc:h2:" + link + "/data/foodmart;MODE=MySQL");
            JdbcUrlPolicy.validate("jdbc:h2:file:" + link + "/data/foodmart");
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    public void h2MemAndRemoteUrlsAreNotFileUrls() {
        JdbcUrlPolicy.validate("jdbc:h2:mem:test");
        JdbcUrlPolicy.validate("jdbc:h2:mem:;DB_CLOSE_DELAY=-1");
        JdbcUrlPolicy.validate("jdbc:h2:tcp://localhost:9092/warehouse");
        // H2's memory-only file systems and ssl: remote protocol are not disk files either.
        JdbcUrlPolicy.validate("jdbc:h2:memFS:~/test");
        JdbcUrlPolicy.validate("jdbc:h2:memLZF:~/test");
        JdbcUrlPolicy.validate("jdbc:h2:nioMemFS:~/test");
        JdbcUrlPolicy.validate("jdbc:h2:nioMemLZF:~/test");
        JdbcUrlPolicy.validate("jdbc:h2:ssl://localhost:9092/warehouse");
    }

    @Test
    public void h2PrefixMatchingIsCaseSensitiveLikeH2sOwnGrammar() {
        // H2 matches its prefixes case-sensitively: MEM: is a relative FILE path (cwd-dependent),
        // so it must not be waved through as an in-memory database.
        for (String url : new String[] {
            "jdbc:h2:MEM:/etc/evil",
            "jdbc:h2:Mem:/etc/evil",
            "jdbc:h2:TCP://localhost/warehouse",
            "jdbc:h2:SSL://localhost/warehouse",
            "jdbc:h2://shared/data"
        }) {
            try {
                JdbcUrlPolicy.validate(url);
                fail("expected rejection of " + url);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("saiku.home"));
            }
        }
    }

    @Test
    public void h2UnknownFileSystemPrefixesAreRefusedNotCwdRelative() {
        // nio: is not an H2 2.x scheme; any other unknown scheme: prefix would resolve against the
        // working directory — refuse rather than guess.
        for (String url : new String[] {
            "jdbc:h2:nio:/etc/evil",
            "jdbc:h2:nioMapped:/etc/evil",
            "jdbc:h2:async:/etc/evil",
            "jdbc:h2:retry:/etc/evil",
            "jdbc:h2:whatever:/etc/evil"
        }) {
            try {
                JdbcUrlPolicy.validate(url);
                fail("expected rejection of " + url);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("saiku.home"));
            }
        }
    }

    /* ------------------------------------------- SQLite / Derby / HSQLDB */

    @Test
    public void otherFileEnginesAreConfinedToSaikuHome() {
        for (String url : new String[] {
            "jdbc:sqlite:/etc/evil.db",
            "jdbc:sqlite:file:/etc/evil.db",
            "jdbc:sqlite:../evil.db",
            "jdbc:sqlite:file:../evil.db?cache=shared",
            "jdbc:derby:/etc/evil",
            "jdbc:derby:directory:/etc/evil",
            "jdbc:derby:jar:/etc/evil.jar!db",
            "jdbc:derby:jar:(/etc/evil.jar)db",
            "jdbc:derby:directory:../../evil",
            "jdbc:hsqldb:/etc/evil",
            "jdbc:hsqldb:file:/etc/evil",
            "jdbc:hsqldb:../evil",
            "jdbc:mondrian:Jdbc=jdbc:sqlite:/etc/evil.db;Catalog=file:" + home + "/s.xml;JdbcDrivers=org.sqlite.JDBC"
        }) {
            try {
                JdbcUrlPolicy.validate(url);
                fail("expected rejection of " + url);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("saiku.home"));
            }
        }
    }

    @Test
    public void otherFileEnginesKeepTheirInMemoryAndRemoteForms() {
        JdbcUrlPolicy.validate("jdbc:sqlite::memory:");
        JdbcUrlPolicy.validate("jdbc:sqlite:file::memory:?cache=shared");
        JdbcUrlPolicy.validate("jdbc:sqlite:" + home + "/data/warehouse.db");
        JdbcUrlPolicy.validate("jdbc:sqlite:file:" + home + "/data/warehouse.db");
        JdbcUrlPolicy.validate("jdbc:derby:memory:scratch");
        JdbcUrlPolicy.validate("jdbc:derby:classpath:db/warehouse");
        JdbcUrlPolicy.validate("jdbc:derby:" + home + "/data/warehouse;create=true");
        JdbcUrlPolicy.validate("jdbc:derby:directory:" + home + "/data/warehouse");
        JdbcUrlPolicy.validate("jdbc:derby://warehouse.internal:1527/db");
        JdbcUrlPolicy.validate("jdbc:derby:net://warehouse.internal:1527/db");
        JdbcUrlPolicy.validate("jdbc:hsqldb:mem:scratch");
        JdbcUrlPolicy.validate("jdbc:hsqldb:res:/seed/warehouse");
        JdbcUrlPolicy.validate("jdbc:hsqldb:hsql://warehouse.internal:9001/db");
        JdbcUrlPolicy.validate("jdbc:hsqldb:hsqls://warehouse.internal:9002/db");
        JdbcUrlPolicy.validate("jdbc:hsqldb:" + home + "/data/warehouse");
        JdbcUrlPolicy.validate("jdbc:hsqldb:file:" + home + "/data/warehouse");
    }

    @Test
    public void otherFileEnginesRefuseUnknownSubprotocolPrefixes() {
        for (String url : new String[] {
            "jdbc:derby:http://internal/db", "jdbc:derby:sneaky:/etc/evil", "jdbc:hsqldb:sneaky:/etc/evil"
        }) {
            try {
                JdbcUrlPolicy.validate(url);
                fail("expected rejection of " + url);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("saiku.home"));
            }
        }
    }

    @Test
    public void otherFileEnginesFailClosedWithoutSaikuHome() {
        System.clearProperty("saiku.home");
        for (String url : new String[] {
            "jdbc:sqlite:/anywhere/db.db", "jdbc:derby:directory:/anywhere/db", "jdbc:hsqldb:file:/anywhere/db"
        }) {
            try {
                JdbcUrlPolicy.validate(url);
                fail("must fail closed without saiku.home: " + url);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("saiku.home"));
            }
        }
        System.setProperty(FileEngineConfinement.CONFINEMENT_PROPERTY, "false");
        JdbcUrlPolicy.validate("jdbc:sqlite:/anywhere/db.db"); // explicit opt-out
    }

    @Test
    public void otherFileEngineOperatorRootsAreHonoured() throws Exception {
        Path extra = Files.createTempDirectory("saiku-extra").toRealPath();
        System.setProperty(FileEngineConfinement.ALLOWED_ROOTS_PROPERTY, extra.toString());
        JdbcUrlPolicy.validate("jdbc:sqlite:" + extra + "/w.db");
        JdbcUrlPolicy.validate("jdbc:derby:directory:" + extra + "/db");
        JdbcUrlPolicy.validate("jdbc:hsqldb:file:" + extra + "/db");
    }

    @Test
    public void h2OperatorRootAndOptOutAreHonoured() throws Exception {
        Path extra = Files.createTempDirectory("saiku-extra").toRealPath();
        String outside = "jdbc:h2:" + extra + "/db";
        try {
            JdbcUrlPolicy.validate(outside);
            fail("outside path must be rejected by default");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains(extra.toString()));
        }
        System.setProperty(FileEngineConfinement.ALLOWED_ROOTS_PROPERTY, extra.toString());
        JdbcUrlPolicy.validate(outside);

        System.clearProperty(FileEngineConfinement.ALLOWED_ROOTS_PROPERTY);
        System.setProperty(FileEngineConfinement.CONFINEMENT_PROPERTY, "false");
        JdbcUrlPolicy.validate("jdbc:h2:file:/etc/evil");
    }

    @Test
    public void h2FileUrlIsRejectedWhenSaikuHomeIsUnset() {
        System.clearProperty("saiku.home");
        try {
            JdbcUrlPolicy.validate("jdbc:h2:file:/anywhere/db");
            fail("must fail closed without saiku.home");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("saiku.home"));
        }
        JdbcUrlPolicy.validate("jdbc:h2:mem:x"); // not a file URL
        System.setProperty(FileEngineConfinement.CONFINEMENT_PROPERTY, "false");
        JdbcUrlPolicy.validate("jdbc:h2:file:/anywhere/db"); // explicit opt-out
    }

    @Test
    public void duckDbWithoutAnyRootStillLocksExternalAccess() {
        System.clearProperty("saiku.home");
        String out = JdbcUrlPolicy.confineFileEngines("jdbc:duckdb:");
        assertTrue(out, out.contains("enable_external_access=false"));
        assertTrue(out, out.endsWith("lock_configuration=true"));
        assertFalse(out, out.contains("allowed_directories"));

        System.setProperty(FileEngineConfinement.ALLOWED_ROOTS_PROPERTY, home.toString());
        assertTrue(JdbcUrlPolicy.confineFileEngines("jdbc:duckdb:").contains("allowed_directories=['" + home + "']"));
    }
}
