/*
 * Copyright (C) 2011 OSBI Ltd
 *
 * This program is free software; you can redistribute it and/or modify it 
 * under the terms of the GNU General Public License as published by the Free 
 * Software Foundation; either version 2 of the License, or (at your option) 
 * any later version.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 
 * See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along 
 * with this program; if not, write to the Free Software Foundation, Inc., 
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA 
 *
 */
package org.saiku.olap.query;

import java.util.Map;
import java.util.Properties;

import org.olap4j.Axis;
import org.olap4j.CellSet;
import org.olap4j.OlapStatement;
import org.olap4j.Scenario;
import org.olap4j.metadata.Cube;
import org.olap4j.query.QueryAxis;
import org.olap4j.query.QueryDimension;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuTag;
import org.saiku.olap.util.exception.SaikuOlapException;

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
	public void storeCellset(CellSet cs);
	public CellSet getCellset();
	public void setStatement(OlapStatement os);
	public OlapStatement getStatement();
	public void cancel() throws Exception;
	

}
