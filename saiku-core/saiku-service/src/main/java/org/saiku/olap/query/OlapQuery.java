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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;
import java.util.Properties;

import org.olap4j.Axis;
import org.olap4j.Axis.Standard;
import org.olap4j.CellSet;
import org.olap4j.OlapConnection;
import org.olap4j.OlapStatement;
import org.olap4j.Scenario;
import org.olap4j.impl.IdentifierParser;
import org.olap4j.mdx.ParseTreeWriter;
import org.olap4j.metadata.Catalog;
import org.olap4j.metadata.Cube;
import org.olap4j.query.Query;
import org.olap4j.query.QueryAxis;
import org.olap4j.query.QueryDimension;
import org.olap4j.query.QueryDimension.HierarchizeMode;
import org.olap4j.query.Selection;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuTag;
import org.saiku.olap.query.QueryProperties.QueryProperty;
import org.saiku.olap.query.QueryProperties.QueryPropertyFactory;
import org.saiku.olap.util.SaikuProperties;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OlapQuery implements IQuery {

    private static final Logger log = LoggerFactory.getLogger(OlapQuery.class);

    private static final String SCENARIO = "Scenario";

	private Query query;
	private Properties properties = new Properties();

	private SaikuCube cube;
	
	private Scenario scenario;
	
	private SaikuTag tag = null;
	
	private CellSet cellset = null;

	private OlapStatement statement = null;

	private OlapConnection connection;
	
	public OlapQuery(Query query, OlapConnection connection, SaikuCube cube, boolean applyDefaultProperties) {
		this.query = query;
		this.cube = cube;
		this.connection = connection;
		if (applyDefaultProperties) {
			applyDefaultProperties();	
		}
	}

	public OlapQuery(Query query, OlapConnection connection, SaikuCube cube) {
		this(query,connection, cube,true);
	}
	
	public void swapAxes() {
		this.query.swapAxes();
	}
	
	public Map<Axis, QueryAxis> getAxes() {
		return this.query.getAxes();
	}
	
	public QueryAxis getAxis(Axis axis) {
		return this.query.getAxis(axis);
	}
	
	public QueryAxis getAxis(String name) throws SaikuOlapException {
		if ("UNUSED".equals(name)) {
			return getUnusedAxis();
		}
		Standard standardAxis = Standard.valueOf(name);
		if (standardAxis == null)
			throw new SaikuOlapException("Axis ("+name+") not found for query ("+ query.getName() + ")");
		
		Axis queryAxis = Axis.Factory.forOrdinal(standardAxis.axisOrdinal());
		return query.getAxis(queryAxis);
	}
	
	public Cube getCube() {
		return this.query.getCube();
	}
	
	public QueryAxis getUnusedAxis() {
		return this.query.getUnusedAxis();
	}
	
	public void moveDimension(QueryDimension dimension, Axis axis) {
	}

	public void moveDimension(QueryDimension dimension, Axis axis, int position) {
			dimension.setHierarchyConsistent(true);
	
	public QueryDimension getDimension(String name) {
		return this.query.getDimension(name);
	}

	private QueryAxis findAxis(QueryDimension dimension) {

		if (query.getUnusedAxis().getDimensions().contains(dimension)) {
			return query.getUnusedAxis();
		}
		else {
				Map<Axis,QueryAxis> axes = query.getAxes();
				for (Axis axis : axes.keySet()) {
				if (axes.get(axis).getDimensions().contains(dimension)) {
				return axes.get(axis);
				}
			}
		}
		return null;
	}
	

    public String getMdx() {
        final Writer writer = new StringWriter();
        this.query.getSelect().unparse(new ParseTreeWriter(new PrintWriter(writer)));
        return writer.toString();
    }
    
    public SaikuCube getSaikuCube() {
    	return cube;
    }
    
    public String getName() {
    	return query.getName();
    }
    
    public CellSet execute() throws Exception {
    	if (scenario != null && query.getDimension(SCENARIO) != null) {
    		QueryDimension dimension = query.getDimension(SCENARIO);
    		moveDimension(dimension, Axis.FILTER);
    		Selection sel = dimension.createSelection(IdentifierParser.parseIdentifier("[Scenario].[" + getScenario().getId() + "]"));
    		if (!dimension.getInclusions().contains(sel)) {
    			dimension.getInclusions().add(sel);
    		}
    	}
    	
        String mdx = getMdx();
        
        log.debug("Executing query (" + this.getName() + ") :\n" + mdx);
        
        final Catalog catalog = query.getCube().getSchema().getCatalog();
        this.connection.setCatalog(catalog.getName());
		OlapStatement stmt = connection.createStatement();
		this.statement = stmt;
		CellSet cellSet = stmt.executeOlapQuery(mdx);
		this.statement.close();
		this.statement = null;
    	if (scenario != null && query.getDimension(SCENARIO) != null) {
    		QueryDimension dimension = query.getDimension(SCENARIO);
    		dimension.getInclusions().clear();
    		moveDimension(dimension, null);
    	}

        return cellSet;
    }
    
    private void applyDefaultProperties() {
    	if (SaikuProperties.olapDefaultNonEmpty) {
    		query.getAxis(Axis.ROWS).setNonEmpty(true);
    		query.getAxis(Axis.COLUMNS).setNonEmpty(true);
    	}
    }
    
	public void resetAxisSelections(QueryAxis axis) {
		for (QueryDimension dim : axis.getDimensions()) {
			dim.clearInclusions();
			dim.clearExclusions();
			dim.clearSort();
		}
	}

	public void clearAllQuerySelections() {
		resetAxisSelections(getUnusedAxis());
		Map<Axis,QueryAxis> axes = getAxes();
		for (Axis axis : axes.keySet()) {
			resetAxisSelections(axes.get(axis));
		}
	}
	
	public void resetQuery() {
		resetAxisSelections(getUnusedAxis());
		Map<Axis,QueryAxis> axes = getAxes();
		for (Axis axis : axes.keySet()) {
			resetAxisSelections(axes.get(axis));
			if (axis != null) {
				for (QueryDimension dim : getAxis(axis).getDimensions())
				moveDimension(dim, getUnusedAxis().getLocation());
			}
		}
	}
    
    public void setProperties(Properties props) {
    	this.properties = props;
    	for (Object _key : props.keySet()) {
    		String key = (String) _key;
    		String value = props.getProperty((String) key);
    		QueryProperty prop = QueryPropertyFactory.getProperty(key, value, this);
    		prop.handle();
    	}
    }
    
    public Properties getProperties() {
    	this.properties.putAll(QueryPropertyFactory.forQuery(this));
    	return this.properties;
    }
    
    public String toXml() {
    	QuerySerializer qs = new QuerySerializer(this);
    	return qs.createXML();
    }
    
    public Boolean isDrillThroughEnabled() {
    	return query.getCube().isDrillThroughEnabled();
    }

	public QueryType getType() {
		return QueryType.QM;
	}
	
	public void setMdx(String mdx) {
		throw new UnsupportedOperationException();
	}
	
	public void setScenario(Scenario scenario) {
		this.scenario = scenario;
	}
	
	public Scenario getScenario() {
		return scenario;
	}

	public void setTag(SaikuTag tag) {
		this.tag = tag;
	}

	public SaikuTag getTag() {
		return this.tag;
	}

	public void removeTag() {
		tag = null;		
	}

	public void storeCellset(CellSet cs) {
		this.cellset = cs;
		
	}

	public CellSet getCellset() {
		return cellset;
	}

	public void setStatement(OlapStatement os) {
		this.statement = os;
		
	}

	public OlapStatement getStatement() {
		return this.statement;
	}

	public void cancel() throws Exception {
		if (this.statement != null && !this.statement.isClosed()) {
			statement.cancel();
			statement.close();
		}
		this.statement = null;
	}

}
