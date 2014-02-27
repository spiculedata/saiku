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

	public enum QueryType { MDX, QM };
	public String getName();
	public SaikuCube getSaikuCube();
	public CellSet execute() throws Exception;
	public String getMdx();
	public void resetQuery();
	public void setProperties(Properties props);
	public Properties getProperties();
	public String toXml();
	public Boolean isDrillThroughEnabled();
	public QueryType getType();

	public void swapAxes();
	public Map<Axis, QueryAxis> getAxes();
	public QueryAxis getAxis(Axis axis);
	public QueryAxis getAxis(String name) throws SaikuOlapException;
	public Cube getCube();			
	public QueryAxis getUnusedAxis();
	public void moveDimension(QueryDimension dimension, Axis axis);
	public void moveDimension(QueryDimension dimension, Axis axis, int position);
	public QueryDimension getDimension(String name);
	public void resetAxisSelections(QueryAxis axis);
	public void clearAllQuerySelections();
	public void setMdx(String mdx);
	public void setScenario(Scenario scenario);
	public Scenario getScenario();
	public void setTag(SaikuTag tag);
	public SaikuTag getTag();
	public void removeTag();
	public void setFilter(SaikuFilter filter);
	public SaikuFilter getFilter();
	public void removeFilter();
	public void storeCellset(CellSet cs);
	public CellSet getCellset();
	public void setStatement(OlapStatement os);
	public OlapStatement getStatement();
	public void cancel() throws Exception;
	public void clearAxis(String axisName) throws SaikuOlapException;
	public OlapConnection getConnection();
	public void storeFormatter(ICellSetFormatter formatter);
	public ICellSetFormatter getFormatter();
	public void setTotalFunction(String uniqueLevelName, String value);
	public String getTotalFunction(String uniqueLevelName);
	public Map<String, String> getTotalFunctions();

}
