/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.olap.query2.OssieQueryModel;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.OlapDiscoverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Execute a shelf-state query against an Ossie datasource. Sibling to the MDX-flavoured
 * {@code ThinQueryService} — the same conceptual role, but the query language is SQL and the
 * return envelope is synthesised from a JDBC ResultSet rather than an olap4j CellSet.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>Resolve the semantic model via {@link OssieDiscoverService} — needed for metric
 *       expressions during translation.
 *   <li>Translate the shelf state to SQL via {@link OssieShelfSqlTranslator}.
 *   <li>Acquire the underlying Calcite {@link Connection} through the shared
 *       {@link IConnectionManager}.
 *   <li>Execute the SQL, project the result set into a {@link CellDataSet} shaped so the
 *       existing frontend cellset renderer displays it unchanged. Header row = column names as
 *       {@link MemberCell}s. Body rows = {@link MemberCell}s for dimension values, {@link
 *       DataCell}s for metric values.
 * </ol>
 */
public class OssieQueryService {

    private static final Logger log = LoggerFactory.getLogger(OssieQueryService.class);

    private OssieDiscoverService discoverService;
    private OlapDiscoverService olapDiscoverService;

    private final OssieShelfSqlTranslator translator = new OssieShelfSqlTranslator();

    public void setOssieDiscoverService(OssieDiscoverService s) {
        this.discoverService = s;
    }

    public void setOlapDiscoverService(OlapDiscoverService s) {
        this.olapDiscoverService = s;
    }

    /**
     * Execute the OSSIE-typed query on {@code tq}. Callers guarantee {@code tq.queryType == OSSIE}
     * and that {@code tq.ossieQueryModel} is populated.
     */
    public CellDataSet execute(ThinQuery tq) throws Exception {
        OssieQueryModel model = tq.getOssieQueryModel();
        if (model == null) {
            throw new IllegalArgumentException(
                    "ThinQuery queryType=OSSIE requires an ossieQueryModel — none present on '" + tq.getName() + "'");
        }
        String connectionName = model.getConnection();
        if (connectionName == null || connectionName.isBlank()) {
            throw new IllegalArgumentException("OssieQueryModel.connection is required");
        }
        OssieModelDto semantic = discoverService.getModel(connectionName);
        String sql = translator.translate(model, semantic);
        log.info("Ossie execute (query='{}', connection='{}'): {}", tq.getName(), connectionName, sql);

        long startMs = System.currentTimeMillis();
        ISaikuConnection saikuConn = olapDiscoverService.getConnectionManager().getConnection(connectionName);
        if (saikuConn == null) {
            throw new IllegalStateException("No live connection registered for '" + connectionName + "'");
        }
        Connection calcite = saikuConn.getConnection();
        if (calcite == null) {
            throw new IllegalStateException(
                    "Connection '" + connectionName + "' is not initialised — check safemode / disabled flags");
        }
        try (Statement stmt = calcite.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            CellDataSet result = toCellDataSet(rs, model);
            result.setRuntime((int) (System.currentTimeMillis() - startMs));
            return result;
        }
    }

    /**
     * Project a JDBC {@link ResultSet} into the {@link CellDataSet} shape the existing MDX
     * CellsetTable renders. Header row is column names as {@link MemberCell}s. Body cells:
     * {@link MemberCell} for dimension columns (rows + columns shelves), {@link DataCell} for
     * metric columns (values shelf). Ordering: dims first (rows then columns), then metrics —
     * mirrors the {@code SELECT} order in {@link OssieShelfSqlTranslator}.
     */
    CellDataSet toCellDataSet(ResultSet rs, OssieQueryModel model) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        int dimensionCount = model.getRows().size() + model.getColumns().size();

        // Header row: one MemberCell per column, carrying its display name.
        AbstractBaseCell[][] header = new AbstractBaseCell[1][columnCount];
        for (int i = 0; i < columnCount; i++) {
            MemberCell mc = new MemberCell(false, false);
            mc.setRawValue(meta.getColumnLabel(i + 1));
            mc.setFormattedValue(meta.getColumnLabel(i + 1));
            header[0][i] = mc;
        }

        // Body rows.
        List<AbstractBaseCell[]> rows = new ArrayList<>();
        while (rs.next()) {
            AbstractBaseCell[] row = new AbstractBaseCell[columnCount];
            for (int i = 0; i < columnCount; i++) {
                Object value = rs.getObject(i + 1);
                String formatted = value == null ? "" : value.toString();
                if (i < dimensionCount) {
                    MemberCell mc = new MemberCell(false, false);
                    mc.setRawValue(formatted);
                    mc.setFormattedValue(formatted);
                    row[i] = mc;
                } else {
                    DataCell dc = new DataCell(true, false, new ArrayList<>());
                    if (value instanceof Number) {
                        dc.setRawNumber(((Number) value).doubleValue());
                    }
                    dc.setRawValue(formatted);
                    dc.setFormattedValue(formatted);
                    row[i] = dc;
                }
            }
            rows.add(row);
        }

        AbstractBaseCell[][] body = rows.toArray(new AbstractBaseCell[0][]);
        CellDataSet ds = new CellDataSet(columnCount, rows.size());
        ds.setCellSetHeaders(header);
        ds.setCellSetBody(body);
        return ds;
    }
}
