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

    @Test
    public void testAddDatasourceAllowsInternalSpacesInName() {
        // saiku#1906: the allowlist deliberately permits internal spaces (upgrade-safety —
        // existing datasource names may already contain them). This manager is intentionally
        // left unwired (no connection/repository manager), so addDatasource may still fail
        // downstream for unrelated reasons (e.g. a null repository manager) — we only assert
        // the name allowlist itself does not reject spaces.
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
                return new Properties();
            }
        };

        try {
            rdManager.addDatasource(ds);
        } catch (IllegalArgumentException unexpected) {
            fail("datasource name with internal spaces must not be rejected by the allowlist: "
                    + unexpected.getMessage());
        } catch (Exception otherFailure) {
            // Acceptable: this manager has no connection/repository manager wired, so the
            // write path may fail for reasons unrelated to name validation.
        }
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

    private ScopedRepo createScopedRepo(Map<String, Object> sessionAttributes) {
        ScopedRepo repo = new ScopedRepo();

        repo.setSession(new MockHttpSession(sessionAttributes));

        return repo;
    }
}
