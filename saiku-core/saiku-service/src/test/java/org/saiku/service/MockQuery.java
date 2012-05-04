package org.saiku.service;

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
import org.saiku.olap.query.IQuery;
import org.saiku.olap.util.exception.SaikuOlapException;

public class MockQuery implements IQuery {

	private String name;

	public MockQuery(String name) {
		this.name = name;
	}
	public String getName() {
		return name;
	}

	public SaikuCube getSaikuCube() {
		// TODO Auto-generated method stub
		return null;
	}

	public CellSet execute() throws Exception {
		// TODO Auto-generated method stub
		return null;
	}

	public String getMdx() {
		// TODO Auto-generated method stub
		return null;
	}

	public void resetQuery() {
		// TODO Auto-generated method stub

	}

	public void setProperties(Properties props) {
		// TODO Auto-generated method stub

	}

	public Properties getProperties() {
		// TODO Auto-generated method stub
		return null;
	}

	public String toXml() {
		// TODO Auto-generated method stub
		return null;
	}

	public Boolean isDrillThroughEnabled() {
		// TODO Auto-generated method stub
		return null;
	}

	public QueryType getType() {
		// TODO Auto-generated method stub
		return null;
	}

	public void swapAxes() {
		// TODO Auto-generated method stub

	}

	public Map<Axis, QueryAxis> getAxes() {
		// TODO Auto-generated method stub
		return null;
	}

	public QueryAxis getAxis(Axis axis) {
		// TODO Auto-generated method stub
		return null;
	}

	public QueryAxis getAxis(String name) throws SaikuOlapException {
		// TODO Auto-generated method stub
		return null;
	}

	public Cube getCube() {
		// TODO Auto-generated method stub
		return null;
	}

	public QueryAxis getUnusedAxis() {
		// TODO Auto-generated method stub
		return null;
	}

	public void moveDimension(QueryDimension dimension, Axis axis) {
		// TODO Auto-generated method stub

	}

	public void moveDimension(QueryDimension dimension, Axis axis, int position) {
		// TODO Auto-generated method stub

	}

	public QueryDimension getDimension(String name) {
		// TODO Auto-generated method stub
		return null;
	}

	public void resetAxisSelections(QueryAxis axis) {
		// TODO Auto-generated method stub

	}

	public void clearAllQuerySelections() {
		// TODO Auto-generated method stub

	}

	public void setMdx(String mdx) {
		// TODO Auto-generated method stub

	}

	public void setScenario(Scenario scenario) {
		// TODO Auto-generated method stub

	}

	public Scenario getScenario() {
		// TODO Auto-generated method stub
		return null;
	}

	public void setTag(SaikuTag tag) {
		// TODO Auto-generated method stub

	}

	public SaikuTag getTag() {
		// TODO Auto-generated method stub
		return null;
	}

	public void removeTag() {
		// TODO Auto-generated method stub

	}

	public void storeCellset(CellSet cs) {
		// TODO Auto-generated method stub

	}

	public CellSet getCellset() {
		// TODO Auto-generated method stub
		return null;
	}

	public void setStatement(OlapStatement os) {
		// TODO Auto-generated method stub

	}

	public OlapStatement getStatement() {
		// TODO Auto-generated method stub
		return null;
	}

	public void cancel() throws Exception {
		// TODO Auto-generated method stub

	}

}
