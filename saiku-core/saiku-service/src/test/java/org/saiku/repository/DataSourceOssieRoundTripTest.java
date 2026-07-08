/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Properties;
import org.junit.Test;
import org.saiku.datasources.datasource.SaikuDatasource;

/**
 * Verifies the JAXB round-trip for OSSIE-typed {@link DataSource} DTOs — the on-disk
 * representation used for {@code .sds} files by
 * {@link org.saiku.repository.FilesystemRepositoryManager#saveDataSource}. If the ossieYaml
 * field doesn't survive marshal/unmarshal, an admin-added Ossie datasource silently loses
 * its YAML path after a server restart — this test catches that regression at unit-test
 * speed instead of on the next reload.
 */
public class DataSourceOssieRoundTripTest {

    @Test
    public void marshalUnmarshalPreservesOssieYaml() throws Exception {
        Properties props = new Properties();
        props.setProperty("ossieYaml", "/opt/saiku/pharma.ossie.yaml");
        props.setProperty("location", "jdbc:postgresql://localhost:5432/warehouse");
        props.setProperty("schema", "SALES");
        props.setProperty("username", "admin");
        props.setProperty("password", "hidden");
        props.setProperty("id", "ossie-1");
        SaikuDatasource ds = new SaikuDatasource("SALES", SaikuDatasource.Type.OSSIE, props);
        DataSource src = new DataSource(ds);

        JAXBContext ctx = JAXBContext.newInstance(DataSource.class);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Marshaller m = ctx.createMarshaller();
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        m.marshal(src, buf);
        String xml = buf.toString();

        // The marshalled XML must include the ossieYaml element so future reads pick it up.
        assertNotNull(xml);
        assert xml.contains("<ossieYaml>/opt/saiku/pharma.ossie.yaml</ossieYaml>");
        assert xml.contains("<type>OSSIE</type>");

        Unmarshaller u = ctx.createUnmarshaller();
        DataSource loaded = (DataSource) u.unmarshal(new ByteArrayInputStream(xml.getBytes()));
        assertEquals("SALES", loaded.getName());
        assertEquals("OSSIE", loaded.getType());
        assertEquals("/opt/saiku/pharma.ossie.yaml", loaded.getOssieYaml());
        assertEquals("jdbc:postgresql://localhost:5432/warehouse", loaded.getLocation());
        assertEquals("SALES", loaded.getSchema());
        assertEquals("admin", loaded.getUsername());
        assertEquals("hidden", loaded.getPassword());
        assertEquals("ossie-1", loaded.getId());
    }
}
