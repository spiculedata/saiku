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

import java.util.Map;
import org.olap4j.OlapConnection;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.datasource.IDatasourceManager;

public interface IConnectionManager {

    void init() throws SaikuOlapException;

    void setDataSourceManager(IDatasourceManager ds);

    IDatasourceManager getDataSourceManager();

    void refreshConnection(String name);

    void refreshAllConnections();

    OlapConnection getOlapConnection(String name) throws SaikuOlapException;

    Map<String, OlapConnection> getAllOlapConnections() throws SaikuOlapException;

    ISaikuConnection getConnection(String name) throws SaikuOlapException;

    Map<String, ISaikuConnection> getAllConnections() throws SaikuOlapException;
}
