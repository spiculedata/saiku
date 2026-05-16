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

package org.saiku.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read and return log files for the admin "Logs" panel.
 *
 * <p>The SPA passes a log name like {@code saiku} or {@code mondrian_sql}; the
 * {@code .log} suffix is appended here so the front-end stays consistent with
 * how it labels files in the dropdown. Path-traversal attempts are rejected
 * up-front (saiku#780-style containment).
 *
 * <p>Returns {@code null} when the resolved path does not exist so the resource
 * layer can surface a clean 404 envelope rather than the opaque 500 the
 * previous {@code FileUtils.readFileToString} call produced on missing files.
 */
public class LogExtractor {

    private static final Logger log = LoggerFactory.getLogger(LogExtractor.class);

    private String logdirectory;

    /**
     * @param name log name without extension (e.g. {@code saiku},
     *             {@code mondrian_sql}). {@code .log} is appended if not
     *             already present.
     * @return file contents as a UTF-8 string, or {@code null} when the file
     *         does not exist.
     * @throws IOException for genuine I/O failures (permissions, FS errors);
     *                     missing files are NOT errors.
     */
    public String readLog(String name) throws IOException {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IOException("Cannot display file outside of log folder: " + name);
        }
        String filename = name.endsWith(".log") ? name : name + ".log";
        Path base = Paths.get(logdirectory == null ? "logs" : logdirectory)
                .toAbsolutePath()
                .normalize();
        Path target = base.resolve(filename).normalize();
        // Defence-in-depth: ensure the resolved path is still under base.
        if (!target.startsWith(base)) {
            throw new IOException("Cannot display file outside of log folder: " + name);
        }
        if (!Files.isRegularFile(target)) {
            log.debug("Log file does not exist: {}", target);
            return null;
        }
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    public String getLogdirectory() {
        return logdirectory;
    }

    public void setLogdirectory(String logdirectory) {
        this.logdirectory = logdirectory;
    }
}
