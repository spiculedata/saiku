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
        assertTrue(out, out.indexOf("enable_external_access=false") > out.indexOf("enable_external_access=true"));
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
    public void h2MemAndRemoteUrlsAreNotFileUrls() {
        JdbcUrlPolicy.validate("jdbc:h2:mem:test");
        JdbcUrlPolicy.validate("jdbc:h2:mem:;DB_CLOSE_DELAY=-1");
        JdbcUrlPolicy.validate("jdbc:h2:tcp://localhost:9092/warehouse");
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
