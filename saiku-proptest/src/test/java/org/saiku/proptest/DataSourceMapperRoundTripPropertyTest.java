/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.saiku.service.datasource.OssieModelPathGuard;
import org.saiku.web.rest.objects.DataSourceMapper;

/**
 * Round-trip property for the server-side datasource wire mapping — the same {@link
 * DataSourceMapper} whose UI-side field-name mismatch caused the {@code /datasources -> 400} bug
 * (saiku#1529). Here we assert the server contract's own invariant: an Ossie datasource that goes
 * {@code DataSourceMapper -> SaikuDatasource -> DataSourceMapper} comes back with its identifying
 * fields intact, for any generated values.
 *
 * <p>Since saiku#2534, {@code toSaikuDataSource} refuses an {@code ossieYaml} outside the permitted
 * model roots, so "for any generated values" means any values that name a valid datasource: the
 * generated model path is placed inside a temporary permitted root via the guard's operator
 * extra-roots property (set only for the duration of the guarded save).
 */
class DataSourceMapperRoundTripPropertyTest {

    @HegelTest
    void ossieDatasourceSurvivesRoundTrip(TestCase tc) throws IOException {
        String name = tc.draw(fromRegex("[a-zA-Z][a-zA-Z0-9_ -]{0,20}"), "name");
        String host = tc.draw(fromRegex("[a-z][a-z0-9.]{0,15}"), "host");
        String db = tc.draw(fromRegex("[a-z][a-z0-9_]{0,15}"), "db");
        String schema = tc.draw(fromRegex("[a-z][a-z0-9_]{0,15}"), "schema");
        String username = tc.draw(fromRegex("[a-z][a-z0-9_]{0,15}"), "username");

        // saiku#2534: the model path must sit inside a permitted root. Generate a nested path
        // inside a scratch root admitted via the operator extra-roots property.
        Path modelRoot = Files.createTempDirectory("saiku-ossie-proptest");
        modelRoot.toFile().deleteOnExit();
        String leaf = tc.draw(fromRegex("[a-z][a-z0-9/_]{1,20}"), "ossieYamlName");
        String ossieYaml = modelRoot.resolve(leaf + ".yaml").toString();

        DataSourceMapper original = new DataSourceMapper();
        original.setConnectiontype("OSSIE");
        original.setConnectionname(name);
        original.setJdbcurl("jdbc:postgresql://" + host + ":5432/" + db);
        original.setSchema(schema);
        original.setUsername(username);
        original.setOssieYaml(ossieYaml);

        DataSourceMapper roundTripped;
        String previous = System.getProperty(OssieModelPathGuard.EXTRA_ROOTS_PROPERTY);
        try {
            System.setProperty(OssieModelPathGuard.EXTRA_ROOTS_PROPERTY, modelRoot.toString());
            roundTripped = new DataSourceMapper(original.toSaikuDataSource());
        } finally {
            if (previous == null) {
                System.clearProperty(OssieModelPathGuard.EXTRA_ROOTS_PROPERTY);
            } else {
                System.setProperty(OssieModelPathGuard.EXTRA_ROOTS_PROPERTY, previous);
            }
        }

        assertEquals(original.getConnectionname(), roundTripped.getConnectionname(), "connectionname");
        assertEquals(original.getJdbcurl(), roundTripped.getJdbcurl(), "jdbcurl");
        assertEquals(original.getSchema(), roundTripped.getSchema(), "schema");
        assertEquals(original.getUsername(), roundTripped.getUsername(), "username");
        assertEquals(original.getOssieYaml(), roundTripped.getOssieYaml(), "ossieYaml");
    }
}
