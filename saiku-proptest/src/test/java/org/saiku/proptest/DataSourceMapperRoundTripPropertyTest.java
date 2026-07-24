/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.saiku.web.rest.objects.DataSourceMapper;

/**
 * Round-trip property for the server-side datasource wire mapping — the same {@link
 * DataSourceMapper} whose UI-side field-name mismatch caused the {@code /datasources -> 400} bug
 * (saiku#1529). Here we assert the server contract's own invariant: an Ossie datasource that goes
 * {@code DataSourceMapper -> SaikuDatasource -> DataSourceMapper} comes back with its identifying
 * fields intact, for any generated values.
 */
class DataSourceMapperRoundTripPropertyTest {

    @HegelTest
    void ossieDatasourceSurvivesRoundTrip(TestCase tc) {
        String name = tc.draw(fromRegex("[a-zA-Z][a-zA-Z0-9_ -]{0,20}"), "name");
        String host = tc.draw(fromRegex("[a-z][a-z0-9.]{0,15}"), "host");
        String db = tc.draw(fromRegex("[a-z][a-z0-9_]{0,15}"), "db");
        String schema = tc.draw(fromRegex("[a-z][a-z0-9_]{0,15}"), "schema");
        String username = tc.draw(fromRegex("[a-z][a-z0-9_]{0,15}"), "username");
        String ossieYaml = tc.draw(fromRegex("/[a-z][a-z0-9/_]{1,20}\\.yaml"), "ossieYaml");

        DataSourceMapper original = new DataSourceMapper();
        original.setConnectiontype("OSSIE");
        original.setConnectionname(name);
        original.setJdbcurl("jdbc:postgresql://" + host + ":5432/" + db);
        original.setSchema(schema);
        original.setUsername(username);
        original.setOssieYaml(ossieYaml);

        DataSourceMapper roundTripped = new DataSourceMapper(original.toSaikuDataSource());

        assertEquals(original.getConnectionname(), roundTripped.getConnectionname(), "connectionname");
        assertEquals(original.getJdbcurl(), roundTripped.getJdbcurl(), "jdbcurl");
        assertEquals(original.getSchema(), roundTripped.getSchema(), "schema");
        assertEquals(original.getUsername(), roundTripped.getUsername(), "username");
        assertEquals(original.getOssieYaml(), roundTripped.getOssieYaml(), "ossieYaml");
    }
}
