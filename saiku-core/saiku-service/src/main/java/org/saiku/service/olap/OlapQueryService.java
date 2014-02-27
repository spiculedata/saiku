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

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import mondrian.rolap.RolapConnection;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.olap4j.AllocationPolicy;
import org.olap4j.Axis;
import org.olap4j.CellSet;
import org.olap4j.CellSetAxis;
import org.olap4j.OlapConnection;
import org.olap4j.OlapException;
import org.olap4j.OlapStatement;
import org.olap4j.Position;
import org.olap4j.Scenario;
import org.olap4j.impl.IdentifierParser;
import org.olap4j.mdx.IdentifierNode;
import org.olap4j.mdx.IdentifierSegment;
import org.olap4j.mdx.ParseTreeNode;
import org.olap4j.mdx.ParseTreeWriter;
import org.olap4j.mdx.SelectNode;
import org.olap4j.mdx.parser.impl.DefaultMdxParserImpl;
import org.olap4j.metadata.Cube;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Level.Type;
import org.olap4j.metadata.Measure;
import org.olap4j.metadata.Member;
import org.olap4j.metadata.MetadataElement;
import org.olap4j.query.LimitFunction;
import org.olap4j.query.Query;
import org.olap4j.query.QueryAxis;
import org.olap4j.query.QueryDimension;
import org.olap4j.query.Selection;
import org.olap4j.query.Selection.Operator;
import org.olap4j.query.SortOrder;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuDimensionSelection;
import org.saiku.olap.dto.SaikuMember;
import org.saiku.olap.dto.SaikuQuery;
import org.saiku.olap.dto.SaikuSelection;
import org.saiku.olap.dto.SaikuTag;
import org.saiku.olap.dto.SaikuTuple;
import org.saiku.olap.dto.SimpleCubeElement;
import org.saiku.olap.dto.filter.SaikuFilter;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.olap.query.IQuery;
import org.saiku.olap.query.IQuery.QueryType;
import org.saiku.olap.query.MdxQuery;
import org.saiku.olap.query.OlapQuery;
import org.saiku.olap.query.QueryDeserializer;
import org.saiku.olap.util.ObjectUtil;
import org.saiku.olap.util.OlapResultSetUtil;
import org.saiku.olap.util.SaikuUniqueNameComparator;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.olap.util.formatter.CellSetFormatter;
import org.saiku.olap.util.formatter.FlattenedCellSetFormatter;
import org.saiku.olap.util.formatter.HierarchicalCellSetFormatter;
import org.saiku.olap.util.formatter.ICellSetFormatter;
import org.saiku.service.olap.totals.AxisInfo;
import org.saiku.service.olap.totals.TotalNode;
import org.saiku.service.olap.totals.TotalsListsBuilder;
import org.saiku.service.olap.totals.aggregators.TotalAggregator;
import org.saiku.service.util.KeyValue;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.service.util.export.CsvExporter;
import org.saiku.service.util.export.ExcelExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OlapQueryService implements Serializable {

	/**
	 * Unique serialization UID 
	 */
	private static final long serialVersionUID = -7615296596528274904L;

	private static final Logger log = LoggerFactory.getLogger(OlapQueryService.class);

	private OlapDiscoverService olapDiscoverService;

	private Map<String, IQuery> queries = new HashMap<String, IQuery>(); 

	private static final AtomicLong ID_GENERATOR = new AtomicLong();

	public void setOlapDiscoverService(OlapDiscoverService os) {
		olapDiscoverService = os;
	}

	public OlapQueryService() {	}

	public void destroy() {
		for (Object  q : queries.keySet().toArray()) {
			closeQuery(q.toString());
		}
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
			QueryDeserializer qd = new QueryDeserializer();
			SaikuCube scube = qd.getFakeCube(xml);
			OlapConnection con = olapDiscoverService.getNativeConnection(scube.getConnectionName());
			IQuery query = qd.unparse(xml, con);
			// TODO - this is not good! could lead to duplicate queries
			if (name == null) {
				name = UUID.randomUUID().toString();
				putIQuery(name, query);
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
		return execute(queryName, new FlattenedCellSetFormatter());
	}
	
	public CellDataSet execute(String queryName, ICellSetFormatter formatter) {
		String runId = "runId:" + ID_GENERATOR.getAndIncrement();
		try {
			//			System.out.println("Execute: ID " + Thread.currentThread().getId() + " Name: " + Thread.currentThread().getName());
			IQuery query = getIQuery(queryName);
			OlapConnection con = olapDiscoverService.getNativeConnection(query.getSaikuCube().getConnectionName());


			Long start = (new Date()).getTime();
			if (query.getScenario() != null) {
				log.info(runId + "\tQuery: " + query.getName() + " Setting scenario:" + query.getScenario().getId());
				con.setScenario(query.getScenario());
			}

			if (query.getTag() != null) {
				query = applyTag(query, con, query.getTag());
			}

			String mdx = query.getMdx();
			log.info(runId + "\tType:" + query.getType() + ":\n" + mdx);

			CellSet cellSet =  query.execute();
			Long exec = (new Date()).getTime();

			if (query.getScenario() != null) {
				log.info("Query (" + queryName + ") removing scenario:" + query.getScenario().getId());
				con.setScenario(null);
			}

			CellDataSet result = OlapResultSetUtil.cellSet2Matrix(cellSet,formatter);
			Long format = (new Date()).getTime();
			log.info(runId + "\tSize: " + result.getWidth() + "/" + result.getHeight() + "\tExecute:\t" + (exec - start)
					+ "ms\tFormat:\t" + (format - exec) + "ms\t Total: " + (format - start) + "ms");
			result.setRuntime(new Double(format - start).intValue());
			getIQuery(queryName).storeCellset(cellSet);
			getIQuery(queryName).storeFormatter(formatter);
			if (QueryType.QM.equals(query.getType()) && formatter instanceof FlattenedCellSetFormatter) {
				QueryDimension queryDimension = query.getDimension("Measures");
				Measure[] selectedMeasures = new Measure[queryDimension.getInclusions().size()];
				for (int i = 0; i < selectedMeasures.length; i++)
					selectedMeasures[i] = (Measure) queryDimension.getInclusions().get(i).getRootElement();
				result.setSelectedMeasures(selectedMeasures);

				int rowsIndex = 0;
				if (!cellSet.getAxes().get(0).getAxisOrdinal().equals(Axis.ROWS)) {
					rowsIndex = (rowsIndex + 1) & 1;
				}
				final AxisInfo[] axisInfos = new AxisInfo[]{new AxisInfo(cellSet.getAxes().get(rowsIndex)), new AxisInfo(cellSet.getAxes().get((rowsIndex + 1) & 1))};
				List<TotalNode>[][] totals = new List[2][];
				TotalsListsBuilder builder = null;
				for (int index = 0; index < 2; index++) {
					final int second = (index + 1) & 1;
					TotalAggregator[] aggregators = new TotalAggregator[axisInfos[second].maxDepth + 1];
					for (int i = 1; i < aggregators.length - 1; i++) {
						String totalFunctionName = query.getTotalFunction(axisInfos[second].uniqueLevelNames.get(i - 1));
						aggregators[i] = TotalAggregator.newInstanceByFunctionName(totalFunctionName);
					}
					String totalFunctionName = query.getTotalFunction(axisInfos[second].axis.getAxisOrdinal().name()); 
					aggregators[0] = totalFunctionName != null ? TotalAggregator.newInstanceByFunctionName(totalFunctionName) : null;
					builder = new TotalsListsBuilder(selectedMeasures, aggregators, cellSet, axisInfos[index], axisInfos[second]);
					totals[index] = builder.buildTotalsLists();
				}
				result.setLeftOffset(axisInfos[0].maxDepth);
				result.setRowTotalsLists(totals[1]);
				result.setColTotalsLists(totals[0]);
			}
			return result;
		} catch (Exception e) {
			if (log.isInfoEnabled()) {
				String error = ExceptionUtils.getRootCauseMessage(e);
				log.info(runId + "\tException: " + error); 
			}
			throw new SaikuServiceException(runId + "\tCan't execute query: " + queryName,e);
		} catch (Error e) {
			if (log.isInfoEnabled()) {
				String error = ExceptionUtils.getRootCauseMessage(e);
				log.info(runId + "\tError: " + error); 
			}
			throw new SaikuServiceException(runId + "\tCan't execute query: " + queryName,e);
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
		QueryDeserializer qd = new QueryDeserializer();
		query = qd.unparse(xml, con);

		List<SimpleCubeElement> doneDimension = new ArrayList<SimpleCubeElement>();
		Map<String,QueryDimension> dimensionMap = new HashMap<String,QueryDimension>();
		if (t.getSaikuTupleDimensions() != null) {
			for (SimpleCubeElement st : t.getSaikuTupleDimensions()) {
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
				SimpleCubeElement rootDim = t.getSaikuTupleDimensions().get(0);
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

	public List<SimpleCubeElement> getResultMetadataMembers(String queryName, boolean preferResult, String dimensionName, String hierarchyName, String levelName, String searchString, int searchLimit) {
		IQuery query = getIQuery(queryName);
		CellSet cs = query.getCellset();
		List<SimpleCubeElement> members = new ArrayList<SimpleCubeElement>();
		Set<Level> levels = new HashSet<Level>();
		boolean search = StringUtils.isNotBlank(searchString);
		preferResult = (preferResult && !search);
		searchString = search ? searchString.toLowerCase() : null;

		if (cs != null && preferResult) {
			for (CellSetAxis axis : cs.getAxes()) {
				int posIndex = 0;
				for (Hierarchy h : axis.getAxisMetaData().getHierarchies()) {
					if (h.getUniqueName().equals(hierarchyName) || h.getName().equals(hierarchyName)) {
						log.debug("Found hierarchy in the result: " + hierarchyName);
						if (h.getLevels().size() == 1) {
							break;
						}
						Set<Member> mset = new HashSet<Member>();
						for (Position pos : axis.getPositions()) {
							Member m = pos.getMembers().get(posIndex);
							if (!m.getLevel().getLevelType().equals(Type.ALL)) {
								levels.add(m.getLevel());
							}
							if (m.getLevel().getUniqueName().equals(levelName) || m.getLevel().getName().equals(levelName)) {
								mset.add(m);
							}
						}

						members = ObjectUtil.convert2Simple(mset);
						Collections.sort(members, new SaikuUniqueNameComparator());

						break;
					}
					posIndex++;
				}
			}
			log.debug("Found members in the result: " + members.size());

		}
		if (cs == null || !preferResult || members.size() == 0 || levels.size() == 1) {
			members = olapDiscoverService.getLevelMembers(query.getSaikuCube(), dimensionName, hierarchyName, levelName, searchString, searchLimit);
		}
		return members;
	}

	public ResultSet explain(String queryName) {
		OlapStatement stmt = null;
		try {

			final OlapConnection con = olapDiscoverService.getNativeConnection(getQuery(queryName).getCube().getConnectionName());
			if (!con.isWrapperFor(RolapConnection.class))
				throw new IllegalArgumentException("Cannot only get explain plan for Mondrian connections");

			stmt = con.createStatement();
			String mdx = getMDXQuery(queryName);
			mdx = "EXPLAIN PLAN FOR \n" + mdx;
			ResultSet rs = stmt.executeQuery(mdx);
			return rs;

		} catch (Exception e) {
			throw new SaikuServiceException("Error EXPLAIN: " + queryName,e);
		} finally {
			try {
				if (stmt != null)  stmt.close();
			} catch (Exception e) {}
		}
	}


	public ResultSet drillthrough(String queryName, int maxrows, String returns) {
		OlapStatement stmt = null;
		try {
			final OlapConnection con = olapDiscoverService.getNativeConnection(getQuery(queryName).getCube().getConnectionName()); 
			stmt = con.createStatement();
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
			ResultSet rs = stmt.executeQuery(mdx);
			return rs;
		} catch (SQLException e) {
			throw new SaikuServiceException("Error DRILLTHROUGH: " + queryName,e);
		} finally {
			try {
				if (stmt != null)  stmt.close();
			} catch (Exception e) {}
		}
	}

	public ResultSet drillthrough(String queryName, List<Integer> cellPosition, Integer maxrows, String returns) {
		OlapStatement stmt = null;
		try {
			IQuery query = getIQuery(queryName);
			CellSet cs = query.getCellset();
			SaikuCube cube = getQuery(queryName).getCube();
			final OlapConnection con = olapDiscoverService.getNativeConnection(cube.getConnectionName()); 
			stmt = con.createStatement();

			SelectNode sn = (new DefaultMdxParserImpl().parseSelect(getMDXQuery(queryName)));
			String select = null;
			StringBuffer buf = new StringBuffer();
			if (sn.getWithList() != null && sn.getWithList().size() > 0) {
	            buf.append("WITH \n");
	            StringWriter sw = new StringWriter();
	            ParseTreeWriter ptw = new ParseTreeWriter(sw);
	            final PrintWriter pw = ptw.getPrintWriter();
	            for (ParseTreeNode with : sn.getWithList()) {
	                with.unparse(ptw);
	                pw.println();
	            }
	            buf.append(sw.toString());
			}
			
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
			ResultSet rs = stmt.executeQuery(select);
			return rs;
		} catch (Exception e) {
			throw new SaikuServiceException("Error DRILLTHROUGH: " + queryName,e);
		} finally {
			try {
				if (stmt != null)  stmt.close();
			} catch (Exception e) {}
		}

	}


	public byte[] exportDrillthroughCsv(String queryName, int maxrows) {
		OlapStatement stmt = null;
		try {
			final OlapConnection con = olapDiscoverService.getNativeConnection(getQuery(queryName).getCube().getConnectionName()); 
			stmt = con.createStatement();
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
		} finally {
			try {
				if (stmt != null)  stmt.close();
			} catch (Exception e) {}
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

	public IQuery swapAxes(String queryName) {
		IQuery query = getIQuery(queryName);
		if (QueryType.QM.equals(query.getType())) {
			query.swapAxes();
		}		
		return query;
	}
	
	public IQuery showGrandTotals(String queryName, String axisName, String functionName) {
		IQuery query = getIQuery(queryName);
		if ("not".equals(functionName)) {
			functionName = null;
		}
		query.setTotalFunction(axisName, functionName);
		return query;
	}

	public boolean includeChildren(String queryName, String dimensionName, String uniqueMemberName) {
		IQuery query = getIQuery(queryName);
		List<IdentifierSegment> memberList = IdentifierNode.parseIdentifier(uniqueMemberName).getSegmentList();
		QueryDimension dimension = query.getDimension(dimensionName);
		try {
			Selection sel = dimension.createSelection(Operator.CHILDREN, memberList);
			dimension.getInclusions().add(sel);
			return true;
		} catch (OlapException e) {
			throw new SaikuServiceException("Cannot include children query ("+queryName+") dimension (" + dimensionName + ") member ("+
					uniqueMemberName +")" ,e);
		}
	}

	public boolean removeChildren(String queryName, String dimensionName, String uniqueMemberName) {
		IQuery query = getIQuery(queryName);
		List<IdentifierSegment> memberList = IdentifierNode.parseIdentifier(uniqueMemberName).getSegmentList();
		QueryDimension dimension = query.getDimension(dimensionName);
		try {
			Selection sel = dimension.createSelection(Operator.CHILDREN, memberList);
			if (dimension.getInclusions().contains(sel)) {
				dimension.getInclusions().remove(sel);
			}
			return true;
		} catch (OlapException e) {
			throw new SaikuServiceException("Cannot remove children query ("+queryName+") dimension (" + dimensionName + ") member ("+
					uniqueMemberName +")" ,e);
		}
	}

	public boolean removeAllChildren(String queryName, String dimensionName) {
		IQuery query = getIQuery(queryName);
		QueryDimension dimension = query.getDimension(dimensionName);
		List<Selection> children = new ArrayList<Selection>();
		try {
			for (Selection sel : dimension.getInclusions()) {
				if (sel.getOperator().equals(Operator.CHILDREN)) {
					children.add(sel);
				}
			}
			dimension.getInclusions().removeAll(children);
			return true;
		} catch (Exception e) {
			throw new SaikuServiceException("Cannot remove all children  for query ("+queryName+") dimension (" + dimensionName + ")",e);
		}
	}

	public boolean includeMember(String queryName, String dimensionName, String uniqueMemberName, String selectionType, int memberposition) {
		String defaultTotalsFunction = "";
		return includeMember(queryName, dimensionName, uniqueMemberName, selectionType, defaultTotalsFunction, memberposition);
	}

	public boolean includeMember(String queryName, String dimensionName, String uniqueMemberName, String selectionType, String totalsFunction, int memberposition){
		IQuery query = getIQuery(queryName);


		List<IdentifierSegment> memberList = IdentifierNode.parseIdentifier(uniqueMemberName).getSegmentList();
		QueryDimension dimension = query.getDimension(dimensionName);
		final Selection.Operator selectionMode = Selection.Operator.valueOf(selectionType);
		try {
			removeAllChildren(queryName, dimensionName);
			Selection sel = dimension.createSelection(selectionMode, memberList);
			if (dimension.getInclusions().contains(sel)) {
				dimension.getInclusions().remove(sel);
			}
			if (memberposition < 0) {
				memberposition = dimension.getInclusions().size();
			}
			dimension.getInclusions().add(memberposition, sel);
			query.setTotalFunction(((Member) sel.getRootElement()).getLevel().getUniqueName(), totalsFunction);
			return true;
		} catch (OlapException e) {
			throw new SaikuServiceException("Cannot include member query ("+queryName+") dimension (" + dimensionName + ") member ("+
					uniqueMemberName+") operator (" + selectionType + ") position " + memberposition,e);
		}
	}

	public boolean removeMember(String queryName, String dimensionName, String uniqueMemberName, String selectionType) throws SaikuServiceException{
		IQuery query = getIQuery(queryName);
		removeAllChildren(queryName, dimensionName);
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
		String defaultTotalsFunction = "";
		return includeLevel(queryName, dimensionName, uniqueHierarchyName, uniqueLevelName, defaultTotalsFunction);
	}

	public boolean includeLevel(String queryName, String dimensionName, String uniqueHierarchyName, String uniqueLevelName, String totalsFunction) {
		IQuery query = getIQuery(queryName);
		removeAllChildren(queryName, dimensionName);
		
		QueryDimension dimension = query.getDimension(dimensionName);
		for (Hierarchy hierarchy : dimension.getDimension().getHierarchies()) {
			if (hierarchy.getUniqueName().equals(uniqueHierarchyName)) {
				for (Level level : hierarchy.getLevels()) {
					if (level.getUniqueName().equals(uniqueLevelName)) {
						Selection sel = dimension.createSelection(level);
						if (!dimension.getInclusions().contains(sel)) {
							dimension.include(level);
						}
						query.setTotalFunction(uniqueLevelName, totalsFunction);
						return true;
					}
				}
			}
		}
		return false;
	}

	public boolean removeLevel(String queryName, String dimensionName, String uniqueHierarchyName, String uniqueLevelName) {
		IQuery query = getIQuery(queryName);
		removeAllChildren(queryName, dimensionName);
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
							dimension.getInclusions().removeAll(removals);
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
					dimsel.add(ObjectUtil.convertDimensionSelection(dim, query));
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
					return ObjectUtil.convertDimensionSelection(dim, query);
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

	public IQuery clearAxis(String queryName, String axisName) {
		try {
			IQuery query = getIQuery(queryName);
			query.clearAxis(axisName);
			return query;
		} catch (SaikuOlapException e) {
			throw new SaikuServiceException("Cannot clear for query: " + queryName + " axis: " + axisName,e);
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

	public void limitAxis(String queryName, String axisName, String limitFunction, String n, String sortLiteral) {
		IQuery query = getIQuery(queryName);
		if (Axis.Standard.valueOf(axisName) != null) {
			QueryAxis qAxis = query.getAxis(Axis.Standard.valueOf(axisName));
			LimitFunction lf = LimitFunction.valueOf(limitFunction);
			BigDecimal bn = new BigDecimal(n);
			qAxis.limit(lf, bn, sortLiteral);
		}
	}

	public void clearLimit(String queryName, String axisName) {
		IQuery query = getIQuery(queryName);
		if (Axis.Standard.valueOf(axisName) != null) {
			QueryAxis qAxis = query.getAxis(Axis.Standard.valueOf(axisName));
			qAxis.clearLimitFunction();
		}
	}

	public void filterAxis(String queryName, String axisName, String filterCondition) {
		IQuery query = getIQuery(queryName);
		if (Axis.Standard.valueOf(axisName) != null) {
			QueryAxis qAxis = query.getAxis(Axis.Standard.valueOf(axisName));
			qAxis.filter(filterCondition);
		}
	}

	public void clearFilter(String queryName, String axisName) {
		IQuery query = getIQuery(queryName);
		if (Axis.Standard.valueOf(axisName) != null) {
			QueryAxis qAxis = query.getAxis(Axis.Standard.valueOf(axisName));
			qAxis.clearFilter();
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
		Properties props = query.getProperties();
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
		return getExport(queryName,type,new FlattenedCellSetFormatter());
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

		return getExport(queryName, type, new FlattenedCellSetFormatter());
	}

	public byte[] getExport(String queryName, String type, ICellSetFormatter formatter) {
		if (type != null) {
			IQuery query = getIQuery(queryName);
			CellSet rs = query.getCellset();
			List<SaikuDimensionSelection> filters = new ArrayList<SaikuDimensionSelection>();

			if (query.getType().equals(QueryType.QM)) {
				filters = getAxisSelection(queryName, "FILTER");
			}
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
			CellSet cs = query.getCellset();
			List<SaikuTuple> tuples = new ArrayList<SaikuTuple>();
			List<SimpleCubeElement> dimensions = new ArrayList<SimpleCubeElement>();
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
						SimpleCubeElement sd = 
								new SimpleCubeElement(
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
	

	public IQuery zoomIn(String queryName, List<List<Integer>> realPositions) {
		try {
			IQuery query = getIQuery(queryName);
			CellSet cs = query.getCellset();
			if (cs == null) {
				throw new SaikuServiceException("Cannot zoom in if last cellset is null");
			}
			if (realPositions == null || realPositions.size() == 0) {
				throw new SaikuServiceException("Cannot zoom in if zoom in position is empty");
			}
			
			Map<Dimension, Set<Member>> memberSelection = new HashMap<Dimension, Set<Member>>();
			for (List<Integer> position : realPositions) {
				for (int k = 0; k < position.size(); k++) {
					Position p = cs.getAxes().get(k).getPositions().get(position.get(k));
					List<Member> members = p.getMembers();
					for (Member m : members) {
						Dimension d = m.getDimension();
						if (!memberSelection.containsKey(d)) {
							Set<Member> mset = new HashSet<Member>();
							memberSelection.put(d, mset);
						}
						memberSelection.get(d).add(m);
					}
				}
			}
			
			for(Dimension d : memberSelection.keySet()) {
				QueryDimension a = query.getDimension(d.getName());
				a.clearInclusions();
				for (Member m : memberSelection.get(d)) {
					a.include(m);
				}
			}
			
			return query;
		} catch (Exception e) {
			throw new SaikuServiceException("Error zoom in on query: " + queryName,e);
		}
		
	}

	public SaikuFilter getFilter(String queryName, String filtername, String dimensionName, String hierarchyName, String levelName) {
		try {


			IQuery query = getIQuery(queryName);
			CellSet cs = query.getCellset();
			if (cs == null) {
				throw new SaikuServiceException("Cannot get filter of result if last cellset is null");
			}

			CellDataSet result = OlapResultSetUtil.cellSet2Matrix(cs, query.getFormatter());

			List<SimpleCubeElement> members = new ArrayList<SimpleCubeElement>();
			Set<MetadataElement> mset = new HashSet<MetadataElement>();

			Cube cube = query.getCube();
			Hierarchy h = cube.getHierarchies().get(hierarchyName);
			if (h == null) {
				throw new Exception("Cannot find hierarchy in cube " + cube.getName() + " with name " + hierarchyName);
			}
			Dimension d = h.getDimension();
			Level l = h.getLevels().get(levelName);
			if (l == null) {
				throw new Exception("Cannot find level in hierarchy " + h.getName() + " with name " + levelName);
			}
			SimpleCubeElement hierarchy = new SimpleCubeElement(h.getName(),  h.getUniqueName(),  h.getCaption());
			SimpleCubeElement dimension = new SimpleCubeElement(d.getName(), d.getUniqueName(), d.getCaption());

			Long start = (new Date()).getTime();

			// try headers first
			AbstractBaseCell[][] headers = result.getCellSetHeaders();
			if (headers != null && headers.length > 0 && headers[0].length > 0) {
				for (int i=0; i < headers.length; i++) {
					for (int k = 0; k < headers[0].length; k++) {
						if (headers[i][k] == null) {
							continue;
						}
						MemberCell mc = (MemberCell) headers[i][k];
						if (mc.getUniqueName() != null) {
							if (mc.getHierarchy().equals(hierarchy.getUniqueName()) && mc.getLevel().equals(l.getUniqueName())) {
								String mu = mc.getUniqueName();
								List<IdentifierSegment> memberList = IdentifierNode.parseIdentifier(mu).getSegmentList();
								Member m = cube.lookupMember(memberList);
								mset.add(m);
							}
						}
					}
				}
			}
			Long header = (new Date()).getTime();
			
			if (mset.size() == 0) {
				// try body next
				AbstractBaseCell[][] body = result.getCellSetBody();
				if (body != null && body.length > 0 && body[0].length > 0) {
					for (int i=0; i < body.length; i++) {
						for (int k = 0; k < body[0].length; k++) {
							if (body[i][k] == null) {
								continue;
							}
							AbstractBaseCell ac = body[i][k];
							if (ac instanceof DataCell) {
								break;
							}
							if (ac instanceof MemberCell) {
								MemberCell mc = (MemberCell) body[i][k];
								if (mc.getUniqueName() != null) {
									if (mc.getHierarchy().equals(hierarchy.getUniqueName()) && mc.getLevel().equals(l.getUniqueName())) {
										String mu = mc.getUniqueName();
										List<IdentifierSegment> memberList = IdentifierNode.parseIdentifier(mu).getSegmentList();
										Member m = cube.lookupMember(memberList);
										mset.add(m);
									}
								}
							}
						}
					}
				}
			}
			Long body = (new Date()).getTime();


			// fallback - check inclusions (probably they are only on filter)
			if (mset.size() == 0) {
//				List<CellSetAxis> axes = new ArrayList<CellSetAxis>();
//				axes.addAll(cs.getAxes());
//				axes.add(cs.getFilterAxis());
//				for (CellSetAxis axis : axes) {
//					int posIndex = 0;
//					for (Hierarchy he : axis.getAxisMetaData().getHierarchies()) {
//						if (he.getName().equals(hierarchyName)) {
//							if (hierarchy == null) {
//								hierarchy = new SimpleCubeElement(he.getName(),  he.getUniqueName(),  he.getCaption());
//								dimension = new SimpleCubeElement(d.getName(), d.getUniqueName(), d.getCaption());
//							}
//							if (he.getLevels().size() == 1) {
//								break;
//							}
//
//							for (Position pos : axis.getPositions()) {
//								Member m = pos.getMembers().get(posIndex);
//								if (m.getLevel().getName().equals(levelName)) {
//									mset.add(m);
//								}
//							}
//							break;
//						}
//						posIndex++;
//					}
//				}
				if (mset.size() == 0) {
					QueryDimension qd = query.getDimension(dimensionName);
					if (qd != null && qd.getAxis().getLocation() != null) {
						for (Selection sel : qd.getInclusions()) {
							if ((sel.getRootElement() instanceof Member)) {
								Member m = ((Member) sel.getRootElement());
								if (m.getLevel().getName().equals(levelName)) {
									mset.add(m);
								}
							}
						}
					}
				}
			}
			Long end = (new Date()).getTime();
			
//			System.out.println("Header Time: " + new Double(header - start).intValue());
//			System.out.println("Body   Time: " + new Double(body - header).intValue());
//			System.out.println("Rest   Time: " + new Double(end- body).intValue());
			members = ObjectUtil.convert2Simple(mset);
			Collections.sort(members, new SaikuUniqueNameComparator());
			log.debug("Create Filters: Found members in the result or query: " + members.size());

			return new SaikuFilter(filtername, null, dimension, hierarchy, members);
		} catch (Exception e) {
			throw new SaikuServiceException("Error getFilter:" + filtername + " for query: " + queryName,e);
		}
	}

	public Map<String, SaikuFilter> getValidFilters(String queryName, Map<String, SaikuFilter> allFilters) {
		IQuery query = getIQuery(queryName);
		Cube c = query.getCube();
		Map<String, SaikuFilter> filteredMap = new HashMap<String, SaikuFilter>(); 
		for (SaikuFilter sf : allFilters.values()) {
			if (StringUtils.isBlank(sf.getName()) || sf.getDimension() == null)
				continue;

			String dimensionName = sf.getDimension().getName();
			String hierarchyName = sf.getHierarchy().getName();
			boolean hasDimension = c.getDimensions().indexOfName(dimensionName) >= 0;
			boolean hasHierarchy = c.getHierarchies().indexOfName(hierarchyName) >= 0;
			if (hasDimension || hasHierarchy) {
				filteredMap.put(sf.getName(), sf);
			}
		}
		return filteredMap;
	}

	public SaikuQuery applyFilter(String queryname, SaikuFilter filter) throws Exception {
		IQuery query = getIQuery(queryname);
		if (filter != null && filter.getName() != null && filter.getDimension() != null && filter.getMembers() != null) {
			query.setFilter(filter);
			QueryDimension qDim = query.getDimension(filter.getDimension().getName());

			if (qDim != null) {
				qDim.clearInclusions();
				query.moveDimension(qDim, Axis.FILTER);
				for (SimpleCubeElement member : filter.getMembers()) {
					List<IdentifierSegment> memberList = IdentifierNode.parseIdentifier(member.getUniqueName()).getSegmentList();
					qDim.include(memberList);
				}
			}
		}
		return ObjectUtil.convert(query);
	}

	public SaikuQuery removeFilter(String queryname) {
		IQuery query = getIQuery(queryname);
		if (query != null && query.getFilter() != null) {
			SaikuFilter filter = query.getFilter();
			QueryDimension qDim = query.getDimension(filter.getDimension().getName());
			if (qDim != null) {
				qDim.clearInclusions();
				query.moveDimension(qDim, null);
			}
			query.removeFilter();
		}
		return ObjectUtil.convert(query);
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
		if (queries.containsKey(queryName)) {
			IQuery q = queries.remove(queryName);
			try {
				q.cancel();
			} catch (Exception e) {}
			q = null;
		}
	}

	private IQuery getIQuery(String queryName) {
		if (queries.containsKey(queryName)) {
			return  queries.get(queryName);
		}
		throw new SaikuServiceException("No query found using name: " + queryName);
	}

	private Map<String, IQuery> getIQueryMap() {
		return queries;
	}
}
