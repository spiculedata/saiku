/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.datasource;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.*;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.repository.ScopedRepo;
import org.springframework.security.web.session.HttpSessionCreatedEvent;

public class RepositoryDatasourceManagerTest {
    private static final String JACKRABBIT = "jackrabbit";
    private static final String CLASSPATH = "classpath";

    private RepositoryDatasourceManager rdManager;

    @Before
    public void init() {
        rdManager = new RepositoryDatasourceManager();
    }

    @Test
    public void testCleanse() {
        assertEquals("c:/temp/", rdManager.cleanse("c:\\temp"));
        assertEquals("/opt/saikurepo/", rdManager.cleanse("/opt/saikurepo"));
        assertEquals("c:/temp/data/", rdManager.cleanse("c:\\temp/data////"));
        assertEquals("/opt/saikurepo/home/", rdManager.cleanse("//opt/saikurepo//home"));
    }

    @Test
    public void testGetDatadirJackrabbit() {
        rdManager.setType(JACKRABBIT);
        assertEquals("/", rdManager.getDatadir());
    }

    @Test
    public void testGetDatadirClasspathNonWorkspaced() {
        rdManager.setType(CLASSPATH);
        rdManager.setWorkspaces("false");
        rdManager.setDatadir("c:\\temp\\saikurepo");
        assertEquals("c:/temp/saikurepo/", rdManager.getDatadir());
    }

    @Test
    public void testGetDatadirClasspathWorkspaced() {
        // Configuring session attributes
        Map<String, Object> session = new HashMap<>();
        session.put(RepositoryDatasourceManager.ORBIS_WORKSPACE_DIR, "workspaces");

        rdManager.setType(CLASSPATH);
        rdManager.setWorkspaces("true");
        rdManager.setSessionRegistry(createScopedRepo(session));
        rdManager.setDatadir("c:\\temp\\saikurepo");

        assertEquals("c:/temp/saikurepo/workspaces/", rdManager.getDatadir());
    }

    @Test
    public void testAddDatasource() throws Exception {
        MockConnectionManager cManager = new MockConnectionManager();
        MockRepositoryManager rManager = new MockRepositoryManager();

        // Configuring session attributes
        Map<String, Object> session = new HashMap<>();
        session.put(RepositoryDatasourceManager.ORBIS_WORKSPACE_DIR, "workspace");

        rdManager.setConnectionManager(cManager);
        rdManager.setRepositoryManager(rManager);
        rdManager.setType(CLASSPATH);
        rdManager.setWorkspaces("true");
        rdManager.setSessionRegistry(createScopedRepo(session));
        rdManager.setDatadir("c:\\temp\\repo");

        SaikuDatasource ds = new SaikuDatasource() {
            @Override
            public Type getType() {
                return Type.OLAP;
            }

            @Override
            public String getName() {
                return "MOCK_DATA_MDYYYY";
            }

            @Override
            public Properties getProperties() {
                Properties props = new Properties();
                props.setProperty("driver", "mondrian.olap4j.MondrianOlap4jDriver");
                props.setProperty(
                        "location",
                        "jdbc:mondrian:Jdbc=jdbc:calcite:model=c://temp/repo/workspace_bruno//datasources/B_MOCK_DATA_MDYYYY-csv.json;Catalog=mondrian://datasources/B_MOCK_DATA_MDYYYY.xml;JdbcDrivers=org.apache.calcite.jdbc.Driver;");
                props.setProperty("username", "bruno");
                props.setProperty("password", "bruno");
                props.setProperty("id", "b5ef4927-63e3-4d9c-b7dc-905fff8841f8");
                props.setProperty("security.enabled", "false");
                props.setProperty("type", "OLAP");
                props.setProperty("csv", "true");

                return props;
            }
        };

        rdManager.addDatasource(ds);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddDatasourceRejectsPathTraversalName() throws Exception {
        // saiku#1906: a name carrying ../ segments must never reach the file-write branches
        // (csv json / workspace mondrian catalog / .sds descriptor) that key off ds.getName().
        // Validation fires immediately after `new DataSource(datasource)`, before any manager
        // wiring is touched, so minimal properties/no wiring is fine here.
        SaikuDatasource ds = new SaikuDatasource() {
            @Override
            public Type getType() {
                return Type.OLAP;
            }

            @Override
            public String getName() {
                return "../../evil";
            }

            @Override
            public Properties getProperties() {
                return new Properties();
            }
        };

        rdManager.addDatasource(ds);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddDatasourceRejectsNameExceedingUtf8ByteCap() throws Exception {
        // saiku#1906 SEC follow-up (data loss): DATASOURCE_NAME_PATTERN caps at 128 Unicode
        // CODE POINTS, which a CJK/astral name can satisfy while still blowing past the
        // 200-UTF-8-byte cap validateDatasourceName also enforces -- needed because ext4's
        // NAME_MAX is 255 bytes, and saveDataSource swallows the resulting IOException
        // instead of surfacing it, so an unvalidated over-long name would 200 OK on the REST
        // call and then silently vanish on the next restart. This name is 70 Hiragana
        // characters -- well under the 128-code-point limit -- but is 210 UTF-8 bytes (3
        // bytes/char), over the 200-byte cap.
        String longCjkName = "あ".repeat(70);

        SaikuDatasource ds = new SaikuDatasource() {
            @Override
            public Type getType() {
                return Type.OLAP;
            }

            @Override
            public String getName() {
                return longCjkName;
            }

            @Override
            public Properties getProperties() {
                return new Properties();
            }
        };

        rdManager.addDatasource(ds);
    }

    @Test
    public void testAddDatasourceAllowsInternalSpacesInName() throws Exception {
        // saiku#1906 SEC follow-up: the original version of this test only asserted "does
        // not throw IllegalArgumentException" against an unwired manager, which would have
        // passed even if the write silently failed downstream for an unrelated reason (a
        // swallowed exception passes for the wrong reason). Wire the manager the same way
        // testAddDatasource() does and assert the .sds file actually lands under the name
        // with its internal space intact.
        MockConnectionManager cManager = new MockConnectionManager();
        MockRepositoryManager rManager = new MockRepositoryManager();

        Map<String, Object> session = new HashMap<>();
        session.put(RepositoryDatasourceManager.ORBIS_WORKSPACE_DIR, "workspace");

        rdManager.setConnectionManager(cManager);
        rdManager.setRepositoryManager(rManager);
        rdManager.setType(CLASSPATH);
        rdManager.setWorkspaces("true");
        rdManager.setSessionRegistry(createScopedRepo(session));
        rdManager.setDatadir("c:\\temp\\repo");

        SaikuDatasource ds = new SaikuDatasource() {
            @Override
            public Type getType() {
                return Type.OLAP;
            }

            @Override
            public String getName() {
                return "My Sales DB";
            }

            @Override
            public Properties getProperties() {
                Properties props = new Properties();
                props.setProperty("driver", "mondrian.olap4j.MondrianOlap4jDriver");
                props.setProperty(
                        "location", "jdbc:mondrian:Jdbc=jdbc:h2:mem:test;Catalog=mondrian://My Sales DB.xml;");
                props.setProperty("username", "bruno");
                props.setProperty("password", "bruno");
                props.setProperty("id", "b5ef4927-63e3-4d9c-b7dc-905fff8841f8");
                props.setProperty("security.enabled", "false");
                props.setProperty("type", "OLAP");

                return props;
            }
        };

        rdManager.addDatasource(ds);

        assertNotNull(
                "datasource name with internal spaces must be written to disk (the allowlist must not reject it)",
                rManager.getDataSource("/datasources/My Sales DB.sds"));
    }

    @Test
    public void testAddDatasourceAllowsAccentedAndParenthesizedName() throws Exception {
        // saiku#1906 SEC follow-up: the allowlist widened from ASCII-only to Unicode
        // letters/digits plus parens so real, already-stored datasource names (accented /
        // international, or parenthesised) don't start 500ing on re-save. Prove it end to
        // end, the same way testAddDatasourceAllowsInternalSpacesInName does.
        MockConnectionManager cManager = new MockConnectionManager();
        MockRepositoryManager rManager = new MockRepositoryManager();

        Map<String, Object> session = new HashMap<>();
        session.put(RepositoryDatasourceManager.ORBIS_WORKSPACE_DIR, "workspace");

        rdManager.setConnectionManager(cManager);
        rdManager.setRepositoryManager(rManager);
        rdManager.setType(CLASSPATH);
        rdManager.setWorkspaces("true");
        rdManager.setSessionRegistry(createScopedRepo(session));
        rdManager.setDatadir("c:\\temp\\repo");

        SaikuDatasource ds = new SaikuDatasource() {
            @Override
            public Type getType() {
                return Type.OLAP;
            }

            @Override
            public String getName() {
                return "Ventes Été (EU)";
            }

            @Override
            public Properties getProperties() {
                Properties props = new Properties();
                props.setProperty("driver", "mondrian.olap4j.MondrianOlap4jDriver");
                props.setProperty(
                        "location", "jdbc:mondrian:Jdbc=jdbc:h2:mem:test;Catalog=mondrian://Ventes Ete (EU).xml;");
                props.setProperty("username", "bruno");
                props.setProperty("password", "bruno");
                props.setProperty("id", "b5ef4927-63e3-4d9c-b7dc-905fff8841f8");
                props.setProperty("security.enabled", "false");
                props.setProperty("type", "OLAP");

                return props;
            }
        };

        rdManager.addDatasource(ds);

        assertNotNull(
                "an accented, parenthesised datasource name must be accepted by the allowlist and written to disk",
                rManager.getDataSource("/datasources/Ventes Été (EU).sds"));
    }

    @Test
    public void testDatasourceProcessorAdded() throws Exception {
        MockConnectionManager cManager = new MockConnectionManager();
        MockRepositoryManager rManager = new MockRepositoryManager();

        // Configuring session attributes
        Map<String, Object> session = new HashMap<>();
        session.put(RepositoryDatasourceManager.ORBIS_WORKSPACE_DIR, "workspace");

        rdManager.setConnectionManager(cManager);
        rdManager.setRepositoryManager(rManager);
        rdManager.setType(CLASSPATH);
        rdManager.setWorkspaces("true");
        rdManager.setSessionRegistry(createScopedRepo(session));
        rdManager.setDatadir("c:\\temp\\saikurepo");
        rdManager.setExternalPropertiesFile("/does/not/exist");
        rdManager.setDatasourceProcessor("org.example.DatasourceProcessor");

        SaikuDatasource ds = new SaikuDatasource() {
            @Override
            public Type getType() {
                return Type.OLAP;
            }

            @Override
            public String getName() {
                return "MOCK_DATA_MDYYYY";
            }

            @Override
            public Properties getProperties() {
                Properties props = new Properties();
                props.setProperty("driver", "mondrian.olap4j.MondrianOlap4jDriver");
                props.setProperty(
                        "location",
                        "jdbc:mondrian:Jdbc=jdbc:calcite:model=c://temp/saikurepo/datasources/B_MOCK_DATA_MDYYYY-csv.json;Catalog=mondrian://datasources/B_MOCK_DATA_MDYYYY.xml;JdbcDrivers=org.apache.calcite.jdbc.Driver;");
                props.setProperty("username", "bruno");
                props.setProperty("password", "bruno");
                props.setProperty("id", "b5ef4927-63e3-4d9c-b7dc-905fff8841f8");
                props.setProperty("security.enabled", "false");
                props.setProperty("type", "OLAP");
                props.setProperty("csv", "true");

                return props;
            }
        };

        rdManager.addDatasource(ds);

        rdManager.onApplicationEvent(new HttpSessionCreatedEvent(new MockHttpSession(session)));

        Properties actual = rdManager.getDatasource("MOCK_DATA_MDYYYY").getProperties();
        assertEquals("org.example.DatasourceProcessor", actual.getProperty(ISaikuConnection.DATASOURCE_PROCESSORS));
    }

    @Test
    public void testConnectionProcessorAdded() throws Exception {
        MockConnectionManager cManager = new MockConnectionManager();
        MockRepositoryManager rManager = new MockRepositoryManager();

        // Configuring session attributes
        Map<String, Object> session = new HashMap<>();
        session.put(RepositoryDatasourceManager.ORBIS_WORKSPACE_DIR, "workspace");

        rdManager.setConnectionManager(cManager);
        rdManager.setRepositoryManager(rManager);
        rdManager.setType(CLASSPATH);
        rdManager.setWorkspaces("true");
        rdManager.setSessionRegistry(createScopedRepo(session));
        rdManager.setDatadir("c:\\temp\\saikurepo");
        rdManager.setExternalPropertiesFile("/does/not/exist");
        rdManager.setConnectionProcessor("org.example.ConnectionProcessor");

        SaikuDatasource ds = new SaikuDatasource() {
            @Override
            public Type getType() {
                return Type.OLAP;
            }

            @Override
            public String getName() {
                return "MOCK_DATA_MDYYYY";
            }

            @Override
            public Properties getProperties() {
                Properties props = new Properties();
                props.setProperty("driver", "mondrian.olap4j.MondrianOlap4jDriver");
                props.setProperty(
                        "location",
                        "jdbc:mondrian:Jdbc=jdbc:calcite:model=c://temp/saikurepo/datasources/B_MOCK_DATA_MDYYYY-csv.json;Catalog=mondrian://datasources/B_MOCK_DATA_MDYYYY.xml;JdbcDrivers=org.apache.calcite.jdbc.Driver;");
                props.setProperty("username", "bruno");
                props.setProperty("password", "bruno");
                props.setProperty("id", "b5ef4927-63e3-4d9c-b7dc-905fff8841f8");
                props.setProperty("security.enabled", "false");
                props.setProperty("type", "OLAP");
                props.setProperty("csv", "true");

                return props;
            }
        };

        rdManager.addDatasource(ds);

        rdManager.onApplicationEvent(new HttpSessionCreatedEvent(new MockHttpSession(session)));

        Properties actual = rdManager.getDatasource("MOCK_DATA_MDYYYY").getProperties();
        assertEquals("org.example.ConnectionProcessor", actual.getProperty(ISaikuConnection.CONNECTION_PROCESSORS));
    }

    // ---------------------------------------------------------------------------------------
    // saiku#1932: the CSV-datasource branch interpolated a location-derived path (and the
    // datasource name) unescaped into a hand-built Calcite model JSON, and concatenated the
    // datadir onto it with no containment check.
    // ---------------------------------------------------------------------------------------

    @Test
    public void testJsonEscapeNeutralisesQuoteBreakout() {
        // A raw `'` would terminate the quoted model JSON string and let the rest be read as a
        // further model key (e.g. an injected `factory:`). Every quote and backslash must be
        // escaped, and control characters must become a backslash-u escape (avatica's JsonReader
        // rejects any other raw control character inside a string).
        assertEquals("a\\'b", RepositoryDatasourceManager.jsonEscape("a'b"));
        assertEquals("a\\\"b", RepositoryDatasourceManager.jsonEscape("a\"b"));
        assertEquals("a\\\\b", RepositoryDatasourceManager.jsonEscape("a\\b"));
        assertEquals("a\\nb", RepositoryDatasourceManager.jsonEscape("a\nb"));
        assertEquals("a\\u0000b", RepositoryDatasourceManager.jsonEscape("a" + (char) 0x00 + "b"));
        assertEquals("a\\u001fb", RepositoryDatasourceManager.jsonEscape("a" + (char) 0x1f + "b"));
        assertEquals("plain/path.csv", RepositoryDatasourceManager.jsonEscape("plain/path.csv"));
    }

    @Test
    public void testAddDatasourceEscapesQuoteInCsvPath() throws Exception {
        // End-to-end: a `'` in the CSV path must reach the persisted Calcite model JSON escaped,
        // so the model still parses as ONE operand string rather than being broken open by the
        // attacker-controlled remainder.
        String json = addCsvDatasource("c://temp/repo/x';factory: 'evil.json", "MOCK_CSV_QUOTE");

        assertNotNull("the csv model json must be written", json);
        assertTrue("the injected quote must be escaped, not raw: " + json, json.contains("x\\'"));
        assertFalse(
                "the injected `factory:` must not appear as a model key of its own: " + json,
                json.contains("factory: 'evil.json"));
        // ...and the schema factory the code itself writes is still there exactly once.
        assertTrue(json.contains("factory: 'org.apache.calcite.adapter.csv.CsvTableFactory'"));
    }

    @Test
    public void testAddDatasourceRejectsCsvPathEscapingDatadir() throws Exception {
        // A `..` segment that normalises outside the datadir must be refused outright rather
        // than interpolated into the model JSON as an out-of-root read target.
        try {
            addCsvDatasource("c://temp/repo/../../../../etc/passwd", "MOCK_CSV_TRAVERSAL");
            fail("a CSV location escaping the datadir must be rejected");
        } catch (IllegalArgumentException expected) {
            // Fail closed.
            assertTrue(expected.getMessage().contains("traversal"));
        }
    }

    @Test
    public void testAddDatasourceNormalisesInnerDotDotInCsvPath() throws Exception {
        // A `..` that stays INSIDE the datadir is not an escape: it must be normalised away
        // (so the persisted model JSON carries no `..` segment) and the datasource must still
        // be written.
        String json = addCsvDatasource("c://temp/repo/./sub/../data/file.csv", "MOCK_CSV_INNER_DOTDOT");

        assertNotNull("the csv model json must be written", json);
        assertFalse("no `..` segment may survive into the model JSON: " + json, json.contains("/../"));
        assertTrue(json.contains("c:/temp/repo/data/file.csv"));
    }

    /**
     * Drive {@code addDatasource} for a CSV datasource whose Calcite model path is {@code
     * csvPath}, and return the persisted {@code <name>-csv.json} content (or null if it was not
     * written).
     */
    private String addCsvDatasource(String csvPath, String dsName) throws Exception {
        MockConnectionManager cManager = new MockConnectionManager();
        MockRepositoryManager rManager = new MockRepositoryManager();

        Map<String, Object> session = new HashMap<>();
        session.put(RepositoryDatasourceManager.ORBIS_WORKSPACE_DIR, "workspace");

        rdManager.setConnectionManager(cManager);
        rdManager.setRepositoryManager(rManager);
        rdManager.setType(CLASSPATH);
        rdManager.setWorkspaces("true");
        rdManager.setSessionRegistry(createScopedRepo(session));
        rdManager.setDatadir("c:\\temp\\repo");

        SaikuDatasource ds = new SaikuDatasource() {
            @Override
            public Type getType() {
                return Type.OLAP;
            }

            @Override
            public String getName() {
                return dsName;
            }

            @Override
            public Properties getProperties() {
                Properties props = new Properties();
                props.setProperty("driver", "mondrian.olap4j.MondrianOlap4jDriver");
                // split[2] of the location is the Calcite model path; everything up to the first
                // `;` in it is what gets resolved + interpolated as the CSV operand.
                props.setProperty(
                        "location",
                        "jdbc:mondrian:Jdbc=jdbc:calcite:model=" + csvPath + ";Catalog=mondrian://datasources/" + dsName
                                + ".xml;JdbcDrivers=org.apache.calcite.jdbc.Driver;");
                props.setProperty("username", "bruno");
                props.setProperty("password", "bruno");
                props.setProperty("id", "b5ef4927-63e3-4d9c-b7dc-905fff8841f8");
                props.setProperty("security.enabled", "false");
                props.setProperty("type", "OLAP");
                props.setProperty("csv", "true");

                return props;
            }
        };

        rdManager.addDatasource(ds);

        return rManager.getInternalFile("/datasources/" + dsName + "-csv.json");
    }

    private ScopedRepo createScopedRepo(Map<String, Object> sessionAttributes) {
        ScopedRepo repo = new ScopedRepo();

        repo.setSession(new MockHttpSession(sessionAttributes));

        return repo;
    }
}
