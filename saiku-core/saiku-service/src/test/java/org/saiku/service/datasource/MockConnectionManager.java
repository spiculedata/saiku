/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.datasource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.olap4j.OlapConnection;
import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.olap.util.exception.SaikuOlapException;

public class MockConnectionManager implements IConnectionManager {
    /** saiku#2533: names passed to {@link #removeConnection(String)}, for eviction assertions. */
    public final List<String> removedConnections = new ArrayList<>();

    @Override
    public void removeConnection(String name) {
        removedConnections.add(name);
    }

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
