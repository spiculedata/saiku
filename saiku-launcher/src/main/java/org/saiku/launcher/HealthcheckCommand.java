/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Container liveness probe: {@code HEALTHCHECK CMD ["java", "-jar", "/app/saiku.jar", "healthcheck"]}.
 *
 * <p>saiku#2462: replaces {@code curl --head}, which the shell-less Chainguard JRE image does
 * not carry. HEAD on the anonymous {@code /rest/saiku/info} (saiku#866 added the HEAD handler
 * for monitors; a GET walks the plugin directory). Exit 0 on 2xx, 1 on anything else.
 */
@Command(
        name = "healthcheck",
        description = "Probe a running Saiku (exit 0 healthy, 1 not). Used by the container HEALTHCHECK.")
public class HealthcheckCommand implements Callable<Integer> {

    static final int DEFAULT_PORT = 8080;
    static final int DEFAULT_TIMEOUT_SECONDS = 4;

    @Option(names = "--url", description = "URL to probe (default http://localhost:8080/rest/saiku/info).")
    String url;

    @Option(
            names = "--timeout",
            description = "Seconds to wait for an answer (default ${DEFAULT-VALUE}).",
            defaultValue = "" + DEFAULT_TIMEOUT_SECONDS)
    int timeoutSeconds;

    static String defaultUrl(int port) {
        return "http://localhost:" + port + "/rest/saiku/info";
    }

    @Override
    public Integer call() {
        String target = url != null ? url : defaultUrl(DEFAULT_PORT);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            int status =
                    client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                return 0;
            }
            System.err.println("healthcheck: " + target + " answered HTTP " + status);
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("healthcheck: interrupted");
            return 1;
        } catch (Exception e) {
            System.err.println("healthcheck: " + target + " unreachable: " + e);
            return 1;
        }
    }
}
