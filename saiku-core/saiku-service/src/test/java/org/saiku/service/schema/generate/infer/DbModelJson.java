package org.saiku.service.schema.generate.infer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.List;
import org.saiku.service.schema.generate.model.DbColumn;
import org.saiku.service.schema.generate.model.DbForeignKey;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.model.DbTable;

/**
 * Test-scope loader that deserialises a small, hand-authored JSON fixture into a
 * {@link DbModel}. Kept deliberately minimal — the fixture format is internal to the
 * golden-file tests and isn't a stable public contract.
 *
 * <p>Fixture shape:
 *
 * <pre>{@code
 * {
 *   "tables": [
 *     {
 *       "schema": "public",
 *       "name": "orders",
 *       "rowCountEstimate": 1000000,
 *       "columns": [
 *         {"name": "id", "type": "INTEGER", "nullable": false, "primaryKey": true}
 *       ],
 *       "foreignKeys": [
 *         {"fromColumn": "customer_id", "toTable": "customers", "toColumn": "id"}
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code type} is resolved via {@link JDBCType#valueOf(String)}.
 */
final class DbModelJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DbModelJson() {}

    static DbModel load(InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        JsonNode tablesNode = root.path("tables");
        List<DbTable> tables = new ArrayList<>();
        if (tablesNode.isArray()) {
            for (JsonNode tn : tablesNode) {
                tables.add(parseTable(tn));
            }
        }
        return new DbModel(tables);
    }

    private static DbTable parseTable(JsonNode tn) {
        String schema = tn.hasNonNull("schema") ? tn.get("schema").asText() : null;
        String name = tn.get("name").asText();
        Long rowCount =
                tn.hasNonNull("rowCountEstimate") ? tn.get("rowCountEstimate").asLong() : null;

        List<DbColumn> columns = new ArrayList<>();
        JsonNode colsNode = tn.path("columns");
        if (colsNode.isArray()) {
            for (JsonNode c : colsNode) {
                columns.add(new DbColumn(
                        c.get("name").asText(),
                        JDBCType.valueOf(c.get("type").asText()),
                        c.path("nullable").asBoolean(true),
                        c.path("primaryKey").asBoolean(false)));
            }
        }

        List<DbForeignKey> fks = new ArrayList<>();
        JsonNode fksNode = tn.path("foreignKeys");
        if (fksNode.isArray()) {
            for (JsonNode fk : fksNode) {
                fks.add(new DbForeignKey(
                        fk.get("fromColumn").asText(),
                        fk.get("toTable").asText(),
                        fk.get("toColumn").asText()));
            }
        }

        return new DbTable(schema, name, columns, fks, rowCount);
    }
}
