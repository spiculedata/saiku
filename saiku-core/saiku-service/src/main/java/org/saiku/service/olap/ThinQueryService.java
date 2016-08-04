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

import org.apache.commons.lang.StringUtils;

import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuHierarchy;
import org.saiku.olap.dto.SaikuLevel;
import org.saiku.olap.dto.SaikuMember;
import org.saiku.olap.dto.SimpleCubeElement;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query.IQuery;
import org.saiku.olap.query.IQuery.QueryType;
import org.saiku.olap.query.OlapQuery;
import org.saiku.olap.query.QueryDeserializer;
import org.saiku.olap.query2.ThinAxis;
import org.saiku.olap.query2.ThinCalculatedMember;
import org.saiku.olap.query2.ThinHierarchy;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinMember;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.olap.query2.ThinQueryModel.AxisLocation;
import org.saiku.olap.query2.util.Fat;
import org.saiku.olap.query2.util.Thin;
import org.saiku.olap.util.ObjectUtil;
import org.saiku.olap.util.OlapResultSetUtil;
import org.saiku.olap.util.QueryConverter;
import org.saiku.olap.util.SaikuProperties;
import org.saiku.olap.util.SaikuUniqueNameComparator;
import org.saiku.olap.util.formatter.CellSetFormatterFactory;
import org.saiku.olap.util.formatter.FlattenedCellSetFormatter;
import org.saiku.olap.util.formatter.ICellSetFormatter;
import org.saiku.query.Query;
import org.saiku.query.QueryDetails;
import org.saiku.query.QueryHierarchy;
import org.saiku.query.QueryLevel;
import org.saiku.query.util.QueryUtil;
import org.saiku.service.olap.drillthrough.DimensionResultInfo;
import org.saiku.service.olap.drillthrough.DrillThroughResult;
import org.saiku.service.olap.drillthrough.DrillthroughUtils;
import org.saiku.service.olap.drillthrough.MeasureResultInfo;
import org.saiku.service.olap.drillthrough.ResultInfo;
import org.saiku.service.olap.totals.AxisInfo;
import org.saiku.service.olap.totals.TotalNode;
import org.saiku.service.olap.totals.TotalsListsBuilder;
import org.saiku.service.olap.totals.aggregators.TotalAggregator;
import org.saiku.service.util.KeyValue;
import org.saiku.service.util.QueryContext;
import org.saiku.service.util.QueryContext.ObjectKey;
import org.saiku.service.util.QueryContext.Type;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.service.util.export.CsvExporter;
import org.saiku.service.util.export.ExcelExporter;

import org.olap4j.Axis;
import org.olap4j.CellSet;
import org.olap4j.CellSetAxis;
import org.olap4j.OlapConnection;
import org.olap4j.OlapStatement;
import org.olap4j.Position;
import org.olap4j.mdx.ParseTreeNode;
import org.olap4j.mdx.ParseTreeWriter;
import org.olap4j.mdx.SelectNode;
import org.olap4j.mdx.parser.impl.DefaultMdxParserImpl;
import org.olap4j.metadata.Cube;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Measure;
import org.olap4j.metadata.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import mondrian.olap4j.SaikuMondrianHelper;

public class ThinQueryService implements Serializable {

    /**
     * Unique serialization UID
     */
    private static final long serialVersionUID = -7615296596528274904L;

    private static final Logger log = LoggerFactory.getLogger(ThinQueryService.class);

    private static final AtomicLong ID_GENERATOR = new AtomicLong();

    private OlapDiscoverService olapDiscoverService;

    private CellSetFormatterFactory cff = new CellSetFormatterFactory();

    private final Map<String, QueryContext> context = new HashMap<>();

    public void setOlapDiscoverService(OlapDiscoverService os) {
        this.olapDiscoverService = os;
    }

    public void setCellSetFormatterFactory(CellSetFormatterFactory cff) {
        this.cff = cff;
    }


    public ThinQuery createQuery(ThinQuery tq) {
        if (StringUtils.isBlank(tq.getName())) {
            tq.setName(UUID.randomUUID().toString());
        }
        Map<String, Object> cubeProperties = olapDiscoverService.getProperties(tq.getCube());
        tq.getProperties().putAll(cubeProperties);
        if (!context.containsKey(tq.getName())) {
            //			Cube cub = olapDiscoverService.getNativeCube(tq.getCube());
            //			Query query = new Query(tq.getName(), cub);
            //			tq = Thin.convert(query, tq.getCube());
            QueryContext qt = new QueryContext(Type.OLAP, tq);
            qt.store(ObjectKey.QUERY, tq);
            this.context.put(tq.getName(), qt);
        }
        return tq;
    }

    public QueryContext getContext(String name) {
        return this.context.get(name);
    }

    @Deprecated
    public ThinQuery createEmpty(String name, SaikuCube cube) {
        try {
            Cube cub = olapDiscoverService.getNativeCube(cube);
            Query query = new Query(name, cub);
            return Thin.convert(query, cube);

        } catch (Exception e) {
            log.error("Cannot create new query for cube :" + cube, e);
        }
        return null;

    }


    CellSet executeInternalQuery(ThinQuery query) throws Exception {
        String runId = "RUN#:" + ID_GENERATOR.getAndIncrement();
        QueryContext queryContext = context.get(query.getName());

        if (queryContext == null) {
            queryContext = new QueryContext(Type.OLAP, query);
            this.context.put(query.getName(), queryContext);
        }

        OlapConnection con = olapDiscoverService.getNativeConnection(query.getCube().getConnection());
        if (StringUtils.isNotBlank(query.getCube().getCatalog())) {
            con.setCatalog(query.getCube().getCatalog());
        }

        if (queryContext.contains(ObjectKey.STATEMENT)) {
            Statement s = queryContext.getStatement();
            s.cancel();
            s.close();
            s = null;
            queryContext.remove(ObjectKey.STATEMENT);
        }

        OlapStatement stmt = con.createStatement();
        queryContext.store(ObjectKey.STATEMENT, stmt);

        query = updateQuery(query);

        try {
            String mdx = query.getParameterResolvedMdx();
            log.info(runId + "\tType:" + query.getType() + ":\n" + mdx);

            CellSet cs = stmt.executeOlapQuery(mdx);
            queryContext.store(ObjectKey.RESULT, cs);
            if (query != null) {
                queryContext.store(ObjectKey.QUERY, query);
            }
            return cs;
        } finally {
            stmt.close();
            queryContext.remove(ObjectKey.STATEMENT);
        }
    }

    public CellDataSet execute(ThinQuery tq) {
        if (tq.getProperties().containsKey("saiku.olap.result.formatter")) {
            return execute(tq, tq.getProperties().get("saiku.olap.result.formatter").toString());
        }
        return execute(tq, "");
    }

    public CellDataSet execute(ThinQuery tq, String formatter) {
        String formatterName = formatter == null ? "" : formatter.toLowerCase();
        ICellSetFormatter cf = cff.forName(formatterName);
        return execute(tq, cf);
    }

    public CellDataSet getFormattedResult(String query, String format) throws Exception {
        QueryContext qc = getContext(query);
        ThinQuery tq = qc.getOlapQuery();
        CellSet cs = qc.getOlapResult();
        String formatterName = (StringUtils.isBlank(format) ? "" : format.toLowerCase());
        ICellSetFormatter cf = cff.forName(formatterName);
        CellDataSet result = OlapResultSetUtil.cellSet2Matrix(cs, cf);

        if (ThinQuery.Type.QUERYMODEL.equals(tq.getType()) && cf instanceof FlattenedCellSetFormatter && tq.hasAggregators()) {
            calculateTotals(tq, result, cs, cf);
        }
        return result;
    }

    private CellDataSet execute(ThinQuery tq, ICellSetFormatter formatter) {
        try {

            Long start = (new Date()).getTime();
            log.debug("Query Start");
            CellSet cellSet = executeInternalQuery(tq);
            log.debug("Query End");
            String runId = "RUN#:" + ID_GENERATOR.get();
            Long exec = (new Date()).getTime();

            CellDataSet result = OlapResultSetUtil.cellSet2Matrix(cellSet, formatter);
            Long format = (new Date()).getTime();

            if (ThinQuery.Type.QUERYMODEL.equals(tq.getType()) && formatter instanceof FlattenedCellSetFormatter && tq.hasAggregators()) {
                calculateTotals(tq, result, cellSet, formatter);
            }
            Long totals = (new Date()).getTime();
            log.info(runId + "\tSize: " + result.getWidth() + "/" + result.getHeight() + "\tExecute:\t" + (exec - start)
                    + "ms\tFormat:\t" + (format - exec) + "ms\tTotals:\t" + (totals - format) + "ms\t Total: " + (totals - start) + "ms");

            result.setRuntime(new Double(format - start).intValue());
            return result;
        } catch (Exception | Error e) {
            throw new SaikuServiceException("Can't execute query: " + tq.getName(), e);
        }
    }

    public void cancel(String name) throws SQLException {
        if (context.containsKey(name)) {
            QueryContext queryContext = context.get(name);
            if (queryContext.contains(ObjectKey.STATEMENT)) {
                Statement stmt = queryContext.getStatement();
                if (stmt != null && !stmt.isClosed()) {
                    stmt.cancel();
                    stmt.close();
                }
                stmt = null;
                queryContext.remove(ObjectKey.STATEMENT);
            }
        }
    }

    private void getEnabledCMembers(ThinQueryModel qm, ThinQueryModel queryModel) {
        int i = 0;
        for (Map.Entry<AxisLocation, ThinAxis> entry : qm.getAxes().entrySet()) {
            ThinAxis v = entry.getValue();
            for (ThinHierarchy h : v.getHierarchies()) {
                for (Map.Entry<String, ThinLevel> entry1 : h.getLevels().entrySet()) {
                    ThinLevel v2 = entry1.getValue();
                    if (v2.getSelection() != null) {
                        for (ThinMember m : v2.getSelection().getMembers()) {
                            if (m.getType()!=null && m.getType().equals("calculatedmember")) {
                                Map<AxisLocation, ThinAxis> ax = queryModel.getAxes();
                                ThinAxis sax = ax.get(entry.getKey());
                                List<ThinHierarchy> h2 = sax.getHierarchies();
                                Map<String, ThinLevel> l = h2.get(i).getLevels();
                                ThinLevel l2 = l.get(entry1.getKey());
                                l2.getSelection().getMembers().add(m);

                                queryModel.getAxes().get(entry.getKey()).getHierarchies().get(i).getLevels().get(entry1
                                        .getKey()).getSelection().getMembers().add(m);

                            }
                        }
                    }
                }
                i++;
            }
        }
    }

    public ThinQuery updateQuery(ThinQuery old) throws Exception {
        if (ThinQuery.Type.QUERYMODEL.equals(old.getType())) {
            Cube cub = olapDiscoverService.getNativeCube(old.getCube());
            Query q = Fat.convert(old, cub);
            List<ThinCalculatedMember> cms = old.getQueryModel().getCalculatedMembers();
            ThinQuery tqAfter = Thin.convert(q, old.getCube());
            tqAfter.getQueryModel().setCalculatedMembers(cms);
            getEnabledCMembers(old.getQueryModel(), tqAfter.getQueryModel());
            old.setQueryModel(tqAfter.getQueryModel());
            old.setMdx(tqAfter.getMdx());
        }
        if (context.containsKey(old.getName())) {
            QueryContext qc = context.get(old.getName());
            qc.store(ObjectKey.QUERY, old);
        }
        String mdx = old.getMdx();
        List<String> params = QueryUtil.parseParameters(mdx);
        old.addParameters(params);

        Map<String, Object> cubeProperties = olapDiscoverService.getProperties(old.getCube());
        old.getProperties().putAll(cubeProperties);

      /**
       * TODO NASTY HACK REMOVE IN NEXT RELEASE
       */
        old = removeDupSelections(old);
        return old;
    }

    private ThinQuery removeDupSelections(ThinQuery old) {

        Map<AxisLocation, ThinAxis> map = old.getQueryModel().getAxes();
        for (Map.Entry<AxisLocation, ThinAxis> entry : map.entrySet())
        {
            for(ThinHierarchy h : entry.getValue().getHierarchies()){
                Map<String, ThinLevel> map2= h.getLevels();
                for(Map.Entry<String, ThinLevel> levelentry : map2.entrySet())
                {
                    List<ThinMember> members = levelentry.getValue().getSelection().getMembers();
                    SortedSet<ThinMember> deduped = new TreeSet<>(new Comparator<ThinMember>() {
                        @Override
                        public int compare(ThinMember arg0, ThinMember arg1) {
                            return arg0.getUniqueName().compareTo(arg1.getUniqueName());
                        }
                    });

                    for(ThinMember tm: members){
                        deduped.add(tm);
                    }

                    members.clear();
                    members.addAll(deduped);

                    levelentry.getValue().getSelection().setMembers(members);
                }
            }
        }
        return old;
    }

    public void deleteQuery(String queryName) {
        try {
            if (context.containsKey(queryName)) {
                QueryContext qc = context.remove(queryName);
                qc.destroy();
            }
        } catch (Exception e) {
            throw new SaikuServiceException(e);
        }
    }

    public byte[] getExport(String queryName, String type) {
        return getExport(queryName, type, new FlattenedCellSetFormatter());
    }

    public byte[] getExport(String queryName, String type, String formatter) {
        String formatterName = formatter == null ? "" : formatter.toLowerCase();
        ICellSetFormatter cf = cff.forName(formatterName);
        return getExport(queryName, type, cf);
    }

    private byte[] getExport(String queryName, String type, ICellSetFormatter formatter) {
        if (StringUtils.isNotBlank(type) && context.containsKey(queryName)) {
            //Query Context
            QueryContext qc = context.get(queryName);

            //Query
            ThinQuery tq = qc.getOlapQuery();

            //Query exec result
            CellSet rs = qc.getOlapResult();

            //Query result as table
            CellDataSet table = OlapResultSetUtil.cellSet2Matrix(rs, formatter);

            //Calculate totals and sub totals
            if (ThinQuery.Type.QUERYMODEL.equals(tq.getType()) && formatter instanceof FlattenedCellSetFormatter && tq.hasAggregators()) {
                try {
                    calculateTotals(tq, table, rs, formatter);
                } catch (Exception e) {
                    //Ignore totals calculation errors
                    log.error(e.getMessage(), e);
                }
            }

            List<ThinHierarchy> filterHierarchies = null;
            if (ThinQuery.Type.QUERYMODEL.equals(tq.getType())) {
                filterHierarchies = tq.getQueryModel().getAxes().get(AxisLocation.FILTER).getHierarchies();
            }
            if (type.toLowerCase().equals("xls")) {
                return ExcelExporter.exportExcel(table, formatter, filterHierarchies);
            }
            if (type.toLowerCase().equals("csv")) {
                return CsvExporter.exportCsv(rs, SaikuProperties.webExportCsvDelimiter, SaikuProperties.webExportCsvTextEscape, formatter);
            }
        }
        return new byte[0];
    }

    public ResultSet drillthrough(String queryName, int maxrows, String returns) {
        OlapStatement stmt = null;
        try {

            ThinQuery query = context.get(queryName).getOlapQuery();
            final OlapConnection con = olapDiscoverService.getNativeConnection(query.getCube().getConnection());
            stmt = con.createStatement();
            String mdx = query.getMdx();
            if (maxrows > 0) {
                mdx = "DRILLTHROUGH MAXROWS " + maxrows + " " + mdx;
            } else {
                mdx = "DRILLTHROUGH " + mdx;
            }
            if (StringUtils.isNotBlank(returns)) {
                mdx += "\r\n RETURN " + returns;
            }
            return stmt.executeQuery(mdx);
        } catch (SQLException e) {
            throw new SaikuServiceException("Error DRILLTHROUGH: " + queryName, e);
        } finally {
            try {
                if (stmt != null) stmt.close();
            } catch (Exception e) {
            }
        }
    }

    public boolean isMdxDrillthrough(ThinQuery query) {
        try {
            if (ThinQuery.Type.MDX.equals(query.getType())) {
                SaikuCube cube = query.getCube();
                final OlapConnection con = olapDiscoverService.getNativeConnection(cube.getConnection());
                return SaikuMondrianHelper.isMondrianDrillthrough(con, query.getMdx());
            }
        } catch (Exception | Error e) {
            log.warn("Error checking for DRILLTHROUGH: " + query.getName() + " DRILLTHROUGH MDX:" + query.getMdx(), e);
        }
        return false;

    }

    public ResultSet drillthrough(ThinQuery query) {
        OlapStatement stmt = null;
        try {
            SaikuCube cube = query.getCube();
            final OlapConnection con = olapDiscoverService.getNativeConnection(cube.getConnection());
            stmt = con.createStatement();
            return stmt.executeQuery(query.getMdx());
        } catch (SQLException e) {
            throw new SaikuServiceException("Error DRILLTHROUGH: " + query.getMdx() + " DRILLTHROUGH MDX:" + query.getMdx(), e);
        } finally {
            try {
                if (stmt != null) stmt.close();
            } catch (Exception e) {
            }
        }

    }

    public DrillThroughResult drillthroughWithCaptions(String queryName, List<Integer> cellPosition, Integer maxrows, String returns) {
    	QueryContext queryContext = context.get(queryName);
    	SaikuCube saikuCube = queryContext.getOlapQuery().getCube();
    	List<SaikuMember> measures = olapDiscoverService.getMeasures(saikuCube);
    	CellSet cs = queryContext.getOlapResult();
    	ResultSet drillthrough = drillthrough(queryName, cellPosition, maxrows, returns);
    	
    	int width;
		try {
			width = drillthrough.getMetaData().getColumnCount();
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
    	String[] simpleHeader = new String[width];
    	AbstractBaseCell[][] cellHeaders = null;
    	if (StringUtils.isBlank(returns)) {    		
    		CellDataSet result = OlapResultSetUtil.cellSet2Matrix(cs, cff.forName(StringUtils.EMPTY));
    		cellHeaders = result.getCellSetHeaders();
    	} else {
    		List<ResultInfo> results = DrillthroughUtils.extractResultInfo(returns);
    		for (int i = 0; i < results.size(); i++) {
				final ResultInfo ri = results.get(i);
				if (ri instanceof MeasureResultInfo) {
					simpleHeader[i] = DrillthroughUtils.findMeasure(measures, ((MeasureResultInfo) ri).getName()).getCaption();
    			} else if (ri instanceof DimensionResultInfo) {
    				final DimensionResultInfo dri = (DimensionResultInfo) ri;
    				List<SaikuHierarchy> hierarchies = olapDiscoverService
    						.getDimension(saikuCube, dri.getDimension()).getHierarchies();
    				SaikuHierarchy hierarchy = DrillthroughUtils.findHierarchy(hierarchies, dri.getHierarchy());
    				SaikuLevel level = DrillthroughUtils.findLevel(hierarchy.getLevels(), dri.getLevel());
    				simpleHeader[i] = level.getCaption();
    			}
			}
    	}
    	return new DrillThroughResult(drillthrough, simpleHeader, cellHeaders);
    }

    public ResultSet drillthrough(String queryName, List<Integer> cellPosition, Integer maxrows, String returns) {
        OlapStatement stmt = null;
        try {
            QueryContext queryContext = context.get(queryName);
            ThinQuery query = queryContext.getOlapQuery();
            CellSet cs = queryContext.getOlapResult();
            SaikuCube cube = query.getCube();
            final OlapConnection con = olapDiscoverService.getNativeConnection(cube.getConnection());
            stmt = con.createStatement();
            SelectNode sn = (new DefaultMdxParserImpl().parseSelect(query.getMdx()));
            String select = null;
            StringBuilder buf = new StringBuilder();
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
            buf.append("FROM [").append(cube.getName()).append("]\r\n");
            final Writer writer = new StringWriter();
            sn.getFilterAxis().unparse(new ParseTreeWriter(new PrintWriter(writer)));
            if (StringUtils.isNotBlank(writer.toString())) {
                buf.append("WHERE ").append(writer.toString());
            }
            select = buf.toString();
            if (maxrows > 0) {
                select = "DRILLTHROUGH MAXROWS " + maxrows + " " + select + "\r\n";
            } else {
                select = "DRILLTHROUGH " + select + "\r\n";
            }
            if (StringUtils.isNotBlank(returns)) {
                select += "\r\n RETURN " + returns;
            }
            log.debug("Drill Through for query (" + queryName + ") : \r\n" + select);
            return stmt.executeQuery(select);
        } catch (Exception e) {
            throw new SaikuServiceException("Error DRILLTHROUGH: " + queryName, e);
        } finally {
            try {
                if (stmt != null) stmt.close();
            } catch (Exception e) {
            }
        }
    }


    public byte[] exportDrillthroughCsv(String queryName, int maxrows) {
        OlapStatement stmt = null;
        try {
            QueryContext queryContext = context.get(queryName);
            ThinQuery query = queryContext.getOlapQuery();
            final OlapConnection con = olapDiscoverService.getNativeConnection(query.getCube().getConnection());
            stmt = con.createStatement();
            String mdx = query.getMdx();
            if (maxrows > 0) {
                mdx = "DRILLTHROUGH MAXROWS " + maxrows + " " + mdx;
            } else {
                mdx = "DRILLTHROUGH " + mdx;
            }

            ResultSet rs = stmt.executeQuery(mdx);
            return CsvExporter.exportCsv(rs);
        } catch (SQLException e) {
            throw new SaikuServiceException("Error DRILLTHROUGH: " + queryName, e);
        } finally {
            try {
                if (stmt != null) stmt.close();
            } catch (Exception e) {
            }
        }

    }

    public byte[] exportResultSetCsv(ResultSet rs) {
        return CsvExporter.exportCsv(rs);
    }

    public byte[] exportResultSetCsv(ResultSet rs, String delimiter, String enclosing, boolean printHeader, List<KeyValue<String, String>> additionalColumns) {
        return CsvExporter.exportCsv(rs, delimiter, enclosing, printHeader, additionalColumns);
    }


    public List<SimpleCubeElement> getResultMetadataMembers(
            String queryName,
            boolean preferResult,
            String hierarchyName,
            String levelName,
            String searchString,
            int searchLimit) {

        if (context.containsKey(queryName)) {
            CellSet cs = context.get(queryName).getOlapResult();
            List<SimpleCubeElement> members = new ArrayList<>();
            Set<Level> levels = new HashSet<>();
            boolean search = StringUtils.isNotBlank(searchString);
            preferResult = (preferResult && !search);
            searchString = search ? searchString.toLowerCase() : null;

            if (cs != null && preferResult) {
                for (CellSetAxis axis : cs.getAxes()) {
                    int posIndex = 0;
                    for (Hierarchy h : axis.getAxisMetaData().getHierarchies()) {
                        if (h != null && (h.getUniqueName().equals(hierarchyName) || h.getName().equals(hierarchyName))) {
                            log.debug("Found hierarchy in the result: " + hierarchyName);
                            if (h.getLevels().size() == 1) {
                                break;
                            }
                            Set<Member> mset = new HashSet<>();
                            for (Position pos : axis.getPositions()) {
                                Member m = pos.getMembers().get(posIndex);
                                if (!m.getLevel().getLevelType().equals(org.olap4j.metadata.Level.Type.ALL)) {
                                    levels.add(m.getLevel());
                                }
                                if (m.getLevel().getUniqueName().equals(levelName) || m.getLevel().getName().equals(levelName)) {
                                    Member.Type t = m.getMemberType();
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
                members = olapDiscoverService.getLevelMembers(context.get(queryName).getOlapQuery().getCube(), hierarchyName, levelName, searchString, searchLimit);
            }
            return members;
        }
        return null;
    }

    private void calculateTotals(ThinQuery tq, CellDataSet result, CellSet cellSet, ICellSetFormatter formatter) throws Exception {
        if (ThinQuery.Type.QUERYMODEL.equals(tq.getType()) && formatter instanceof FlattenedCellSetFormatter) {
            Cube cub = olapDiscoverService.getNativeCube(tq.getCube());
            Query query = Fat.convert(tq, cub);

            QueryDetails details = query.getDetails();
            Measure[] selectedMeasures = new Measure[details.getMeasures().size()];
            for (int i = 0; i < selectedMeasures.length; i++)
                selectedMeasures[i] = details.getMeasures().get(i);
            result.setSelectedMeasures(selectedMeasures);

            int rowsIndex = 0;
            if (!cellSet.getAxes().get(0).getAxisOrdinal().equals(Axis.ROWS)) {
                rowsIndex = (rowsIndex + 1) & 1;
            }
            // TODO - refactor this using axis ordinals etc.
            final AxisInfo[] axisInfos = new AxisInfo[]{new AxisInfo(cellSet.getAxes().get(rowsIndex)), new AxisInfo(cellSet.getAxes().get((rowsIndex + 1) & 1))};
            List<TotalNode>[][] totals = new List[2][];
            TotalsListsBuilder builder = null;
            for (int index = 0; index < 2; index++) {
                final int second = (index + 1) & 1;
                TotalAggregator[] aggregators = new TotalAggregator[axisInfos[second].maxDepth + 1];
                for (int i = 1; i < aggregators.length - 1; i++) {
                    List<String> aggs = query.getAggregators(axisInfos[second].uniqueLevelNames.get(i - 1));
                    String totalFunctionName = aggs != null && aggs.size() > 0 ? aggs.get(0) : null;
                    aggregators[i] = StringUtils.isNotBlank(totalFunctionName) ? TotalAggregator.newInstanceByFunctionName(totalFunctionName) : null;
                }
                List<String> aggs = query.getAggregators(axisInfos[second].axis.getAxisOrdinal().name());
                String totalFunctionName = aggs != null && aggs.size() > 0 ? aggs.get(0) : null;
                aggregators[0] = StringUtils.isNotBlank(totalFunctionName) ? TotalAggregator.newInstanceByFunctionName(totalFunctionName) : null;
                builder = new TotalsListsBuilder(selectedMeasures, aggregators, cellSet, axisInfos[index], axisInfos[second]);
                totals[index] = builder.buildTotalsLists();
            }
            result.setLeftOffset(axisInfos[0].maxDepth);
            result.setRowTotalsLists(totals[1]);
            result.setColTotalsLists(totals[0]);
        }
    }

    public ThinQuery zoomIn(String queryName, List<List<Integer>> realPositions) {

        try {
            if (context.containsKey(queryName)) {
                CellSet cs = context.get(queryName).getOlapResult();
                ThinQuery old = context.get(queryName).getOlapQuery();
                Cube cub = olapDiscoverService.getNativeCube(old.getCube());
                Query q = Fat.convert(old, cub);

                if (cs == null) {
                    throw new SaikuServiceException("Cannot zoom in if last cellset is null");
                }
                if (realPositions == null || realPositions.size() == 0) {
                    throw new SaikuServiceException("Cannot zoom in if zoom in position is empty");
                }

                Map<Hierarchy, Set<Member>> memberSelection = new HashMap<>();
                for (List<Integer> position : realPositions) {
                    for (int k = 0; k < position.size(); k++) {
                        Position p = cs.getAxes().get(k).getPositions().get(position.get(k));
                        List<Member> members = p.getMembers();
                        for (Member m : members) {
                            Hierarchy h = m.getHierarchy();
                            if (!memberSelection.containsKey(h)) {
                                Set<Member> mset = new HashSet<>();
                                memberSelection.put(h, mset);
                            }
                            memberSelection.get(h).add(m);
                        }
                    }
                }


                for (Hierarchy h : memberSelection.keySet()) {
                    QueryHierarchy qh = q.getHierarchy(h);
                    for (QueryLevel ql : qh.getActiveQueryLevels()) {
                        ql.getInclusions().clear();
                        ql.getExclusions().clear();
                        ql.setMdxSetExpression(null);
                    }
                    for (Member m : memberSelection.get(h)) {
                        qh.includeMember(m);
                    }
                }
                ThinQuery tqAfter = Thin.convert(q, old.getCube());
                q = null;
                return tqAfter;
            } else {
                throw new SaikuServiceException("Cannot get query result from context: " + queryName);
            }

        } catch (Exception e) {
            throw new SaikuServiceException("Error zoom in on query: " + queryName, e);
        }

    }

    public ThinQuery drillacross(String queryName, List<Integer> cellPosition, Map<String, List<String>> levels) {
        try {
            ThinQuery old = context.get(queryName).getOlapQuery();
            Cube cub = olapDiscoverService.getNativeCube(old.getCube());
            Query query = Fat.convert(old, cub);
            CellSet cs = context.get(queryName).getOlapResult();


            Set<Level> levelSet = new HashSet<>();
            if (cs == null) {
                throw new SaikuServiceException("Cannot drill across. Last CellSet empty");
            }
            for (int i = 0; i < cellPosition.size(); i++) {
                List<Member> members = cs.getAxes().get(i).getPositions().get(cellPosition.get(i)).getMembers();
                for (Member m : members) {
                    QueryHierarchy qh = query.getHierarchy(m.getHierarchy());
                    if (qh.getHierarchy().getDimension().getName().equals("Measures")) {
                        Measure measure = query.getMeasure(m.getName());
                        if (!query.getDetails().getMeasures().contains(measure)) {
                            query.getDetails().add(measure);
                        }

                    } else {
                        qh.clearSelection();
                        qh.clearFilters();
                        qh.clearSort();
                        query.moveHierarchy(qh, Axis.FILTER);
                        qh.includeMember(m);
                        levelSet.add(m.getLevel());
                    }

                }
            }
            boolean clearedMeasures = false;

            if (levels != null) {
                for (String key : levels.keySet()) {
                    String dimensionName = key.split("###")[0];

                    if ("Measures".equals(dimensionName)) {
                        if (!clearedMeasures) {
                            query.getDetails().getMeasures().clear();
                            clearedMeasures = true;
                        }
                        for (String measureName : levels.get(key)) {
                            Measure measure = query.getMeasure(measureName);
                            if (measure != null) {
                                query.getDetails().add(measure);
                            } else {
                                for (Measure m : cub.getMeasures()) {
                                    if (m.getUniqueName().equals(measureName)) {
                                        query.getDetails().add(m);
                                    }
                                }
                            }
                        }
                        continue;
                    }
                    String hierarchyName = key.split("###")[1];
                    Dimension d = cub.getDimensions().get(dimensionName);
                    Hierarchy h = d.getHierarchies().get(hierarchyName);
                    QueryHierarchy qh = query.getHierarchy(h);
                    for (Level l : h.getLevels()) {
                        for (String levelU : levels.get(key)) {
                            if (l.getUniqueName().equals(levelU) || l.getName().equals(levelU)) {
                                qh.includeLevel(l);
                            }
                        }
                    }
                    if (qh.getActiveQueryLevels().size() > 0) {
                        query.moveHierarchy(qh, Axis.ROWS);
                    }
                }
            }
            if (query.getDetails().getMeasures().size() == 0) {
                QueryHierarchy qh = query.getHierarchy("Measures");
                Member defaultMeasure = qh.getHierarchy().getDefaultMember();
                query.getDetails().add(query.getMeasure(defaultMeasure.getName()));
            }
            return Thin.convert(query, old.getCube());
        } catch (Exception e) {
            throw new SaikuServiceException("Error drilling across: " + queryName, e);
        }
    }

    public boolean isOldQuery(String xml) {
        return StringUtils.isNotBlank(xml) && xml.trim().startsWith("<?xml");
    }

    public ThinQuery convertQuery(String xml) throws Exception {
        if (StringUtils.isNotBlank(xml) && xml.trim().startsWith("<?xml")) {
            QueryDeserializer qd = new QueryDeserializer();
            SaikuCube scube = qd.getFakeCube(xml);
            OlapConnection con = olapDiscoverService.getNativeConnection(scube.getConnection());
            IQuery query = qd.unparse(xml, con);

            if (QueryType.QM.equals(query.getType())) {
                OlapQuery qr = (OlapQuery) query;
                Query sQ = QueryConverter.convertQuery(qr.getQuery());
                SaikuCube converted = ObjectUtil.convert(scube.getConnection(), sQ.getCube());
                return Thin.convert(sQ, converted);
            } else {
                SaikuCube converted = ObjectUtil.convert(scube.getConnection(), olapDiscoverService.getNativeCube(scube));
                return new ThinQuery(query.getName(), converted, query.getMdx());
            }
        }
        return null;

    }
}
