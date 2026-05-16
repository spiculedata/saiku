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
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractConnectionManager implements IConnectionManager, Serializable {

    private static final long serialVersionUID = 4735617922513789022L;
    private static final Logger log = LoggerFactory.getLogger(AbstractConnectionManager.class);
    private transient IDatasourceManager ds;
    private UserService userService;

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
                    @SuppressWarnings("unchecked")
                    final Class<IDatasourceProcessor> clazz = (Class<IDatasourceProcessor>) Class.forName(processor);
                    final Constructor<IDatasourceProcessor> ctor = clazz.getConstructor();
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
                    @SuppressWarnings("unchecked")
                    final Class<IConnectionProcessor> clazz = (Class<IConnectionProcessor>) Class.forName(processor);
                    final Constructor<IConnectionProcessor> ctor = clazz.getConstructor();
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
    }

    public Map<String, ISaikuConnection> getAllConnections() throws SaikuOlapException {
        Map<String, ISaikuConnection> resultDs = new HashMap<>();

        String[] userRoles = new String[] {};

        if (userService != null) {
            userRoles = userService.getCurrentUserRoles();
        }

        if (ds != null) {
            for (String name : ds.getDatasources(userRoles).keySet()) {
                ISaikuConnection con = getConnection(name);

                if (con != null) {
                    resultDs.put(name, con);
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
