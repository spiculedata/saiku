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
package org.saiku.service.olap;

import org.apache.commons.lang.StringUtils;
import org.olap4j.*;
import org.olap4j.impl.IdentifierParser;
import org.olap4j.mdx.IdentifierNode;
import org.olap4j.mdx.IdentifierSegment;
import org.olap4j.mdx.ParseTreeWriter;
import org.olap4j.mdx.SelectNode;
import org.olap4j.mdx.parser.impl.DefaultMdxParserImpl;
import org.olap4j.metadata.Cube;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Member;
import org.olap4j.query.*;
import org.saiku.olap.dto.*;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query.IQuery;
import org.saiku.olap.query.MdxQuery;
import org.saiku.olap.query.OlapQuery;
import org.saiku.olap.query.QueryDeserializer;
import org.saiku.olap.util.ObjectUtil;
import org.saiku.olap.util.OlapResultSetUtil;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.olap.util.formatter.CellSetFormatter;
import org.saiku.olap.util.formatter.FlattenedCellSetFormatter;
import org.saiku.olap.util.formatter.HierarchicalCellSetFormatter;
import org.saiku.olap.util.formatter.ICellSetFormatter;
import org.saiku.service.util.KeyValue;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.service.util.export.CsvExporter;
import org.saiku.service.util.export.ExcelExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class OlapQueryService implements Serializable {

	/**
	 * Unique serialization UID 
	 */
	private static final long serialVersionUID = -7615296596528274904L;

	private static final Logger log = LoggerFactory.getLogger(OlapQueryService.class);

	private OlapDiscoverService olapDiscoverService;

	private Map<String, IQuery> queries = new HashMap<String, IQuery>(); 

	public void setOlapDiscoverService(OlapDiscoverService os) {
		olapDiscoverService = os;
	}

	public OlapQueryService() {
//		System.out.println("Constructor: ID " + Thread.currentThread().getId() + " Name: " + Thread.currentThread().getName());

	}
	public SaikuQuery createNewOlapQuery(String queryName, SaikuCube cube) {
		try {
			Cube cub = olapDiscoverService.getNativeCube(cube);
			OlapConnection con = olapDiscoverService.getNativeConnection(cube.getConnectionName());

			if (cub != null) {
				IQuery query = new OlapQuery(new Query(queryName, cub), con,cube);
				putIQuery(queryName, query);
				return ObjectUtil.convert(query);
			}
		} catch (Exception e) {
			log.error("Cannot create new query for cube :" + cube,e);
		}
		return null;

	}

	public SaikuQuery createNewOlapQuery(String name, String xml) {
		try {
			SaikuCube scube = QueryDeserializer.getFakeCube(xml);
			OlapConnection con = olapDiscoverService.getNativeConnection(scube.getConnectionName());
			IQuery query = QueryDeserializer.unparse(xml, con);
			if (name == null) {
				putIQuery(query.getName(), query);
			}
			else {
				putIQuery(name, query);
			}
			return ObjectUtil.convert(query);
		} catch (Exception e) {
			throw new SaikuServiceException("Error creating query from xml",e);
		}
	}


	public void closeQuery(String queryName) {
		try {
			IQuery q = getIQuery(queryName);
			q.cancel();
			removeIQuery(queryName);
		} catch (Exception e) {
			throw new SaikuServiceException("Error closing query: " + queryName,e);
		}
	}

	public List<String> getQueries() {
		List<String> queryList = new ArrayList<String>();
		queryList.addAll(getIQueryMap().keySet());
		return queryList;
	}

	public SaikuQuery getQuery(String queryName) {
		IQuery q = getIQuery(queryName);
		return ObjectUtil.convert(q);
	}

	public void deleteQuery(String queryName) {
		removeIQuery(queryName);
	}
	
	public void cancel(String queryName) {
		try {
//			System.out.println("Cancel: ID " + Thread.currentThread().getId() + " Name: " + Thread.currentThread().getName());
			IQuery q = getIQuery(queryName);
			q.cancel();
		} catch (Exception e) {
			throw new SaikuServiceException("Error cancelling query: " + queryName,e);
		}
	}
	

	public CellDataSet execute(String queryName) {
		return execute(queryName,new HierarchicalCellSetFormatter());
	}

	public CellDataSet execute(String queryName, String formatter) {
		formatter = formatter == null ? "" : formatter.toLowerCase(); 
		if(formatter.equals("flat")) {
			return execute(queryName, new CellSetFormatter());
		}
		else if (formatter.equals("hierarchical")) {
			return execute(queryName, new HierarchicalCellSetFormatter());
		}
		else if (formatter.equals("flattened")) {
			return execute(queryName, new FlattenedCellSetFormatter());
		}
		return execute(queryName, new HierarchicalCellSetFormatter());
	}

	public CellDataSet execute(String queryName, ICellSetFormatter formatter) {
		try {
//			System.out.println("Execute: ID " + Thread.currentThread().getId() + " Name: " + Thread.currentThread().getName());
			IQuery query = getIQuery(queryName);
			OlapConnection con = olapDiscoverService.getNativeConnection(query.getSaikuCube().getConnectionName());
			Long start = (new Date()).getTime();
			if (query.getScenario() != null) {
				log.info("Query (" + queryName + ") Setting scenario:" + query.getScenario().getId());
				con.setScenario(query.getScenario());
			}

			if (query.getTag() != null) {
				query = applyTag(query, con, query.getTag());
			}
			CellSet cellSet =  query.execute();
			Long exec = (new Date()).getTime();

			if (query.getScenario() != null) {
				log.info("Query (" + queryName + ") removing scenario:" + query.getScenario().getId());
				con.setScenario(null);
			}

			CellDataSet result = OlapResultSetUtil.cellSet2Matrix(cellSet,formatter);
			Long format = (new Date()).getTime();
			log.info("Size: " + result.getWidth() + "/" + result.getHeight() + "\tExecute:\t" + (exec - start)
					+ "ms\tFormat:\t" + (format - exec) + "ms\t Total: " + (format - start) + "ms");
			result.setRuntime(new Double(format - start).intValue());
			getIQuery(queryName).storeCellset(cellSet);
			return result;
		} catch (Exception e) {
			throw new SaikuServiceException("Can't execute query: " + queryName,e);
		} catch (Error e) {
			throw new SaikuServiceException("Can't execute query: " + queryName,e);
		}
	}
	
	public SaikuQuery simulateTag(String queryName, SaikuTag tag) {
		try {
			IQuery query = getIQuery(queryName);
			OlapConnection con = olapDiscoverService.getNativeConnection(query.getSaikuCube().getConnectionName());
			return ObjectUtil.convert(applyTag(query, con, tag));
		} catch (Exception e) {
			throw new SaikuServiceException("Can't apply tag: " + tag + " to query "+ queryName,e);
		}
	}
	
	private IQuery applyTag(IQuery query, OlapConnection con, SaikuTag t) throws Exception {
		String xml = query.toXml();
		query = QueryDeserializer.unparse(xml, con);
		
		List<SaikuTupleDimension> doneDimension = new ArrayList<SaikuTupleDimension>();
		Map<String,QueryDimension> dimensionMap = new HashMap<String,QueryDimension>();
		if (t.getSaikuTupleDimensions() != null) {
			for (SaikuTupleDimension st : t.getSaikuTupleDimensions()) {
				if (!doneDimension.contains(st)) {
					QueryDimension dim = query.getDimension(st.getName());
					dimensionMap.put(st.getUniqueName(), dim);
					dim.clearExclusions();
					dim.clearInclusions();
					query.moveDimension(dim, null);
					doneDimension.add(st);
				}
			}
			if (t.getSaikuTupleDimensions().size() > 0) {
				SaikuTupleDimension rootDim = t.getSaikuTupleDimensions().get(0);
				QueryDimension dim = query.getDimension(rootDim.getName());
				query.moveDimension(dim, Axis.COLUMNS);

				for (SaikuTuple tuple : t.getSaikuTuples()) {
					SaikuMember m = tuple.getSaikuMember(rootDim.getUniqueName());
					List<SaikuMember> others = tuple.getOtherSaikuMembers(rootDim.getUniqueName());
					Selection sel = dim.createSelection(IdentifierParser.parseIdentifier(m.getUniqueName()));
					for (SaikuMember context : others) {
						QueryDimension otherDim = dimensionMap.get(context.getDimensionUniqueName());
						query.moveDimension(otherDim, Axis.COLUMNS);
						Selection ctxSel = otherDim.createSelection(IdentifierParser.parseIdentifier(context.getUniqueName()));
						sel.addContext(ctxSel);
					}
					dim.getInclusions().add(sel);
				}
			}
		}
		if (t.getSaikuDimensionSelections() != null) {
			for (SaikuDimensionSelection dimsel : t.getSaikuDimensionSelections()) {
				if (!dimsel.getName().equals("Measures")) {
					QueryDimension filterDim = query.getDimension(dimsel.getName());
					query.moveDimension(filterDim, Axis.FILTER);
					filterDim.clearInclusions();
					for (SaikuSelection ss : dimsel.getSelections()) {
						if (ss.getType() == SaikuSelection.Type.MEMBER) {
							Selection sel = filterDim.createSelection(IdentifierParser.parseIdentifier(ss.getUniqueName()));
							if (!filterDim.getInclusions().contains(sel)) {
								filterDim.getInclusions().add(sel);
							}
						}
					}
					// TODO: Move it to columns since drilling through with 2 filter items of the same dimension doesn't work
//					if (filterDim.getInclusions().size() > 1) {
//						query.moveDimension(filterDim, Axis.COLUMNS);
//					}
				}
			}
		}
		
		return query;
	}

	public void setMdx(String queryName, String mdx) {
		IQuery q = getIQuery(queryName);
		q.setMdx(mdx);
	}
	public CellDataSet executeMdx(String queryName, String mdx) {
		qm2mdx(queryName);
		setMdx(queryName, mdx);
		return execute(queryName, new HierarchicalCellSetFormatter());
	}

	public CellDataSet executeMdx(String queryName, String mdx, ICellSetFormatter formatter) {
		qm2mdx(queryName);
		setMdx(queryName, mdx);
		return execute(queryName, formatter);
	}

	public ResultSet drillthrough(String queryName, int maxrows, String returns) {
		try {
			final OlapConnection con = olapDiscoverService.getNativeConnection(getQuery(queryName).getCube().getConnectionName()); 
			final OlapStatement stmt = con.createStatement();
			String mdx = getMDXQuery(queryName);
			if (maxrows > 0) {
				mdx = "DRILLTHROUGH MAXROWS " + maxrows + " " + mdx;
			}
			else {
				mdx = "DRILLTHROUGH " + mdx;
			}
			if (StringUtils.isNotBlank(returns)) {
				mdx += "\r\n RETURN " + returns;
			}
			return  stmt.executeQuery(mdx);
		} catch (SQLException e) {
			throw new SaikuServiceException("Error DRILLTHROUGH: " + queryName,e);
		}
	}

	public ResultSet drillthrough(String queryName, List<Integer> cellPosition, Integer maxrows, String returns) {
		try {
			IQuery query = getIQuery(queryName);
			CellSet cs = query.getCellset();
			SaikuCube cube = getQuery(queryName).getCube();
			final OlapConnection con = olapDiscoverService.getNativeConnection(cube.getConnectionName()); 
			final OlapStatement stmt = con.createStatement();

			String select = null;
			StringBuffer buf = new StringBuffer();
			buf.append("SELECT (");
			for (int i = 0; i < cellPosition.size(); i++) {
				List<Member> members = cs.getAxes().get(i).getPositions().get(cellPosition.get(i)).getMembers();
				for (int k = 0; k < members.size(); k++) {
					Member m = members.get(k);
					if (k > 0 || i > 0) {
						buf.append(", ");
					}
					buf.append(m.getUniqueName());
				}
			}
			buf.append(") ON COLUMNS \r\n");
			buf.append("FROM " + cube.getCubeName() + "\r\n");
			
			SelectNode sn = (new DefaultMdxParserImpl().parseSelect(getMDXQuery(queryName))); 
			final Writer writer = new StringWriter();
			sn.getFilterAxis().unparse(new ParseTreeWriter(new PrintWriter(writer)));
			if (StringUtils.isNotBlank(writer.toString())) {
				buf.append("WHERE " + writer.toString());
			}
			select = buf.toString(); 
			if (maxrows > 0) {
				select = "DRILLTHROUGH MAXROWS " + maxrows + " " + select + "\r\n";
			}
			else {
				select = "DRILLTHROUGH " + select + "\r\n";
			}
			if (StringUtils.isNotBlank(returns)) {
				select += "\r\n RETURN " + returns;
			}

			log.debug("Drill Through for query (" + queryName + ") : \r\n" + select);
			return  stmt.executeQuery(select);


		} catch (Exception e) {
			throw new SaikuServiceException("Error DRILLTHROUGH: " + queryName,e);
		}
	}


	public byte[] exportDrillthroughCsv(String queryName, int maxrows) {
		try {
			final OlapConnection con = olapDiscoverService.getNativeConnection(getQuery(queryName).getCube().getConnectionName()); 
			final OlapStatement stmt = con.createStatement();
			String mdx = getMDXQuery(queryName);
			if (maxrows > 0) {
				mdx = "DRILLTHROUGH MAXROWS " + maxrows + " " + mdx;
			}
			else {
				mdx = "DRILLTHROUGH " + mdx;
			}
			ResultSet rs = stmt.executeQuery(mdx);
			return CsvExporter.exportCsv(rs);
		} catch (SQLException e) {
			throw new SaikuServiceException("Error DRILLTHROUGH: " + queryName,e);
		}
	}

	public byte[] exportResultSetCsv(ResultSet rs) {
		return CsvExporter.exportCsv(rs);
	}
	public byte[] exportResultSetCsv(ResultSet rs, String delimiter, String enclosing, boolean printHeader, List<KeyValue<String,String>> additionalColumns) {
		return CsvExporter.exportCsv(rs, delimiter, enclosing, printHeader, additionalColumns);
	}


	public void setCellValue(String queryName, List<Integer> position, String value, String allocationPolicy) {
		try {

			IQuery query = getIQuery(queryName);
			OlapConnection con = olapDiscoverService.getNativeConnection(query.getSaikuCube().getConnectionName());

			Scenario s;
			if (query.getScenario() == null) {
				s = con.createScenario();
				query.setScenario(s);
				con.setScenario(s);
				System.out.println("Created scenario:" + s + " : cell:" + position + " value" + value);
			} else {
				s = query.getScenario();
				con.setScenario(s);
				System.out.println("Using scenario:" + s + " : cell:" + position + " value" + value);

			}


			CellSet cs1 = query.execute();
			query.storeCellset(cs1);

			Object v = null;
			try {
				v = Integer.parseInt(value);
			} catch (Exception e) {
				v = Double.parseDouble(value);
			}
			if (v == null) {
				throw new SaikuServiceException("Error setting value of query " + queryName + " to:" + v);
			}

			allocationPolicy = AllocationPolicy.EQUAL_ALLOCATION.toString();

			AllocationPolicy ap = AllocationPolicy.valueOf(allocationPolicy);
			CellSet cs = query.getCellset();
			cs.getCell(position).setValue(v, ap);
			con.setScenario(null);
		} catch (Exception e) {
			throw new SaikuServiceException("Error setting value: " + queryName,e);
		}


	}

	public void swapAxes(String queryName) {
		getIQuery(queryName).swapAxes();
	}

	public boolean includeMember(String queryName, String dimensionName, String uniqueMemberName, String selectionType, int memberposition){
		IQuery query = getIQuery(queryName);
		List<IdentifierSegment> memberList = IdentifierNode.parseIdentifier(uniqueMemberName).getSegmentList();
		QueryDimension dimension = query.getDimension(dimensionName);
		final Selection.Operator selectionMode = Selection.Operator.valueOf(selectionType);
		try {
			Selection sel = dimension.createSelection(selectionMode, memberList);
			if (dimension.getInclusions().contains(sel)) {
				dimension.getInclusions().remove(sel);
			}
			if (memberposition < 0) {
				memberposition = dimension.getInclusions().size();
			}
			dimension.getInclusions().add(memberposition, sel);
			return true;
		} catch (OlapException e) {
			throw new SaikuServiceException("Cannot include member query ("+queryName+") dimension (" + dimensionName + ") member ("+
					uniqueMemberName+") operator (" + selectionType + ") position " + memberposition,e);
		}
	}

	public boolean removeMember(String queryName, String dimensionName, String uniqueMemberName, String selectionType) throws SaikuServiceException{
		IQuery query = getIQuery(queryName);
		List<IdentifierSegment> memberList = IdentifierNode.parseIdentifier(uniqueMemberName).getSegmentList();
		QueryDimension dimension = query.getDimension(dimensionName);
		final Selection.Operator selectionMode = Selection.Operator.valueOf(selectionType);
		try {
			if (log.isDebugEnabled()) {
				log.debug("query: "+queryName+" remove:" + selectionMode.toString() + " " + memberList.size());
			}
			Selection selection = dimension.createSelection(selectionMode, memberList);
			dimension.getInclusions().remove(selection);
			return true;
		} catch (OlapException e) {
			throw new SaikuServiceException("Error removing member (" + uniqueMemberName + ") of dimension (" +dimensionName+")",e);
		}
	}

	public boolean includeLevel(String queryName, String dimensionName, String uniqueHierarchyName, String uniqueLevelName) {
		IQuery query = getIQuery(queryName);
		QueryDimension dimension = query.getDimension(dimensionName);
		for (Hierarchy hierarchy : dimension.getDimension().getHierarchies()) {
			if (hierarchy.getUniqueName().equals(uniqueHierarchyName)) {
				for (Level level : hierarchy.getLevels()) {
					if (level.getUniqueName().equals(uniqueLevelName)) {
						Selection sel = dimension.createSelection(level);
						if (!dimension.getInclusions().contains(sel)) {
							dimension.include(level);
						}
						return true;
					}
				}
			}
		}
		return false;
	}

	public boolean removeLevel(String queryName, String dimensionName, String uniqueHierarchyName, String uniqueLevelName) {
		IQuery query = getIQuery(queryName);
		QueryDimension dimension = query.getDimension(dimensionName);
		try {
			for (Hierarchy hierarchy : dimension.getDimension().getHierarchies()) {		
				if (hierarchy.getUniqueName().equals(uniqueHierarchyName)) {
					for (Level level : hierarchy.getLevels()) {
						if (level.getUniqueName().equals(uniqueLevelName)) {
							Selection inclusion = dimension.createSelection(level);
							dimension.getInclusions().remove(inclusion);
							ArrayList<Selection> removals = new ArrayList<Selection>();
							for (Selection sel :dimension.getInclusions()) {
								if ((sel.getRootElement() instanceof Member)) {
									if (((Member) sel.getRootElement()).getLevel().equals(level)) {
										if (dimension.getInclusions().contains(sel)) {
											removals.add(sel);
										}
									}
								}
							}
                        }
					}
				}
			}
		} catch (Exception e) {
			throw new SaikuServiceException("Cannot remove level" + uniqueLevelName + "from dimension " + dimensionName,e);
		}
		return true;
	}


	public void moveDimension(String queryName, String axisName, String dimensionName, int position) {
		try {
			if (log.isDebugEnabled()) {
				log.debug("move query: " + queryName + " dimension " + dimensionName + " to axis " + axisName + "  position" + position);
			}
			IQuery query = getIQuery(queryName);
			QueryDimension dimension = query.getDimension(dimensionName);
			Axis newAxis = axisName != null ? ( "UNUSED".equals(axisName) ? null : Axis.Standard.valueOf(axisName)) : null;
			if(position==-1){
				query.moveDimension(dimension, newAxis);
			}
			else{
				query.moveDimension(dimension, newAxis, position);
			}
		}
		catch (Exception e) {
			throw new SaikuServiceException("Cannot move dimension:" + dimensionName + " to axis: "+axisName,e);
		}
    }

	public void removeDimension(String queryName, String axisName, String dimensionName) {
		IQuery query = getIQuery(queryName);
		moveDimension(queryName, "UNUSED" , dimensionName, -1);
		query.getDimension(dimensionName).getExclusions().clear();
		query.getDimension(dimensionName).getInclusions().clear();
	}

	public List<SaikuDimensionSelection> getAxisSelection(String queryName, String axis) {
		IQuery query = getIQuery(queryName);

		List<SaikuDimensionSelection> dimsel = new ArrayList<SaikuDimensionSelection>();
		try {
			QueryAxis qaxis = query.getAxis(axis);
			if (qaxis != null) {
				for (QueryDimension dim : qaxis.getDimensions()) {
					dimsel.add(ObjectUtil.convertDimensionSelection(dim));
				}
			}
		} catch (SaikuOlapException e) {
			throw new SaikuServiceException("Cannot get dimension selections",e);
		}
		return dimsel;
	}

	public SaikuDimensionSelection getAxisDimensionSelections(String queryName, String axis, String dimension) {
		IQuery query = getIQuery(queryName);
		try {
			QueryAxis qaxis = query.getAxis(axis);
			if (qaxis != null) {
				QueryDimension dim = query.getDimension(dimension);
				if (dim != null) {
					return ObjectUtil.convertDimensionSelection(dim);
				}
				else
				{
					throw new SaikuOlapException("Cannot find dimension with name:" + dimension);
				}
			}
			else {
				throw new SaikuOlapException("Cannot find axis with name:" + axis);
			}
		} catch (SaikuOlapException e) {
			throw new SaikuServiceException("Cannot get dimension selections",e);
		}
	}

	public void clearQuery(String queryName) {
		IQuery query = getIQuery(queryName);
		query.clearAllQuerySelections();
	}

	public void clearAxis(String queryName, String axisName) {
		IQuery query = getIQuery(queryName);
		if (Axis.Standard.valueOf(axisName) != null) {
			QueryAxis qAxis = query.getAxis(Axis.Standard.valueOf(axisName));
			query.resetAxisSelections(qAxis);
			for (QueryDimension dim : qAxis.getDimensions()) {
				qAxis.removeDimension(dim);
			}
		}
	}

	public void clearAxisSelections(String queryName, String axisName) {
		IQuery query = getIQuery(queryName);
		if (Axis.Standard.valueOf(axisName) != null) {
			QueryAxis qAxis = query.getAxis(Axis.Standard.valueOf(axisName));
			query.resetAxisSelections(qAxis);
		}
	}
	
	public void sortAxis(String queryName, String axisName, String sortLiteral, String sortOrder) {
		IQuery query = getIQuery(queryName);
		if (Axis.Standard.valueOf(axisName) != null) {
			QueryAxis qAxis = query.getAxis(Axis.Standard.valueOf(axisName));
			SortOrder so = SortOrder.valueOf(sortOrder);
			qAxis.sort(so, sortLiteral);
		}
	}
	
	public void clearSort(String queryName, String axisName) {
		IQuery query = getIQuery(queryName);
		if (Axis.Standard.valueOf(axisName) != null) {
			QueryAxis qAxis = query.getAxis(Axis.Standard.valueOf(axisName));
			qAxis.clearSort();
		}
	}

	public void resetQuery(String queryName) {
		IQuery query = getIQuery(queryName);
		query.resetQuery();
	}

	public void setNonEmpty(String queryName, String axisName, boolean bool) {
		IQuery query = getIQuery(queryName);
		QueryAxis newAxis = query.getAxis(Axis.Standard.valueOf(axisName));
		newAxis.setNonEmpty(bool);
	}

	public Properties setProperties(String queryName, Properties props) {
		IQuery query = getIQuery(queryName);
		query.setProperties(props);
		return getProperties(queryName);
	}	


	public Properties getProperties(String queryName) {
		IQuery query = getIQuery(queryName);
		OlapConnection con = olapDiscoverService.getNativeConnection(query.getSaikuCube().getConnectionName());
		Properties props = query.getProperties();
		try {
			con.createScenario();
			if (query.getDimension("Scenario") != null) {
				props.put("org.saiku.connection.scenario", Boolean.toString(true));
			}
			else {
				props.put("org.saiku.connection.scenario", Boolean.toString(false));
			}
		} catch (Exception e) {
			props.put("org.saiku.connection.scenario", Boolean.toString(false));
		}
		return props;
	}

	public String getMDXQuery(String queryName) {
		return getIQuery(queryName).getMdx();
	}

	public String getQueryXml(String queryName) {
		IQuery query = getIQuery(queryName);
		return query.toXml();
	}

	public byte[] getExport(String queryName, String type) {
		return getExport(queryName,type,new HierarchicalCellSetFormatter());
	}

	public byte[] getExport(String queryName, String type, String formatter) {
		formatter = formatter == null ? "" : formatter.toLowerCase();
		if (formatter.equals("flat")) {
			return getExport(queryName, type, new CellSetFormatter());			
		}else if (formatter.equals("flattened")) {
			return getExport(queryName, type, new FlattenedCellSetFormatter());
		} else if (formatter.equals("hierarchical")) {
			return getExport(queryName, type, new HierarchicalCellSetFormatter());
		}

		return getExport(queryName, type, new HierarchicalCellSetFormatter());
	}

	public byte[] getExport(String queryName, String type, ICellSetFormatter formatter) {
		if (type != null) {
			IQuery query = getIQuery(queryName);
			CellSet rs = query.getCellset();
            List<SaikuDimensionSelection> filters = getAxisSelection(queryName, "FILTER");

			if (type.toLowerCase().equals("xls")) {
				return ExcelExporter.exportExcel(rs, formatter, filters);
			}
			if (type.toLowerCase().equals("csv")) {
				return CsvExporter.exportCsv(rs,",","\"", formatter);
			}
		}
		return new byte[0];
	}

	public void qm2mdx(String queryName) {
		IQuery query = getIQuery(queryName);
		OlapConnection con = olapDiscoverService.getNativeConnection(query.getSaikuCube().getConnectionName());
		MdxQuery mdx = new MdxQuery(con, query.getSaikuCube(), query.getName(),getMDXQuery(queryName));
		putIQuery(queryName, mdx);
		query = null;
	}

	public SaikuTag createTag(String queryName, String tagName, List<List<Integer>> cellPositions) {
		try {
			IQuery query = getIQuery(queryName);
			SaikuCube cube = getQuery(queryName).getCube();
			CellSet cs = query.getCellset();
			List<SaikuTuple> tuples = new ArrayList<SaikuTuple>();
			List<SaikuTupleDimension> dimensions = new ArrayList<SaikuTupleDimension>();
			for(List<Integer> cellPosition : cellPositions) {
				List<Member> members = new ArrayList<Member>();
				for (int i = 0; i < cellPosition.size(); i++) {
					members.addAll(cs.getAxes().get(i).getPositions().get(cellPosition.get(i)).getMembers());
				}
				List <SaikuMember> sm = ObjectUtil.convertMembers(members);
				SaikuTuple tuple = new SaikuTuple(sm);
				tuples.add(tuple);
				
				if (dimensions.size() == 0) {
					for (Member m : members) {
						SaikuTupleDimension sd = 
							new SaikuTupleDimension(
								m.getDimension().getName(),
								m.getDimension().getUniqueName(),
								m.getDimension().getCaption());
						if (!dimensions.contains(sd)) {
							dimensions.add(sd);
						}
					}
				}
			}
			List<SaikuDimensionSelection> filterSelections = getAxisSelection(queryName, "FILTER");
			SaikuTag t = new SaikuTag(tagName, dimensions, tuples, filterSelections);
			return t;
			
		} catch (Exception e) {
			throw new SaikuServiceException("Error addTag:" + tagName + " for query: " + queryName,e);
		}
	}
	
	public void setTag(String queryName, SaikuTag tag) {
		IQuery query = getIQuery(queryName);
		query.setTag(tag);
	}
	
	public void disableTag(String queryName) {
		IQuery query = getIQuery(queryName);
		query.removeTag();
	}

	private void putIQuery(String queryName, IQuery query) {
		queries.put(queryName, query);
	}
	
	private void removeIQuery(String queryName) {
		queries.remove(queryName);
	}
	
	
	private IQuery getIQuery(String queryName) {
		return  queries.get(queryName);
	}
	
	private Map<String, IQuery> getIQueryMap() {
		return queries;
	}

}
