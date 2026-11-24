/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.cache.QueryCacheKey;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.user.UserService;
import org.saiku.web.core.SecurityAwareConnectionManagerVisibilityKeyTest.FakeDatasourceManager;
import org.saiku.web.core.SecurityAwareConnectionManagerVisibilityKeyTest.FakeSessionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** saiku#2538: exercises the real datasource and caller decision behind result-cache keys. */
public class SecurityAwareConnectionManagerIdentityKeyTest {

    @Before
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String user) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "n/a", authorities));
    }

    private static SaikuDatasource datasource(String name, boolean enabled, String type) {
        Properties properties = new Properties();
        properties.setProperty(ISaikuConnection.SECURITY_ENABLED_KEY, Boolean.toString(enabled));
        if (type != null) {
            properties.setProperty(ISaikuConnection.SECURITY_TYPE_KEY, type);
        }
        return new SaikuDatasource(name, SaikuDatasource.Type.OLAP, properties);
    }

    private static SecurityAwareConnectionManager manager(
            FakeDatasourceManager datasources, Map<String, Object> session) {
        SecurityAwareConnectionManager manager = new SecurityAwareConnectionManager();
        manager.setDataSourceManager(datasources);
        manager.setSessionService(new FakeSessionService(session));
        return manager;
    }

    @Test
    public void passthroughSameAuthoritiesKeysByPrincipal() {
        FakeDatasourceManager datasources = new FakeDatasourceManager();
        datasources.seed(datasource("warehouse", true, ISaikuConnection.SECURITY_TYPE_PASSTHROUGH_VALUE));
        SecurityAwareConnectionManager manager = manager(datasources, new HashMap<>());

        authenticateAs("alice");
        String alice = manager.identityKey("warehouse");
        authenticateAs("bob");
        String bob = manager.identityKey("warehouse");

        assertEquals("user:alice", alice);
        assertEquals("user:bob", bob);
        assertNotEquals(alice, bob);
    }

    @Test
    public void nonPassthroughDatasourcesKeepEmptyIdentityForSameAuthorities() {
        FakeDatasourceManager datasources = new FakeDatasourceManager();
        datasources.seed(datasource("unsecured", false, null));
        datasources.seed(datasource("one2one", true, ISaikuConnection.SECURITY_TYPE_SPRING2MONDRIAN_VALUE));
        datasources.seed(datasource("lookup", true, ISaikuConnection.SECURITY_TYPE_SPRINGLOOKUPMONDRIAN_VALUE));
        SecurityAwareConnectionManager manager = manager(datasources, new HashMap<>());

        for (String name : new String[] {
            "unsecured", "one2one", "lookup", "workspace_unsecured", "workspace_one2one", "workspace_lookup"
        }) {
            authenticateAs("alice");
            assertEquals(name, "", manager.identityKey(name));
            authenticateAs("bob");
            assertEquals(name, "", manager.identityKey(name));
        }
    }

    @Test
    public void decoratedAndUnresolvableNamesNeverShareAnIdentity() {
        FakeDatasourceManager datasources = new FakeDatasourceManager();
        datasources.seed(datasource("warehouse", true, ISaikuConnection.SECURITY_TYPE_PASSTHROUGH_VALUE));
        SecurityAwareConnectionManager manager = manager(datasources, new HashMap<>());

        for (String name : new String[] {"workspace_warehouse", "missing", null}) {
            authenticateAs("alice");
            assertEquals("user:alice", manager.identityKey(name));
            authenticateAs("bob");
            assertEquals("user:bob", manager.identityKey(name));
        }
    }

    @Test
    public void passthroughUsesSessionUsernameBeforeSecurityPrincipal() {
        FakeDatasourceManager datasources = new FakeDatasourceManager();
        datasources.seed(datasource("warehouse", true, ISaikuConnection.SECURITY_TYPE_PASSTHROUGH_VALUE));
        Map<String, Object> session = new HashMap<>();
        SecurityAwareConnectionManager manager = manager(datasources, session);
        authenticateAs("same-principal");

        session.put("username", "Alice");
        assertEquals("user:alice", manager.identityKey("warehouse"));
        session.put("username", "Bob");
        assertEquals("user:bob", manager.identityKey("warehouse"));
    }

    @Test
    public void thinQueryServiceUsesRealManagerForPassthroughCacheKey() throws ReflectiveOperationException {
        FakeDatasourceManager datasources = new FakeDatasourceManager();
        datasources.seed(datasource("warehouse", true, ISaikuConnection.SECURITY_TYPE_PASSTHROUGH_VALUE));
        ThinQueryService service = new ThinQueryService();
        service.setIdentityContext(manager(datasources, new HashMap<>()));
        service.setUserService(new UserService() {
            @Override
            public String[] getCurrentUserRoles() {
                return new String[] {"ROLE_USER"};
            }
        });
        ThinQuery query = new ThinQuery(
                "sales",
                new SaikuCube("warehouse", "[Sales]", "Sales", "Sales", "FoodMart", "FoodMart"),
                "SELECT {[Measures].[Sales]} ON 0 FROM [Sales]");
        Method cacheKeyFor = ThinQueryService.class.getDeclaredMethod("cacheKeyFor", ThinQuery.class, String.class);
        cacheKeyFor.setAccessible(true);

        authenticateAs("alice");
        String alice = (String) cacheKeyFor.invoke(service, query, "warehouse|FoodMart|FoodMart|Sales");
        authenticateAs("bob");
        String bob = (String) cacheKeyFor.invoke(service, query, "warehouse|FoodMart|FoodMart|Sales");

        assertEquals(
                QueryCacheKey.of(
                        query,
                        "warehouse|FoodMart|FoodMart|Sales",
                        java.util.Collections.singletonList("ROLE_USER"),
                        "user:alice"),
                alice);
        assertEquals(
                QueryCacheKey.of(
                        query,
                        "warehouse|FoodMart|FoodMart|Sales",
                        java.util.Collections.singletonList("ROLE_USER"),
                        "user:bob"),
                bob);
        assertNotEquals("the real manager must separate both result-cache and coalescer keys", alice, bob);
    }
}
