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
 * <p>What ships in this first slice:
 *
 * <ul>
 *   <li><b>Startup + trust auth.</b> SSL requests are refused (server replies {@code 'N'}); the
 *       client falls back to plaintext. Trust auth means we accept any username; SCRAM comes in
 *       a follow-up.
 *   <li><b>Simple query mode</b> ({@code Q} messages). Every incoming SQL statement dispatches to
 *       a fresh Calcite {@link Statement} via a pooled JDBC connection.
 *   <li><b>RowDescription + DataRow + CommandComplete + ReadyForQuery.</b> Common PG type OIDs
 *       for BOOL/INT/BIGINT/FLOAT/NUMERIC/VARCHAR/DATE/TIMESTAMP. Anything else serialises as
 *       TEXT — clients still get the string form and coerce.
 *   <li><b>Terminate</b> ({@code X}) closes the connection cleanly.
 * </ul>
 *
 * <p>What doesn't ship yet — filed as follow-ups on saiku#1392:
 *
 * <ul>
 *   <li>Extended query mode (Parse/Bind/Execute). Most drivers auto-fall-back to simple mode.
 *   <li>SSL/TLS. We answer 'N' to SSL requests; clients configured to REQUIRE SSL fail. Users
 *       set {@code sslmode=disable}.
 *   <li>SCRAM-SHA-256 auth.
 *   <li>COPY, cursors, prepared statements.
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

            // Query loop. Read one message at a time until Terminate or connection close.
            while (running.get()) {
                int msgType = in.read();
                if (msgType < 0) return; // client closed
                int length = in.readInt(); // includes the length field itself
                byte[] payload = in.readNBytes(length - 4);
                switch (msgType) {
                    case 'Q':
                        handleSimpleQuery(payload, out, calcite);
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
