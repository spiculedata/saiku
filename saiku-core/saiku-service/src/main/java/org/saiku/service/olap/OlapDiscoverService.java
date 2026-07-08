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
package org.saiku.service.olap;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mondrian.rolap.RolapConnection;
import org.olap4j.OlapConnection;
import org.olap4j.metadata.Cube;
import org.saiku.olap.discover.OlapMetaExplorer;
import org.saiku.olap.dto.*;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.util.exception.SaikuServiceException;

public class OlapDiscoverService implements Serializable {

    /**
     *  SerialVersionUID
     */
    private static final long serialVersionUID = 884682532600907574L;

    private DatasourceService datasourceService;
    private transient OlapMetaExplorer metaExplorer;

    public void setDatasourceService(DatasourceService ds) {
        datasourceService = ds;
        metaExplorer = new OlapMetaExplorer(ds.getConnectionManager());
    }

    /**
     * Expose the underlying {@link org.saiku.datasources.connection.IConnectionManager} so the
     * Ossie query path (which speaks JDBC, not olap4j) can acquire its {@link
     * org.saiku.datasources.connection.ISaikuConnection} directly. The existing MDX path uses
     * {@link #getNativeConnection} which unwraps the olap4j {@code OlapConnection}; that unwrap
     * fails for OSSIE-typed connections.
     */
    public org.saiku.datasources.connection.IConnectionManager getConnectionManager() {
        return datasourceService.getConnectionManager();
    }

    public List<SaikuCube> getAllCubes() throws SaikuOlapException {
        return metaExplorer.getAllCubes();
    }

    public List<SaikuConnection> getAllConnections() throws SaikuServiceException {
        try {
            return metaExplorer.getAllConnections();
        } catch (SaikuOlapException e) {
            throw new SaikuServiceException("Cannot retrieve all connections", e);
        }
    }

    public List<SaikuConnection> getConnection(String connectionName) {
        List<SaikuConnection> connections = new ArrayList<>();
        try {
            SaikuConnection c = metaExplorer.getConnection(connectionName);
            connections.add(c);
            return connections;
        } catch (SaikuOlapException e) {
            throw new SaikuServiceException("Cannot retrieve all connections", e);
        }
    }

    public void refreshAllConnections() throws SaikuServiceException {
        try {
            datasourceService.getConnectionManager().refreshAllConnections();
        } catch (Exception e) {
            throw new SaikuServiceException("Cannot refresh all connections", e);
        }
    }

    public void refreshConnection(String name) throws SaikuServiceException {
        try {
            datasourceService.getConnectionManager().refreshConnection(name);
        } catch (Exception e) {
            throw new SaikuServiceException("Cannot refresh all connections", e);
        }
    }

    public Cube getNativeCube(SaikuCube cube) throws SaikuServiceException {
        try {
            return metaExplorer.getNativeCube(cube);
        } catch (SaikuOlapException e) {
            throw new SaikuServiceException("Cannot get native cube for cube ( " + cube + " )", e);
        }
    }

    public OlapConnection getNativeConnection(String name) throws SaikuServiceException {
        try {
            return metaExplorer.getNativeConnection(name);
        } catch (SaikuOlapException e) {
            throw new SaikuServiceException("Cannot get native connection for cube ( " + name + " )", e);
        }
    }

    public List<SaikuDimension> getAllDimensions(SaikuCube cube) throws SaikuServiceException {
        try {
            return metaExplorer.getAllDimensions(cube);
        } catch (SaikuOlapException e) {
            throw new SaikuServiceException("Cannot get all dimensions for cube ( " + cube + " )", e);
        }
    }

    public SaikuDimension getDimension(SaikuCube cube, String dimensionName) throws SaikuServiceException {
        try {
            return metaExplorer.getDimension(cube, dimensionName);
        } catch (SaikuOlapException e) {
            throw new SaikuServiceException(
                    "Cannot get dimension (" + dimensionName + " ) for cube ( " + cube + " )", e);
        }
    }

    public List<SaikuHierarchy> getAllHierarchies(SaikuCube cube) throws SaikuServiceException {
        try {
            return metaExplorer.getAllHierarchies(cube);
        } catch (SaikuOlapException e) {
            throw new SaikuServiceException("Cannot get all hierarchies for cube ( " + cube + " )", e);
        }
    }

    public List<SaikuHierarchy> getAllDimensionHierarchies(SaikuCube cube, String dimensionName) {
        try {
            SaikuDimension dim = metaExplorer.getDimension(cube, dimensionName);
            if (dim == null) {
                throw new SaikuServiceException(
                        "Cannot find dimension ( " + dimensionName + ") for cube ( " + cube + " )");
            }
            return dim.getHierarchies();

        } catch (SaikuOlapException e) {
            throw new SaikuServiceException(
                    "Cannot get all hierarchies for cube ( " + cube + " ) dimension ( " + dimensionName + " )", e);
        }
    }

    public List<SaikuLevel> getAllHierarchyLevels(SaikuCube cube, String dimensionName, String hierarchyName) {
        try {

            return metaExplorer.getAllLevels(cube, dimensionName, hierarchyName);

        } catch (SaikuOlapException e) {
            throw new SaikuServiceException(
                    "Cannot get all levels for cube ( " + cube + " ) dimension ( " + dimensionName + " ) hierarchy ( "
                            + hierarchyName + " )",
                    e);
        }
    }

    public List<SimpleCubeElement> getLevelMembers(SaikuCube cube, String hierarchyName, String levelName) {
        return getLevelMembers(cube, hierarchyName, levelName, null, -1);
    }

    public List<SimpleCubeElement> getLevelMembers(
            SaikuCube cube, String hierarchyName, String levelName, int searchLimit) {
        return getLevelMembers(cube, hierarchyName, levelName, null, searchLimit);
    }

    public List<SimpleCubeElement> getLevelMembers(
            SaikuCube cube, String hierarchyName, String levelName, String searchString, int searchLimit) {
        try {

            return metaExplorer.getAllMembers(cube, hierarchyName, levelName, searchString, searchLimit);

        } catch (SaikuOlapException e) {
            throw new SaikuServiceException(
                    "Cannot get all members for cube ( " + cube + " ) hierarchy ( " + hierarchyName + " )", e);
        }
    }

    public List<SaikuMember> getMeasures(SaikuCube cube) {
        try {
            return metaExplorer.getAllMeasures(cube);
        } catch (SaikuOlapException e) {
            throw new SaikuServiceException("Cannot get all measures for cube ( " + cube + " )", e);
        }
    }

    public List<SaikuMember> getHierarchyRootMembers(SaikuCube cube, String hierarchyName) {
        try {
            return metaExplorer.getHierarchyRootMembers(cube, hierarchyName);
        } catch (SaikuOlapException e) {
            throw new SaikuServiceException(e);
        }
    }

    public List<SaikuMember> getMemberChildren(SaikuCube cube, String uniqueMemberName) {
        try {
            return metaExplorer.getMemberChildren(cube, uniqueMemberName);
        } catch (SaikuOlapException e) {
            throw new SaikuServiceException(e);
        }
    }

    public SaikuMember getMember(SaikuCube cube, String uniqueMemberName) {
        try {
            return metaExplorer.getMember(cube, uniqueMemberName);
        } catch (SaikuOlapException e) {
            throw new SaikuServiceException(e);
        }
    }

    public Map<String, Object> getProperties(SaikuCube cube) {
        Map<String, Object> properties = new HashMap<>();
        try {
            Cube c = getNativeCube(cube);
            OlapConnection con = c.getSchema().getCatalog().getDatabase().getOlapConnection();
            properties.put("saiku.olap.query.drillthrough", c.isDrillThroughEnabled());
            properties.put("org.saiku.query.explain", con.isWrapperFor(RolapConnection.class));

            try {
                Boolean isScenario = (c.getDimensions().get("Scenario") != null);
                properties.put("org.saiku.connection.scenario", isScenario);
            } catch (Exception e) {
                properties.put("org.saiku.connection.scenario", false);
            }
        } catch (Exception e) {
            throw new SaikuServiceException(e);
        }
        return properties;
    }

    private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {
        stream.defaultReadObject();
        metaExplorer = new OlapMetaExplorer(datasourceService.getConnectionManager());
    }
}
