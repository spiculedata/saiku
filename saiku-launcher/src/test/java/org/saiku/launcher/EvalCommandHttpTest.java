/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import org.junit.Test;

/**
 * {@link EvalCommand#call()} network paths, driven against a local HTTP
 * server — the remote-endpoint contract the unit suite stops short of.
 */
public class EvalCommandHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GREEN_RESPONSE = "{\"summary\":\"3 suites, 24 cases\",\"hasFailures\":false,"
            + "\"results\":[{\"suiteName\":\"basic\",\"passed\":10,\"total\":10}],\"skipped\":[]}";
    private static final String REGRESSION_RESPONSE = "{\"summary\":\"2 suites\",\"hasFailures\":true,"
            + "\"results\":[{\"suiteName\":\"basic\",\"passed\":3,\"total\":10}],\"skipped\":[]}";

    private static HttpServer serve(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        server.createContext("/rest/saiku/admin/ai-evals/run", exchange -> {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        return server;
    }

    @Test
    public void aGreenSweepExitsZeroOverRealHttp() throws Exception {
        HttpServer server = serve(200, GREEN_RESPONSE);
        try {
            EvalCommand cmd = new EvalCommand();
            cmd.server = "http://127.0.0.1:" + server.getAddress().getPort();
            assertEquals(Integer.valueOf(0), cmd.call());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void aRegressionExitsOneOverRealHttp() throws Exception {
        HttpServer server = serve(200, REGRESSION_RESPONSE);
        try {
            EvalCommand cmd = new EvalCommand();
            cmd.server = "http://127.0.0.1:" + server.getAddress().getPort();
            assertEquals(Integer.valueOf(1), cmd.call());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void aRejectedAuthenticationExitsTwo() throws Exception {
        HttpServer server = serve(401, "denied");
        try {
            EvalCommand cmd = new EvalCommand();
            cmd.server = "http://127.0.0.1:" + server.getAddress().getPort();
            assertEquals(Integer.valueOf(2), cmd.call());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void aServerErrorExitsTwo() throws Exception {
        HttpServer server = serve(500, "boom");
        try {
            EvalCommand cmd = new EvalCommand();
            cmd.server = "http://127.0.0.1:" + server.getAddress().getPort();
            assertEquals(Integer.valueOf(2), cmd.call());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void anUnparseableBodyExitsTwo() throws Exception {
        HttpServer server = serve(200, "not json");
        try {
            EvalCommand cmd = new EvalCommand();
            cmd.server = "http://127.0.0.1:" + server.getAddress().getPort();
            assertEquals(Integer.valueOf(2), cmd.call());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void anUnreachableServerExitsTwo() {
        // Port 1 on loopback — nothing listens there.
        EvalCommand cmd = new EvalCommand();
        cmd.server = "http://127.0.0.1:1";
        assertEquals(Integer.valueOf(2), cmd.call());
    }

    @Test
    public void aTrailingSlashIsCollapsedBeforeThePathIsAppended() throws Exception {
        HttpServer server = serve(200, GREEN_RESPONSE);
        try {
            EvalCommand cmd = new EvalCommand();
            cmd.server = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            assertEquals(Integer.valueOf(0), cmd.call());
        } finally {
            server.stop(0);
        }
    }
}
