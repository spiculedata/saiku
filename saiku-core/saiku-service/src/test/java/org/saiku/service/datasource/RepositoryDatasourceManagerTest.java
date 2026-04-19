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
