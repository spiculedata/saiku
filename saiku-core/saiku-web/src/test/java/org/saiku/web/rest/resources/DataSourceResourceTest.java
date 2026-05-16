/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.*;

import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.Before;
import org.junit.Test;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuServiceException;

/**
 * Drives {@link DataSourceResource} against stubbed {@link DatasourceService}
 * + {@link UserService} so the REST contract is exercised without a real
 * datasource manager.
 */
public class DataSourceResourceTest {

    private DataSourceResource resource;
    private StubDatasourceService dsSvc;
    private StubUserService users;

    @Before
    public void setUp() {
        dsSvc = new StubDatasourceService();
        users = new StubUserService();
        resource = new DataSourceResource();
        resource.setDatasourceService(dsSvc);
        resource.setUserService(users);
    }

    @Test
    public void getDatasources_returnsAllDatasources() {
        dsSvc.store.put("alpha", ds("alpha", "id-a"));
        dsSvc.store.put("beta", ds("beta", "id-b"));
        assertEquals(2, resource.getDatasources().size());
    }

    @Test
    public void getDatasources_swallowsSaikuServiceException_returnsEmpty() {
        dsSvc.throwOnList = new SaikuServiceException("backend down");
        assertTrue(resource.getDatasources().isEmpty());
    }

    @Test
    public void deleteDatasource_returnsGoneStatus_andDelegates() {
        Response.Status s = resource.deleteDatasource("ds-name");
        assertEquals(Response.Status.GONE, s);
        assertEquals(1, dsSvc.removeCount);
        assertEquals("ds-name", dsSvc.lastRemovedName);
    }

    @Test
    public void getDatasourceById_returnsMatchingDatasourceWrapped() {
        dsSvc.store.put("beta", dsForMapper("beta", "id-b"));
        Response resp = resource.getDatasourceById("id-b");
        assertEquals(200, resp.getStatus());
        // body is a DataSourceMapper around the matching SaikuDatasource.
        assertNotNull(resp.getEntity());
    }

    @Test
    public void getDatasourceById_unknownId_returns500FromMapperNpe() {
        // When no datasource matches, the resource still tries to construct a
        // DataSourceMapper(null) which NPEs on ds.getProperties() — caught by
        // the catch block and surfaced as 500. Pinning current behavior so a
        // future fix that returns 404/200 is a deliberate change, not a silent
        // regression.
        dsSvc.store.put("alpha", dsForMapper("alpha", "id-a"));
        Response resp = resource.getDatasourceById("missing-id");
        assertEquals(500, resp.getStatus());
    }

    @Test
    public void getDatasourceById_underlyingException_returns500() {
        dsSvc.throwOnList = new RuntimeException("kaboom");
        Response resp = resource.getDatasourceById("id-b");
        assertEquals(500, resp.getStatus());
        assertEquals("kaboom", resp.getEntity());
    }

    @Test
    public void updateDatasourceLocale_swapsLocale_andSavesOverwrite() {
        SaikuDatasource d = ds("with-locale", "id-locale");
        d.getProperties().setProperty("location", "jdbc:mondrian:Jdbc=foo;Catalog=x;locale=en_GB;");
        dsSvc.store.put("with-locale", d);

        Response resp = resource.updateDatasourceLocale("fr_FR", "id-locale");
        assertEquals(200, resp.getStatus());
        assertEquals(1, dsSvc.addCount);
        assertTrue(dsSvc.lastAddOverwrite);
        assertTrue(d.getProperties().getProperty("location").contains("locale=fr_FR;"));
    }

    @Test
    public void updateDatasourceLocale_unknownId_doesNothing_butStillReturns200() {
        Response resp = resource.updateDatasourceLocale("fr_FR", "no-such-id");
        assertEquals(200, resp.getStatus());
        assertEquals(0, dsSvc.addCount);
    }

    @Test
    public void updateDatasourceLocale_addThrows_returns500() {
        SaikuDatasource d = ds("with-locale", "id-locale");
        d.getProperties().setProperty("location", "jdbc:mondrian:Jdbc=foo;locale=en_GB;");
        dsSvc.store.put("with-locale", d);
        dsSvc.throwOnAdd = new RuntimeException("disk full");

        Response resp = resource.updateDatasourceLocale("fr_FR", "id-locale");
        assertEquals(500, resp.getStatus());
        assertEquals("disk full", resp.getEntity());
    }

    @Test
    public void getUserService_returnsWiredInstance() {
        assertSame(users, resource.getUserService());
    }

    // ---------------------------------------------------------------- helpers

    private static SaikuDatasource ds(String name, String id) {
        Properties p = new Properties();
        p.setProperty("id", id);
        return new SaikuDatasource(name, SaikuDatasource.Type.OLAP, p);
    }

    /**
     * Build a SaikuDatasource with the minimum set of properties that
     * DataSourceMapper needs to construct without throwing.
     */
    private static SaikuDatasource dsForMapper(String name, String id) {
        Properties p = new Properties();
        p.setProperty("id", id);
        p.setProperty("driver", "mondrian.olap4j.MondrianOlap4jDriver");
        p.setProperty("location", "jdbc:mondrian:Jdbc=jdbc:h2:mem:foo;Catalog=x.xml;JdbcDrivers=org.h2.Driver");
        p.setProperty("connectiontype", "OLAP");
        p.setProperty("advanced", "false");
        return new SaikuDatasource(name, SaikuDatasource.Type.OLAP, p);
    }

    private static final class StubDatasourceService extends DatasourceService {
        final Map<String, SaikuDatasource> store = new LinkedHashMap<>();
        RuntimeException throwOnList;
        Exception throwOnAdd;
        int removeCount;
        int addCount;
        String lastRemovedName;
        boolean lastAddOverwrite;

        @Override
        public Map<String, SaikuDatasource> getDatasources(String[] roles) {
            if (throwOnList != null) throw throwOnList;
            return store;
        }

        @Override
        public void removeDatasource(String datasourceId) {
            removeCount++;
            lastRemovedName = datasourceId;
        }

        @Override
        public void addDatasource(SaikuDatasource ds, boolean overwrite, String[] roles) throws Exception {
            if (throwOnAdd != null) throw throwOnAdd;
            addCount++;
            lastAddOverwrite = overwrite;
        }
    }

    private static final class StubUserService extends UserService {
        @Override
        public String[] getCurrentUserRoles() {
            return new String[] {"ROLE_USER"};
        }
    }
}
