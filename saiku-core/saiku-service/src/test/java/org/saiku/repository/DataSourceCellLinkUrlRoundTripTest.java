/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Properties;
import org.junit.Test;
import org.saiku.datasources.datasource.SaikuDatasource;

/**
 * {@code <cellLinkUrl>} on a {@code .sds} must survive marshal/unmarshal so an admin-authored
 * fallback template is not dropped on reload.
 */
public class DataSourceCellLinkUrlRoundTripTest {

    @Test
    public void marshalUnmarshalPreservesCellLinkUrl() throws Exception {
        Properties props = new Properties();
        props.setProperty("location", "jdbc:mondrian:Jdbc=jdbc:h2:mem:x;Catalog=mondrian://ops");
        props.setProperty("username", "sa");
        props.setProperty("id", "ds-1");
        props.setProperty("cellLinkUrl", "https://erp.ejemplo/ops?barrio={Barrio}");
        SaikuDatasource ds = new SaikuDatasource("inmobiliaria", SaikuDatasource.Type.OLAP, props);
        DataSource src = new DataSource(ds);

        JAXBContext ctx = JAXBContext.newInstance(DataSource.class);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Marshaller m = ctx.createMarshaller();
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        m.marshal(src, buf);
        String xml = buf.toString();

        assertTrue(xml.contains("<cellLinkUrl>https://erp.ejemplo/ops?barrio={Barrio}</cellLinkUrl>"));

        Unmarshaller u = ctx.createUnmarshaller();
        DataSource loaded = (DataSource) u.unmarshal(new ByteArrayInputStream(xml.getBytes()));
        assertEquals("inmobiliaria", loaded.getName());
        assertEquals("https://erp.ejemplo/ops?barrio={Barrio}", loaded.getCellLinkUrl());
    }
}
