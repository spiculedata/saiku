/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Properties;
import org.junit.Test;
import org.saiku.datasources.datasource.SaikuDatasource;

/**
 * saiku#2340: the advanced/csv blobs embed the whole property set, including {@code password=}. The
 * #1165 fix only made the discrete {@code password} property write-only, so the blobs must not
 * carry the line out in any response, while the inbound blob still parses.
 */
public class DataSourceMapperBlobRedactionTest {

    private static final String SECRET = "S3cretW4rehouse!";
    private final ObjectMapper mapper = new ObjectMapper();

    private static SaikuDatasource ds(String mode) {
        Properties props = new Properties();
        props.setProperty("driver", "org.postgresql.Driver");
        props.setProperty("location", "jdbc:postgresql://db/warehouse");
        props.setProperty("username", "saiku_ro");
        props.setProperty("password", SECRET);
        props.setProperty("security.enabled", "true");
        props.setProperty(mode, "true");
        return new SaikuDatasource("warehouse", SaikuDatasource.Type.OLAP, props);
    }

    @Test
    public void advancedBlobDoesNotCarryThePassword() throws Exception {
        String json = mapper.writeValueAsString(new DataSourceMapper(ds("advanced")));

        assertFalse("secret leaked: " + json, json.contains(SECRET));
        assertFalse("password line leaked: " + json, json.contains("password="));
        assertTrue("other lines kept", json.contains("username=saiku_ro"));
        assertTrue("other lines kept", json.contains("security.enabled=true"));
    }

    @Test
    public void csvBlobDoesNotCarryThePassword() throws Exception {
        String json = mapper.writeValueAsString(new DataSourceMapper(ds("csv")));

        assertFalse("secret leaked: " + json, json.contains(SECRET));
        assertFalse("password line leaked: " + json, json.contains("password="));
        assertTrue("other lines kept", json.contains("username=saiku_ro"));
    }

    @Test
    public void echoedInboundBlobIsRedactedToo() throws Exception {
        DataSourceMapper inbound = new DataSourceMapper();
        inbound.setAdvanced("type=OLAP\nname=w\ndriver=d\nlocation=jdbc:h2:mem:x\npassword=" + SECRET + "\n");

        String json = mapper.writeValueAsString(inbound);

        assertFalse("secret leaked: " + json, json.contains(SECRET));
    }

    @Test
    public void inboundBlobPasswordIsStillParsed() {
        DataSourceMapper inbound = new DataSourceMapper();
        inbound.setAdvanced("type=OLAP\nname=w\ndriver=org.h2.Driver\nlocation=jdbc:h2:mem:x\nusername=u\npassword="
                + SECRET + "\n");

        SaikuDatasource out = inbound.toSaikuDataSource();

        assertEquals(SECRET, out.getProperties().getProperty("password"));
    }

    @Test
    public void nullBlobsStayNull() {
        DataSourceMapper m = new DataSourceMapper();

        assertNull(m.getAdvanced());
        assertNull(m.getCsv());
    }
}
