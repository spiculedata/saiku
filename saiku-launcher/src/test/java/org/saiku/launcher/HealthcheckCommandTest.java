/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import picocli.CommandLine;

/**
 * saiku#2462 — the container HEALTHCHECK can no longer shell out to curl (the Chainguard JRE has
 * neither a shell nor curl), so the launcher answers it: exit 0 when the info endpoint answers
 * 2xx to a HEAD request, exit 1 for anything else, including nothing listening.
 */
public class HealthcheckCommandTest {

    private HttpServer server;
    private final AtomicReference<String> seenMethod = new AtomicReference<>();
    private volatile int status = 200;

    @Before
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/saiku/info", exchange -> {
            seenMethod.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @After
    public void stopServer() {
        server.stop(0);
    }

    private int run(String... args) {
        return new CommandLine(new SaikuLauncher()).execute(prepend("healthcheck", args));
    }

    private static String[] prepend(String first, String[] rest) {
        String[] all = new String[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/rest/saiku/info";
    }

    @Test
    public void exitsZeroWhenInfoAnswers200AndUsesHead() {
        assertEquals(0, run("--url", url()));
        assertEquals("HEAD", seenMethod.get());
    }

    @Test
    public void exitsOneOnServerError() {
        status = 503;
        assertEquals(1, run("--url", url()));
    }

    @Test
    public void redirectIsNotHealthy() {
        status = 302;
        assertEquals(1, run("--url", url()));
    }

    @Test
    public void exitsOneWhenNothingIsListening() {
        int port = server.getAddress().getPort();
        server.stop(0);
        assertEquals(1, run("--url", "http://127.0.0.1:" + port + "/rest/saiku/info", "--timeout", "1"));
    }

    @Test
    public void defaultsToLocalhost8080Info() {
        assertTrue(HealthcheckCommand.defaultUrl(8080).equals("http://localhost:8080/rest/saiku/info"));
    }
}
