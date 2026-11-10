/*
 *   Copyright 2012 OSBI Ltd
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
package org.saiku.datasources.connection;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.olap4j.OlapConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.datasource.IDatasourceManager;
import org.saiku.service.datasource.IDatasourceProcessor;
import org.saiku.service.datasource.JdbcUrlPolicy;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuAccessDeniedException;
import org.saiku.service.util.exception.SaikuServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractConnectionManager implements IConnectionManager, Serializable {

    private static final long serialVersionUID = 4735617922513789022L;
    private static final Logger log = LoggerFactory.getLogger(AbstractConnectionManager.class);
    private transient IDatasourceManager ds;
    private UserService userService;

    /** saiku#2326: optional AI schema cache hook — refreshConnection drops the
     *  named connection's role-scoped entries. Wired in saiku-beans.xml; null
     *  in unit tests and in deployments without the AI surface.
     *
     *  Injected as an {@link ObjectFactory} rather than a direct bean
     *  reference ON PURPOSE: connectionManager → aiCubeMetadataService →
     *  olapDiscoverService → datasourceService → connectionManager is a bean
     *  cycle, and an eager `aiSchemaCache` property makes Spring hand
     *  olapDiscoverService a half-built datasourceService (its
     *  connectionManager property not yet injected), so OlapDiscoverService
     *  captured `new OlapMetaExplorer(null)` — a final null field, NPE on
     *  every later getAllCubes(). The factory defers the lookup to first
     *  refresh, when the context is fully built. */
    private transient java.util.function.Supplier<AiCubeMetadataService> aiSchemaCacheSource;

    /** XML wiring seam: {@code ObjectFactoryCreatingFactoryBean} turns a bean
     *  name into an ObjectFactory, which is a java.util.function.Supplier, so
     *  no direct edge connectionManager → aiCubeMetadataServiceBean exists at
     *  wiring time. */
    public void setAiSchemaCacheSource(org.springframework.beans.factory.ObjectFactory<AiCubeMetadataService> source) {
        this.aiSchemaCacheSource = source == null ? null : source::getObject;
    }

    public void setDataSourceManager(IDatasourceManager ds) {
        this.ds = ds;
    }

    public IDatasourceManager getDataSourceManager() {
        return ds;
    }

    private transient List<IDatasourceProcessor> dsProcessors;

    public void setDataSourceProcessors(List<IDatasourceProcessor> processors) {
        this.dsProcessors = processors;
    }

    public List<IDatasourceProcessor> getDataSourceProcessors() {
        return this.dsProcessors;
    }

    public abstract void init() throws SaikuOlapException;

    public void destroy() throws SaikuOlapException {
        Map<String, OlapConnection> connections = getAllOlapConnections();
        if (connections != null && !connections.isEmpty()) {
            for (OlapConnection con : connections.values()) {
                try {
                    if (!con.isClosed()) {
                        con.close();
                    }
                } catch (Exception e) {
                    log.error("Could not close connection", e);
                }
            }
        }
        if (connections != null) {
            connections.clear();
        }
        log.info("Do we still have connections? : " + getAllOlapConnections().size());
    }

    private SaikuDatasource preProcess(SaikuDatasource datasource) {
        if (datasource != null && datasource.getProperties().containsKey(ISaikuConnection.DATASOURCE_PROCESSORS)) {
            datasource = datasource.clone();
            String[] processors = datasource
                    .getProperties()
                    .getProperty(ISaikuConnection.DATASOURCE_PROCESSORS)
                    .split(",");
            for (String processor : processors) {
                try {
                    // saiku#1902: load without initialising and check the type BEFORE the class
                    // runs any code, so a mis-configured name can't act as a static-init gadget.
                    final Class<? extends IDatasourceProcessor> clazz =
                            JdbcUrlPolicy.loadImplementation(processor.trim(), IDatasourceProcessor.class);
                    final Constructor<? extends IDatasourceProcessor> ctor = clazz.getConstructor();
                    final IDatasourceProcessor dsProcessor = ctor.newInstance();
                    datasource = dsProcessor.process(datasource);
                } catch (Exception e) {
                    throw new SaikuServiceException("Error applying DatasourceProcessor \"" + processor + "\"", e);
                }
            }
        }
        return datasource;
    }

    private ISaikuConnection postProcess(SaikuDatasource datasource, ISaikuConnection con) {
        if (datasource != null && datasource.getProperties().containsKey(ISaikuConnection.CONNECTION_PROCESSORS)) {
            datasource = datasource.clone();
            String[] processors = datasource
                    .getProperties()
                    .getProperty(ISaikuConnection.CONNECTION_PROCESSORS)
                    .split(",");
            for (String processor : processors) {
                try {
                    // saiku#1902: see preProcess — type-check before initialising.
                    final Class<? extends IConnectionProcessor> clazz =
                            JdbcUrlPolicy.loadImplementation(processor.trim(), IConnectionProcessor.class);
                    final Constructor<? extends IConnectionProcessor> ctor = clazz.getConstructor();
                    final IConnectionProcessor conProcessor = ctor.newInstance();
                    return conProcessor.process(con);
                } catch (Exception e) {
                    throw new SaikuServiceException("Error applying ConnectionProcessor \"" + processor + "\"", e);
                }
            }
        }
        return con;
    }

    public ISaikuConnection getConnection(String name) throws SaikuOlapException {
        SaikuDatasource datasource = ds.getDatasource(name, false);
        datasource = preProcess(datasource);
        ISaikuConnection con = getInternalConnection(name, datasource);
        con = postProcess(datasource, con);
        return con;
    }

    protected abstract ISaikuConnection getInternalConnection(String name, SaikuDatasource datasource)
            throws SaikuOlapException;

    protected abstract ISaikuConnection refreshInternalConnection(String name, SaikuDatasource datasource);

    public void refreshAllConnections() {
        ds.load();

        String[] roles = new String[] {};

        if (userService != null) {
            try {
                roles = userService.getCurrentUserRoles();
            } catch (Exception ex) {
                // It is expected in scenarios where the user roles are not available
            }
        }

        for (String name : ds.getDatasources(roles).keySet()) {
            try {
                refreshConnection(name);
            } catch (Exception ex) {
                // Log but continue so one broken datasource doesn't sink the rest
                log.error("Failed to refresh connection {}", name, ex);
            }
        }
    }

    public void refreshConnection(String name) {
        SaikuDatasource datasource = ds.getDatasource(name);
        datasource = preProcess(datasource);
        ISaikuConnection con = refreshInternalConnection(name, datasource);
        con = postProcess(datasource, con);
        // saiku#1483: every schema-reload path funnels through here (admin refresh,
        // datasource save/update, XMLA refresh, refreshAllConnections). Bump the
        // connection's metadata epoch so QueryCacheKey.cubeVersion changes and the
        // cellset cache stops serving pre-reload results.
        org.saiku.service.cache.CubeMetadataVersions.bump(name);
        // saiku#2326 (CWE-284/524/863): a refresh can change what a role sees on
        // this connection, so its role-scoped AI schema snapshots are stale. Drop
        // them for this connection only (fail-soft — a stale schema is the old
        // bug's lesser cousin, but a broken refresh must not break queries).
        if (aiSchemaCacheSource != null) {
            try {
                AiCubeMetadataService cache = aiSchemaCacheSource.get();
                if (cache != null) {
                    cache.invalidateSchemaCache(name);
                }
            } catch (RuntimeException e) {
                log.warn("saiku#2326: failed to invalidate AI schema cache for connection {}", name, e);
            }
        }
    }

    public Map<String, ISaikuConnection> getAllConnections() throws SaikuOlapException {
        Map<String, ISaikuConnection> resultDs = new HashMap<>();

        String[] userRoles = new String[] {};

        if (userService != null) {
            userRoles = userService.getCurrentUserRoles();
        }

        if (ds != null) {
            for (String name : ds.getDatasources(userRoles).keySet()) {
                try {
                    ISaikuConnection con = getConnection(name);

                    if (con != null) {
                        resultDs.put(name, con);
                    }
                } catch (SaikuAccessDeniedException denied) {
                    // saiku#1968 (CWE-863): the caller holds no Mondrian role on THIS
                    // security-enabled datasource. Omit it from the enumeration rather than aborting
                    // the whole loop, so a user with disjoint roles across datasources still sees the
                    // ones they CAN access. The single-datasource query path still denies (this
                    // exception propagates there). Mirrors the per-datasource resilience in
                    // refreshAllConnections().
                    log.debug("Omitting datasource {} from enumeration — access denied for caller", name);
                }
            }
        }

        return resultDs;
    }

    public OlapConnection getOlapConnection(String name) throws SaikuOlapException {
        ISaikuConnection con = getConnection(name);
        if (con != null) {
            Object o = con.getConnection();
            if (o != null && o instanceof OlapConnection) {
                return (OlapConnection) o;
            }
        } else {

        }
        return null;
    }

    public Map<String, OlapConnection> getAllOlapConnections() throws SaikuOlapException {
        Map<String, ISaikuConnection> connections = getAllConnections();
        Map<String, OlapConnection> ocons = new HashMap<>();
        for (ISaikuConnection con : connections.values()) {
            Object o = con.getConnection();
            if (o != null && o instanceof OlapConnection) {
                ocons.put(con.getName(), (OlapConnection) o);
            }
        }

        return ocons;
    }

    protected boolean isDatasourceSecurity(SaikuDatasource datasource, String value) {
        if (datasource != null && value != null) {
            Properties props = datasource.getProperties();
            if (props != null && isDatasourceSecurityEnabled(datasource)) {
                if (props.containsKey(ISaikuConnection.SECURITY_TYPE_KEY)) {
                    return props.getProperty(ISaikuConnection.SECURITY_TYPE_KEY).equals(value);
                }
            }
        }
        return false;
    }

    protected boolean isDatasourceSecurityEnabled(SaikuDatasource datasource) {
        if (datasource != null) {
            Properties props = datasource.getProperties();
            if (props != null && props.containsKey(ISaikuConnection.SECURITY_ENABLED_KEY)) {
                String enabled = props.getProperty(ISaikuConnection.SECURITY_ENABLED_KEY, "false");
                return Boolean.parseBoolean(enabled);
            }
        }
        return false;
    }

    private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {

        stream.defaultReadObject();
        // ds = (IDatasourceManager)ApplicationContextProvider.getApplicationContext().getBean("classpathDsManager");
    }

    public UserService getUserService() {
        return userService;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }
}
