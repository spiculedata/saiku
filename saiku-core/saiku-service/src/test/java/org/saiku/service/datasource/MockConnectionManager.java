package org.saiku.service.datasource;

import java.util.Map;
import org.olap4j.OlapConnection;
import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.olap.util.exception.SaikuOlapException;

public class MockConnectionManager implements IConnectionManager {
    @Override
    public void init() throws SaikuOlapException {}

    @Override
    public void setDataSourceManager(IDatasourceManager ds) {}

    @Override
    public IDatasourceManager getDataSourceManager() {
        return null;
    }

    @Override
    public void refreshConnection(String name) {}

    @Override
    public void refreshAllConnections() {}

    @Override
    public OlapConnection getOlapConnection(String name) throws SaikuOlapException {
        return null;
    }

    @Override
    public Map<String, OlapConnection> getAllOlapConnections() throws SaikuOlapException {
        return null;
    }

    @Override
    public ISaikuConnection getConnection(String name) throws SaikuOlapException {
        return null;
    }

    @Override
    public Map<String, ISaikuConnection> getAllConnections() throws SaikuOlapException {
        return null;
    }
}
