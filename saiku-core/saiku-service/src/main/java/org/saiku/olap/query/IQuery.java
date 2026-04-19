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
package org.saiku.olap.query;

import java.util.Map;
import java.util.Properties;
import org.olap4j.Axis;
import org.olap4j.CellSet;
import org.olap4j.OlapConnection;
import org.olap4j.OlapStatement;
import org.olap4j.Scenario;
import org.olap4j.metadata.Cube;
import org.olap4j.query.QueryAxis;
import org.olap4j.query.QueryDimension;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuTag;
import org.saiku.olap.dto.filter.SaikuFilter;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.olap.util.formatter.ICellSetFormatter;

public interface IQuery {

    enum QueryType {
        MDX,
        QM
    }

    String getName();

    SaikuCube getSaikuCube();

    CellSet execute() throws Exception;

    String getMdx();

    void resetQuery();

    void setProperties(Properties props);

    Properties getProperties();

    String toXml();

    Boolean isDrillThroughEnabled();

    QueryType getType();

    void swapAxes();

    Map<Axis, QueryAxis> getAxes();

    QueryAxis getAxis(Axis axis);

    QueryAxis getAxis(String name) throws SaikuOlapException;

    Cube getCube();

    QueryAxis getUnusedAxis();

    void moveDimension(QueryDimension dimension, Axis axis);

    void moveDimension(QueryDimension dimension, Axis axis, int position);

    QueryDimension getDimension(String name);

    void resetAxisSelections(QueryAxis axis);

    void clearAllQuerySelections();

    void setMdx(String mdx);

    void setScenario(Scenario scenario);

    Scenario getScenario();

    void setTag(SaikuTag tag);

    SaikuTag getTag();

    void removeTag();

    void setFilter(SaikuFilter filter);

    SaikuFilter getFilter();

    void removeFilter();

    void storeCellset(CellSet cs);

    CellSet getCellset();

    void setStatement(OlapStatement os);

    OlapStatement getStatement();

    void cancel() throws Exception;

    void clearAxis(String axisName) throws SaikuOlapException;

    OlapConnection getConnection();

    void storeFormatter(ICellSetFormatter formatter);

    ICellSetFormatter getFormatter();

    void setTotalFunction(String uniqueLevelName, String value);

    String getTotalFunction(String uniqueLevelName);

    Map<String, String> getTotalFunctions();
}
