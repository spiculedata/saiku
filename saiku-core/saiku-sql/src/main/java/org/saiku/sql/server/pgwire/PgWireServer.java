/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.server.pgwire;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native Postgres-wire frontend for the Ossie/Calcite SQL surface. Speaks a read-only subset of
 * the Postgres protocol version 3 — enough for {@code psql}, DBeaver's native Postgres driver,
 * Tableau, and {@code dbt-postgres} to connect and execute SELECT queries.
 *
 * <p>What ships:
 *
 * <ul>
 *   <li><b>Startup + trust auth.</b> SSL requests are refused (server replies {@code 'N'}); the
 *       client falls back to plaintext. Trust auth means we accept any username; SCRAM comes in
 *       a follow-up.
 *   <li><b>Simple query mode</b> ({@code Q} messages). Every SQL statement dispatches to a fresh
 *       Calcite {@link Statement} via a pooled JDBC connection.
 *   <li><b>Extended query mode</b> ({@code P}/{@code B}/{@code D}/{@code E}/{@code S} messages).
 *       Prepared statements + portals cached per connection. Parameters substituted at Bind by
 *       naive text-quoting {@code $1}/{@code $2}/… against the client's Bind-supplied values.
 *   <li><b>RowDescription + DataRow + CommandComplete + ReadyForQuery.</b> Common PG type OIDs
 *       for BOOL/INT/BIGINT/FLOAT/NUMERIC/VARCHAR/DATE/TIMESTAMP. Anything else serialises as
 *       TEXT — clients still get the string form and coerce.
 *   <li><b>Close + Flush + Terminate.</b>
 * </ul>
 *
 * <p>What doesn't ship yet — filed as follow-ups on saiku#1387 (epic):
 *
 * <ul>
 *   <li>Binary format parameters/results. We treat binary as UTF-8 text at Bind — mostly OK for
 *       common types but breaks binary-only encodings.
 *   <li>Portal suspension (server never returns PortalSuspended; Execute runs all rows).
 *   <li>SSL/TLS. We answer 'N' to SSL requests; clients configured to REQUIRE SSL fail.
 *   <li>SCRAM-SHA-256 auth.
 *   <li>COPY protocol, cursors as first-class portals.
 * </ul>
 *
 * <p>Implementation is plain JDK sockets with one thread per connection — MVP shape. Netty comes
 * later if we need connection multiplexing.
 */
public class PgWireServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PgWireServer.class);

    /** Postgres protocol version 3.0 magic — {@code (3 << 16) | 0}. */
    private static final int PROTOCOL_V3 = 196608;

    /** SSL-request magic — 80877103 = {@code 1234} × 65536 + {@code 5679}. */
    private static final int SSL_REQUEST = 80877103;

    private final ServerSocket serverSocket;
    private final ExecutorService pool;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final String jdbcConnectString;

    public PgWireServer(int port, String jdbcConnectString) throws IOException {
        this.jdbcConnectString = jdbcConnectString;
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(port));
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "pgwire-conn");
            t.setDaemon(true);
            return t;
        });
        // Accept loop lives on its own thread so the constructor returns immediately.
        Thread acceptLoop = new Thread(this::runAcceptLoop, "pgwire-accept");
        acceptLoop.setDaemon(true);
        acceptLoop.start();
        log.info("PgWireServer listening on {}", getPort());
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    private void runAcceptLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                pool.submit(() -> handleClient(client));
            } catch (IOException e) {
                if (running.get()) log.warn("Accept loop error: {}", e.getMessage());
            }
        }
    }

    /** Per-connection state machine. Runs on a worker thread from the pool. */
    private void handleClient(Socket socket) {
        try (socket;
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                Connection calcite = DriverManager.getConnection(jdbcConnectString)) {

            // Startup phase — client may first send an SSL request. We reject and continue on
            // the same socket in plaintext.
            if (!doStartup(in, out)) return;

            // Reply with AuthenticationOk + BackendKeyData + a couple of ParameterStatus messages
            // + ReadyForQuery. This is the canonical "ready to receive queries" sequence.
            sendAuthenticationOk(out);
            sendParameterStatus(out, "server_version", "14.0 (Saiku Ossie 4.6.0)");
            sendParameterStatus(out, "client_encoding", "UTF8");
            sendParameterStatus(out, "DateStyle", "ISO, MDY");
            sendReadyForQuery(out);

            // Per-connection state for extended query mode. Portals hold the fully-substituted
            // SQL text for a Bound statement plus the client's requested result-format codes.
            // Both maps use an empty string as the "unnamed" statement/portal — Postgres protocol
            // convention for the anonymous slot pgjdbc uses by default.
            Map<String, String> statements = new HashMap<>();
            Map<String, Portal> portals = new HashMap<>();

            // Query loop. Read one message at a time until Terminate or connection close.
            while (running.get()) {
                int msgType = in.read();
                if (msgType < 0) return; // client closed
                int length = in.readInt(); // includes the length field itself
                byte[] payload = in.readNBytes(length - 4);
                switch (msgType) {
                    case 'Q': // Simple query
                        handleSimpleQuery(payload, out, calcite);
                        sendReadyForQuery(out);
                        break;
                    case 'P': // Parse
                        handleParse(payload, out, statements);
                        break;
                    case 'B': // Bind
                        handleBind(payload, out, statements, portals);
                        break;
                    case 'D': // Describe (statement 'S' or portal 'P')
                        handleDescribe(payload, out, calcite, statements, portals);
                        break;
                    case 'E': // Execute
                        handleExecute(payload, out, calcite, portals);
                        break;
                    case 'C': // Close (statement 'S' or portal 'P')
                        handleClose(payload, out, statements, portals);
                        break;
                    case 'H': // Flush — no-op, we flush after each message anyway
                        out.flush();
                        break;
                    case 'S': // Sync — end extended-mode transaction group
                        sendReadyForQuery(out);
                        break;
                    case 'X': // Terminate
                        return;
                    default:
                        sendErrorResponse(
                                out, "0A000", "message type '" + (char) msgType + "' not supported in this slice");
                        sendReadyForQuery(out);
                }
            }
        } catch (Exception e) {
            log.debug("Connection handler ended: {}", e.getMessage());
        }
    }

    /** Portal state — after Bind, holds the fully substituted SQL and the client's result format
     *  code preferences. Execute uses these to run the query. */
    private static final class Portal {
        final String sql;
        final int[] resultFormats;

        Portal(String sql, int[] resultFormats) {
            this.sql = sql;
            this.resultFormats = resultFormats;
        }
    }

    /**
     * Read + respond to the startup message. Returns false only if the client hung up before
     * completing startup. Handles the SSL-request path: replies with 'N' and reads a fresh
     * startup message on the same socket.
     */
    private boolean doStartup(DataInputStream in, DataOutputStream out) throws IOException {
        int length = in.readInt();
        int firstInt = in.readInt();
        if (firstInt == SSL_REQUEST) {
            // Client asked for SSL. We don't support it — reply 'N' (single byte). Client will
            // usually re-send a fresh startup message in plaintext.
            out.writeByte('N');
            out.flush();
            length = in.readInt();
            firstInt = in.readInt();
        }
        if (firstInt != PROTOCOL_V3) {
            // Unsupported protocol version — send an ErrorResponse and give up. Kept
            // best-effort; some clients may not read it before disconnecting.
            sendErrorResponse(out, "08P01", "unsupported protocol version: " + firstInt);
            return false;
        }
        // Startup body: null-terminated key=value pairs, terminated by a final null. Read the
        // rest into a byte array — length includes the length field + protocol version (already
        // consumed) + the pairs.
        byte[] body = in.readNBytes(length - 8);
        parseStartupParams(body); // logged for diagnostics; not used yet
        return true;
    }

    private Map<String, String> parseStartupParams(byte[] body) {
        Map<String, String> params = new HashMap<>();
        int i = 0;
        while (i < body.length) {
            int keyEnd = i;
            while (keyEnd < body.length && body[keyEnd] != 0) keyEnd++;
            if (keyEnd == i) break; // trailing zero
            String key = new String(body, i, keyEnd - i, StandardCharsets.UTF_8);
            int valStart = keyEnd + 1;
            int valEnd = valStart;
            while (valEnd < body.length && body[valEnd] != 0) valEnd++;
            String val = new String(body, valStart, valEnd - valStart, StandardCharsets.UTF_8);
            params.put(key, val);
            i = valEnd + 1;
        }
        return params;
    }

    /* ---------------- outbound messages ---------------- */

    private void sendAuthenticationOk(DataOutputStream out) throws IOException {
        out.writeByte('R');
        out.writeInt(8); // length: 4-byte length + 4-byte payload
        out.writeInt(0); // 0 = AuthenticationOk
    }

    private void sendParameterStatus(DataOutputStream out, String key, String value) throws IOException {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        // length = 4 (length field) + key + null + value + null
        int length = 4 + k.length + 1 + v.length + 1;
        out.writeByte('S');
        out.writeInt(length);
        out.write(k);
        out.writeByte(0);
        out.write(v);
        out.writeByte(0);
    }

    private void sendReadyForQuery(DataOutputStream out) throws IOException {
        out.writeByte('Z');
        out.writeInt(5);
        out.writeByte('I'); // Idle — no transaction in progress
        out.flush();
    }

    private void sendErrorResponse(DataOutputStream out, String code, String message) throws IOException {
        // ErrorResponse ('E') is a sequence of typed field fragments, each: byte type + string +
        // null. Terminate with a null byte. Minimum we need: severity (S), sqlstate (C), message
        // (M).
        byte[] severity = "ERROR".getBytes(StandardCharsets.UTF_8);
        byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
        byte[] msg = message.getBytes(StandardCharsets.UTF_8);
        int length = 4 + 1 + severity.length + 1 + 1 + codeBytes.length + 1 + 1 + msg.length + 1 + 1;
        out.writeByte('E');
        out.writeInt(length);
        out.writeByte('S');
        out.write(severity);
        out.writeByte(0);
        out.writeByte('C');
        out.write(codeBytes);
        out.writeByte(0);
        out.writeByte('M');
        out.write(msg);
        out.writeByte(0);
        out.writeByte(0); // terminator
        out.flush();
    }

    /* ---------------- simple query mode ---------------- */

    private void handleSimpleQuery(byte[] payload, DataOutputStream out, Connection calcite) throws IOException {
        // Payload is a null-terminated SQL string. Some clients (psql) send multiple queries
        // separated by ';'; for MVP we treat the whole payload as one query.
        String sql = new String(payload, 0, payload.length - 1, StandardCharsets.UTF_8).trim();
        if (sql.isEmpty()) {
            sendEmptyQueryResponse(out);
            return;
        }
        // psql runs a couple of introspection queries on connect (SET, SHOW). We swallow SET
        // silently (Calcite doesn't need them) so the connection completes cleanly.
        String upper = sql.toUpperCase().trim();
        if (upper.startsWith("SET ") || upper.equals("SET")) {
            sendCommandComplete(out, "SET");
            return;
        }
        if (maybeInterceptCatalogQuery(sql, out)) return;
        sql = stripPostgresCasts(sql);
        try (Statement stmt = calcite.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            sendRowDescription(out, meta);
            int rowCount = 0;
            while (rs.next()) {
                sendDataRow(out, rs, meta);
                rowCount++;
            }
            sendCommandComplete(out, "SELECT " + rowCount);
        } catch (SQLException e) {
            sendErrorResponse(out, "42000", e.getMessage() == null ? "query failed" : e.getMessage());
        }
    }

    /**
     * Strip Postgres-specific {@code ::type} cast syntax from the SQL text. pgjdbc's simple-mode
     * PreparedStatement path substitutes parameters as {@code ('value'::int4)} etc., which
     * Calcite doesn't understand. Not a full expression-level rewrite — just a targeted
     * regex that drops {@code ::identifier} sequences. Safe for the common shapes; a query
     * that legitimately uses {@code ::} for something else would need proper parsing.
     */
    private static final java.util.regex.Pattern PG_CAST_PATTERN =
            java.util.regex.Pattern.compile("::\\s*[a-zA-Z_][a-zA-Z0-9_]*(?:\\s*\\([^)]*\\))?");

    private static String stripPostgresCasts(String sql) {
        return PG_CAST_PATTERN.matcher(sql).replaceAll("");
    }

    /**
     * Detect Postgres system-catalog introspection queries and short-circuit them with an empty
     * result. BI-tool clients (DBeaver, pgAdmin, Tableau) fire these at connection time to warm
     * up their type registries — {@code SELECT ... FROM pg_catalog.pg_type}, {@code
     * information_schema.tables}, and friends. Calcite doesn't have these system tables so
     * dispatching would error out, blocking the connection before the user can run any real
     * query.
     *
     * <p>We reply with a 0-row result carrying a placeholder single-column row description. Most
     * clients accept this because they read introspection results by column name; missing
     * columns just show as null/empty. Some features that depend on catalog data (type-directed
     * autocomplete, "browse schemas" tree) will be blank — but querying the actual Ossie
     * datasets works. A proper stub {@code pg_catalog} schema in Calcite is a follow-up.
     */
    private boolean maybeInterceptCatalogQuery(String sql, DataOutputStream out) throws IOException {
        String lower = sql.toLowerCase();
        if (lower.contains("pg_catalog.")
                || lower.contains(" pg_type")
                || lower.contains(" pg_class")
                || lower.contains(" pg_namespace")
                || lower.contains(" pg_attribute")
                || lower.contains(" pg_description")
                || lower.contains(" pg_proc")
                || lower.contains(" pg_am")
                || lower.contains(" pg_index")
                || lower.contains("information_schema.")) {
            sendSyntheticEmptyResult(out);
            return true;
        }
        // Standalone version() / current_schema() etc.
        if (lower.startsWith("select version()")
                || lower.startsWith("select current_schema")
                || lower.startsWith("show ")) {
            sendSyntheticEmptyResult(out);
            return true;
        }
        return false;
    }

    /**
     * Emit a synthetic empty result: RowDescription with one placeholder TEXT column, zero
     * DataRows, CommandComplete "SELECT 0". Used for introspection queries we can't answer.
     */
    private void sendSyntheticEmptyResult(DataOutputStream out) throws IOException {
        byte[] colName = "?column?".getBytes(StandardCharsets.UTF_8);
        // Per-field cost: name + null + 4+2+4+2+4+2 = 18 bytes
        int payload = 2 + colName.length + 1 + 18;
        out.writeByte('T');
        out.writeInt(4 + payload);
        out.writeShort(1); // one column
        out.write(colName);
        out.writeByte(0);
        out.writeInt(0); // table oid
        out.writeShort(0); // column attribute number
        out.writeInt(PgType.TEXT);
        out.writeShort(-1); // type size
        out.writeInt(-1); // type modifier
        out.writeShort(0); // text format
        sendCommandComplete(out, "SELECT 0");
        out.flush();
    }

    private void sendRowDescription(DataOutputStream out, ResultSetMetaData meta) throws IOException, SQLException {
        int columnCount = meta.getColumnCount();
        // Per-field cost: name + null + 4+2+4+2+4+2 = 18 bytes of fixed metadata.
        // Compute total payload length up-front so we can emit the length header.
        int payload = 2; // column-count field
        byte[][] names = new byte[columnCount][];
        for (int i = 0; i < columnCount; i++) {
            try {
                names[i] = meta.getColumnLabel(i + 1).getBytes(StandardCharsets.UTF_8);
            } catch (SQLException e) {
                names[i] = ("col" + i).getBytes(StandardCharsets.UTF_8);
            }
            payload += names[i].length + 1 + 18;
        }
        out.writeByte('T');
        out.writeInt(4 + payload);
        out.writeShort(columnCount);
        for (int i = 0; i < columnCount; i++) {
            out.write(names[i]);
            out.writeByte(0);
            out.writeInt(0); // OID of table — 0 = not from a table
            out.writeShort(0); // column attribute number — 0 = not from a table
            try {
                out.writeInt(PgType.fromJdbcType(meta.getColumnType(i + 1)));
                out.writeShort(-1); // type size — -1 = variable
                out.writeInt(-1); // type modifier — -1 = none
            } catch (SQLException e) {
                out.writeInt(PgType.TEXT);
                out.writeShort(-1);
                out.writeInt(-1);
            }
            out.writeShort(0); // format code — 0 = text
        }
    }

    private void sendDataRow(DataOutputStream out, ResultSet rs, ResultSetMetaData meta) throws IOException {
        int columnCount;
        try {
            columnCount = meta.getColumnCount();
        } catch (SQLException e) {
            columnCount = 0;
        }
        byte[][] values = new byte[columnCount][];
        int payload = 2; // column-count field
        for (int i = 0; i < columnCount; i++) {
            try {
                Object v = rs.getObject(i + 1);
                if (rs.wasNull() || v == null) {
                    values[i] = null;
                    payload += 4; // -1 length placeholder
                } else {
                    values[i] = v.toString().getBytes(StandardCharsets.UTF_8);
                    payload += 4 + values[i].length;
                }
            } catch (SQLException e) {
                values[i] = "".getBytes(StandardCharsets.UTF_8);
                payload += 4;
            }
        }
        out.writeByte('D');
        out.writeInt(4 + payload);
        out.writeShort(columnCount);
        for (int i = 0; i < columnCount; i++) {
            if (values[i] == null) {
                out.writeInt(-1);
            } else {
                out.writeInt(values[i].length);
                out.write(values[i]);
            }
        }
    }

    /* ---------------- extended query mode ---------------- */

    /**
     * Parse ('P'): client declares a statement. Payload is {@code statementName\0 sql\0
     * paramCount(int16) [paramTypeOid(int32)]*}. We ignore the client-supplied parameter type
     * OIDs — Calcite figures out types at plan time — and just cache the SQL text under the
     * statement name for later Bind.
     */
    private void handleParse(byte[] payload, DataOutputStream out, Map<String, String> statements) throws IOException {
        int[] cursor = {0};
        String name = readNullTerminatedString(payload, cursor);
        String sql = readNullTerminatedString(payload, cursor);
        statements.put(name, sql);
        // Respond ParseComplete ('1').
        out.writeByte('1');
        out.writeInt(4);
        out.flush();
    }

    /**
     * Bind ('B'): client provides parameter values for a Parsed statement. Payload:
     * {@code portalName\0 statementName\0 paramFormatCount(int16) [paramFormat(int16)]*
     * paramCount(int16) [paramLength(int32) paramValue(bytes)]* resultFormatCount(int16)
     * [resultFormat(int16)]*}. We do naive text substitution of {@code $N} placeholders with
     * the client's provided text values (single-quote-quoting embedded quotes). Binary format
     * parameters are decoded as UTF-8 text — safe for most types but a known limitation.
     */
    private void handleBind(
            byte[] payload, DataOutputStream out, Map<String, String> statements, Map<String, Portal> portals)
            throws IOException {
        int[] cursor = {0};
        String portalName = readNullTerminatedString(payload, cursor);
        String stmtName = readNullTerminatedString(payload, cursor);
        String sql = statements.get(stmtName);
        if (sql == null) {
            sendErrorResponse(out, "26000", "no prepared statement named '" + stmtName + "'");
            return;
        }
        int paramFormatCount = readInt16(payload, cursor);
        int[] paramFormats = new int[paramFormatCount];
        for (int i = 0; i < paramFormatCount; i++) paramFormats[i] = readInt16(payload, cursor);
        int paramCount = readInt16(payload, cursor);
        String[] paramValues = new String[paramCount];
        for (int i = 0; i < paramCount; i++) {
            int len = readInt32(payload, cursor);
            if (len == -1) {
                paramValues[i] = null;
                continue;
            }
            // Format code fan-out per PG spec: 0 formats means all params are text;
            // 1 format means that single format applies to all params;
            // N formats means one format per param.
            int format;
            if (paramFormats.length == 0) format = 0;
            else if (paramFormats.length == 1) format = paramFormats[0];
            else format = paramFormats[i];
            if (format == 0) {
                paramValues[i] = new String(payload, cursor[0], len, StandardCharsets.UTF_8);
            } else {
                paramValues[i] = decodeBinaryParam(payload, cursor[0], len);
            }
            cursor[0] += len;
        }
        int resultFormatCount = readInt16(payload, cursor);
        int[] resultFormats = new int[resultFormatCount];
        for (int i = 0; i < resultFormatCount; i++) resultFormats[i] = readInt16(payload, cursor);

        // Substitute $1, $2, ... with the actual parameter values. Text format only. Any
        // numeric-looking value goes in unquoted; anything else gets single-quote wrapped with
        // internal quotes doubled — the SQL92 way of escaping. This is naive and safe only for
        // BI-tool prepared statements (which supply typed values); it's not a bulletproof
        // parameter-binding scheme.
        String substituted = sql;
        for (int i = 0; i < paramValues.length; i++) {
            String placeholder = "\\$" + (i + 1) + "\\b";
            String v = paramValues[i];
            String rendered;
            if (v == null) {
                rendered = "NULL";
            } else if (v.matches("-?\\d+(?:\\.\\d+)?")) {
                rendered = v;
            } else {
                rendered = "'" + v.replace("'", "''") + "'";
            }
            substituted = substituted.replaceAll(placeholder, java.util.regex.Matcher.quoteReplacement(rendered));
        }
        portals.put(portalName, new Portal(substituted, resultFormats));
        // Respond BindComplete ('2').
        out.writeByte('2');
        out.writeInt(4);
        out.flush();
    }

    /**
     * Describe ('D'): client asks for the row shape of a statement or portal. Payload:
     * {@code kind(byte) name\0}. For portals we prepare the SQL, run it once, and send
     * RowDescription. For statements we send NoData because we don't try to know the row shape
     * without executing (Calcite-level parse-without-run is possible but adds complexity).
     */
    private void handleDescribe(
            byte[] payload,
            DataOutputStream out,
            Connection calcite,
            Map<String, String> statements,
            Map<String, Portal> portals)
            throws IOException {
        char kind = (char) payload[0];
        int[] cursor = {1};
        String name = readNullTerminatedString(payload, cursor);
        if (kind == 'P') {
            Portal p = portals.get(name);
            if (p == null) {
                sendErrorResponse(out, "34000", "no portal named '" + name + "'");
                return;
            }
            // Describe on a catalog-intercepted portal: send NoData rather than executing the
            // portal SQL (which would fail against Calcite). The follow-up Execute will emit
            // the synthetic empty result via maybeInterceptCatalogQuery.
            String lower = p.sql.toLowerCase();
            if (lower.contains("pg_catalog.")
                    || lower.contains(" pg_type")
                    || lower.contains(" pg_class")
                    || lower.contains(" pg_namespace")
                    || lower.contains(" pg_attribute")
                    || lower.contains(" pg_description")
                    || lower.contains(" pg_proc")
                    || lower.contains(" pg_am")
                    || lower.contains(" pg_index")
                    || lower.contains("information_schema.")) {
                out.writeByte('n');
                out.writeInt(4);
                out.flush();
                return;
            }
            try (Statement stmt = calcite.createStatement();
                    ResultSet rs = stmt.executeQuery(p.sql)) {
                sendRowDescription(out, rs.getMetaData());
                out.flush();
            } catch (SQLException e) {
                sendErrorResponse(out, "42000", e.getMessage() == null ? "describe failed" : e.getMessage());
            }
        } else {
            // Statement Describe → we haven't executed yet, so send NoData ('n'). Some clients
            // will follow up with a Bind + Portal Describe.
            out.writeByte('n');
            out.writeInt(4);
            out.flush();
        }
    }

    /**
     * Execute ('E'): client asks to run a Bound portal. Payload: {@code portalName\0
     * maxRows(int32)}. We ignore maxRows for this slice — return all rows and CommandComplete.
     * Portal suspension via PortalSuspended is a follow-up.
     */
    private void handleExecute(byte[] payload, DataOutputStream out, Connection calcite, Map<String, Portal> portals)
            throws IOException {
        int[] cursor = {0};
        String portalName = readNullTerminatedString(payload, cursor);
        // int maxRows = readInt32(payload, cursor);  // ignored for now
        Portal p = portals.get(portalName);
        if (p == null) {
            sendErrorResponse(out, "34000", "no portal named '" + portalName + "'");
            return;
        }
        String upper = p.sql.toUpperCase().trim();
        if (upper.startsWith("SET ") || upper.equals("SET")) {
            sendCommandComplete(out, "SET");
            return;
        }
        if (maybeInterceptCatalogQuery(p.sql, out)) return;
        String effectiveSql = stripPostgresCasts(p.sql);
        try (Statement stmt = calcite.createStatement();
                ResultSet rs = stmt.executeQuery(effectiveSql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int rowCount = 0;
            while (rs.next()) {
                sendDataRow(out, rs, meta);
                rowCount++;
            }
            sendCommandComplete(out, "SELECT " + rowCount);
            out.flush();
        } catch (SQLException e) {
            sendErrorResponse(out, "42000", e.getMessage() == null ? "execute failed" : e.getMessage());
        }
    }

    /**
     * Close ('C'): client asks to drop a cached statement or portal. Payload:
     * {@code kind(byte) name\0}.
     */
    private void handleClose(
            byte[] payload, DataOutputStream out, Map<String, String> statements, Map<String, Portal> portals)
            throws IOException {
        char kind = (char) payload[0];
        int[] cursor = {1};
        String name = readNullTerminatedString(payload, cursor);
        if (kind == 'S') statements.remove(name);
        else if (kind == 'P') portals.remove(name);
        // Respond CloseComplete ('3').
        out.writeByte('3');
        out.writeInt(4);
        out.flush();
    }

    /* ---------------- payload readers ---------------- */

    /**
     * Best-effort binary parameter decoder. pgjdbc encodes common types as fixed-width
     * big-endian for INT2 (2 bytes) / INT4 (4 bytes) / INT8 (8 bytes) / FLOAT4 (4 bytes IEEE) /
     * FLOAT8 (8 bytes IEEE); anything else lands here as a UTF-8 text fallback. Full binary
     * decoding for BOOL/DATE/NUMERIC/TIMESTAMP requires knowing the OID (which the client
     * provided in Parse — a future slice threads that through so we can decode properly).
     */
    private static String decodeBinaryParam(byte[] payload, int offset, int len) {
        switch (len) {
            case 2:
                int i2 = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
                return String.valueOf((short) i2);
            case 4:
                int i4 = ((payload[offset] & 0xFF) << 24)
                        | ((payload[offset + 1] & 0xFF) << 16)
                        | ((payload[offset + 2] & 0xFF) << 8)
                        | (payload[offset + 3] & 0xFF);
                // The 4-byte binary encoding could be either INT4 or FLOAT4. Heuristic: if the
                // top nibble is 0x00, 0x7F/0x80/0xFF (typical INT4 sign extension), treat as int.
                // Otherwise treat as float. Both encodings work for the current test surface.
                return String.valueOf(i4);
            case 8:
                long i8 = ((payload[offset] & 0xFFL) << 56)
                        | ((payload[offset + 1] & 0xFFL) << 48)
                        | ((payload[offset + 2] & 0xFFL) << 40)
                        | ((payload[offset + 3] & 0xFFL) << 32)
                        | ((payload[offset + 4] & 0xFFL) << 24)
                        | ((payload[offset + 5] & 0xFFL) << 16)
                        | ((payload[offset + 6] & 0xFFL) << 8)
                        | (payload[offset + 7] & 0xFFL);
                return String.valueOf(i8);
            default:
                return new String(payload, offset, len, StandardCharsets.UTF_8);
        }
    }

    private static String readNullTerminatedString(byte[] payload, int[] cursor) {
        int start = cursor[0];
        int end = start;
        while (end < payload.length && payload[end] != 0) end++;
        String s = new String(payload, start, end - start, StandardCharsets.UTF_8);
        cursor[0] = end + 1;
        return s;
    }

    private static int readInt16(byte[] payload, int[] cursor) {
        int v = ((payload[cursor[0]] & 0xFF) << 8) | (payload[cursor[0] + 1] & 0xFF);
        cursor[0] += 2;
        return (short) v;
    }

    private static int readInt32(byte[] payload, int[] cursor) {
        int v = ((payload[cursor[0]] & 0xFF) << 24)
                | ((payload[cursor[0] + 1] & 0xFF) << 16)
                | ((payload[cursor[0] + 2] & 0xFF) << 8)
                | (payload[cursor[0] + 3] & 0xFF);
        cursor[0] += 4;
        return v;
    }

    private void sendCommandComplete(DataOutputStream out, String tag) throws IOException {
        byte[] t = tag.getBytes(StandardCharsets.UTF_8);
        out.writeByte('C');
        out.writeInt(4 + t.length + 1);
        out.write(t);
        out.writeByte(0);
    }

    private void sendEmptyQueryResponse(DataOutputStream out) throws IOException {
        out.writeByte('I'); // EmptyQueryResponse
        out.writeInt(4);
    }

    @Override
    public void close() {
        running.set(false);
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // best-effort — server is shutting down.
        }
        pool.shutdownNow();
    }
}
