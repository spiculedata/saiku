/*
 * Copyright 2014 OSBI Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.saiku.olap.util;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import mondrian.xmla.XmlaHandler;
import mondrian.xmla.XmlaRequest;
import mondrian.xmla.impl.DefaultXmlaServlet;
import org.olap4j.OlapConnection;
import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * Created by bugg on 30/03/15.
 */
public class SaikuDefaultXmlaServlet extends DefaultXmlaServlet {

    private static final Logger log = LoggerFactory.getLogger(SaikuDefaultXmlaServlet.class);
    private static IConnectionManager connections;

    public SaikuDefaultXmlaServlet() {
        super();
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        ServletContext context = getServletContext();

        WebApplicationContext applicationContext = WebApplicationContextUtils.getWebApplicationContext(context);
        connections = (IConnectionManager) applicationContext.getBean("connectionManager");
    }

    @Override
    protected XmlaHandler.ConnectionFactory createConnectionFactory(ServletConfig servletConfig)
            throws ServletException {
        return new XmlaHandler.ConnectionFactory() {
            private final XmlaHandler.XmlaExtra extra = new XmlaHandler.XmlaExtraImpl();

            public OlapConnection getConnection(String s, String s1, String s2, Properties properties)
                    throws SQLException {
                try {
                    connections.refreshAllConnections();
                    return connections.getOlapConnection(System.getProperty("xmla_datasource"));
                } catch (SaikuOlapException e) {
                    log.error("XMLA connection refresh failed", e);
                }
                return null;
            }

            public Map<String, Object> getPreConfiguredDiscoverDatasourcesResponse() {

                return null; // getDiscoverDatasourcesPreConfiguredResponse(servletConfig);
            }

            public XmlaHandler.Request startRequest(XmlaRequest xmlaRequest, OlapConnection olapConnection) {
                return null;
            }

            public void endRequest(XmlaHandler.Request request) {}

            public XmlaHandler.XmlaExtra getExtra() {
                return extra;
            }
        };
    }
}
