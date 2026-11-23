/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.quickstart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an uploaded CSV file into a queryable table, the missing first step of the saiku#1117
 * quickstart flow: "CSV/Parquet upload → starter-cube UI route". Everything downstream of a
 * registered JDBC datasource — introspection, measure/dimension inference, LLM enrichment, saving
 * the Mondrian schema — already exists ({@code schema/generate/}, {@code
 * SchemaGeneratorResource}); this class exists purely to bridge "the user has a CSV" to "there is
 * a JDBC table for the existing pipeline to introspect".
 *
 * <p>Each upload gets its own single-table embedded H2 database under {@code
 * <saiku.home>/data/quickstart/<tableName>/} rather than sharing one file across uploads. The
 * schema-generation pipeline introspects a datasource's connection with no schema/catalog filter
 * (see {@code JdbcIntrospector}'s default {@code Options}), so a shared multi-upload database
 * would hand the inferrer every other upload's table in the same {@link
 * org.saiku.service.schema.generate.model.DbModel} — silently merging unrelated uploads into one
 * confused draft, and defeating {@code TableClassifier}'s lone-table-is-always-fact rule (see its
 * class doc) the moment a second upload lands.
 *
 * <p>This class never touches the Saiku datasource repository or the schema-generation session
 * store — registering the returned {@link QuickstartUploadResult} as a datasource and kicking off
 * the pipeline is the REST layer's job (mirrors how the cube-designer route publishes a schema:
 * see {@code saiku-ui}'s {@code publish.ts}), so it stays trivially unit-testable against a plain
 * JDBC connection.
 */
public class QuickstartIngestService {

    private static final Logger LOG = LoggerFactory.getLogger(QuickstartIngestService.class);

    /**
     * Upload size ceiling. Generous for the "quickstart demo" use case this route targets (a
     * spreadsheet export, not a data warehouse) while bounding how much memory a single upload
     * can claim — the whole file is decoded to a {@link String} up front (see {@link CsvTable}'s
     * class doc for why), so there is no streaming backpressure to lean on instead.
     */
    static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;

    private static final String H2_DB_NAME = "quickstart";

    private final Path baseDir;

    /** @param homeDir the resolved {@code ${saiku.home}} directory. */
    public QuickstartIngestService(Path homeDir) {
        this.baseDir = homeDir.resolve("data").resolve("quickstart");
    }

    /**
     * Parse {@code csvStream} and load it into a fresh, dedicated H2 table.
     *
     * @param requestedTableName the name the caller asked for (e.g. the upload form field);
     *     sanitised, may be blank
     * @param fallbackName used to derive a table name when {@code requestedTableName} is blank —
     *     typically the uploaded file's original name
     * @throws CsvIngestException the CSV is malformed (see {@link CsvTable#parse}), or a
     *     quickstart table with the resolved name already exists
     * @throws IOException the upload exceeds {@link #MAX_UPLOAD_BYTES} or the stream fails
     * @throws SQLException the H2 database could not be created or loaded
     */
    public QuickstartUploadResult ingest(String requestedTableName, String fallbackName, InputStream csvStream)
            throws IOException, SQLException {
        String csv = readAllBounded(csvStream);
        CsvTable table = CsvTable.parse(csv);
        String tableName = QuickstartNames.sanitize(requestedTableName, fallbackName);

        Path dbDir = baseDir.resolve(tableName);
        if (Files.exists(dbDir)) {
            throw new CsvIngestException(
                    "a quickstart upload named '" + tableName + "' already exists — choose a different name");
        }
        Files.createDirectories(dbDir);

        String dbPath = dbDir.resolve(H2_DB_NAME).toAbsolutePath().toString().replace('\\', '/');
        String jdbcUrl = "jdbc:h2:" + dbPath;

        int rowCount;
        try {
            rowCount = loadTable(table, tableName, jdbcUrl);
        } catch (SQLException | RuntimeException e) {
            // Don't leave a half-created database blocking a retry under the same name.
            deleteQuietly(dbDir);
            throw e;
        }

        List<QuickstartColumn> columns = new ArrayList<>(table.columns().size());
        for (CsvTable.Column column : table.columns()) {
            columns.add(new QuickstartColumn(column.name(), column.type().name()));
        }
        return new QuickstartUploadResult(jdbcUrl, "org.h2.Driver", tableName, rowCount, columns);
    }

    private static int loadTable(CsvTable table, String tableName, String jdbcUrl) throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(jdbcUrl);
        ds.setUser("sa");
        ds.setPassword("");
        try (Connection connection = ds.getConnection()) {
            return CsvTableLoader.load(table, tableName, connection);
        }
    }

    private static void deleteQuietly(Path dir) {
        try {
            FileUtils.deleteDirectory(dir.toFile());
        } catch (IOException cleanupFailed) {
            LOG.warn(
                    "Failed to clean up quickstart directory {} after a failed upload: {}",
                    dir,
                    cleanupFailed.getMessage());
        }
    }

    private static String readAllBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_UPLOAD_BYTES) {
                throw new CsvIngestException(
                        "the CSV file is larger than the " + (MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB upload limit");
            }
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
