/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.semantic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.saiku.service.ossie.OssieModelDto;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Unit tests for {@link TableauTdsExporter} — structural assertions against the emitted DOM. */
public class TableauTdsExporterTest {

    @Test
    public void emitsWellFormedXmlWithDatasetsFieldsAndMetrics() throws Exception {
        OssieModelDto model = fixture();
        SemanticExportResult result = new TableauTdsExporter().export(model);

        assertEquals("pharma.tds", result.getFilename());
        assertEquals("application/xml", result.getContentType());

        Document doc = parse(result.getContent());
        Element root = doc.getDocumentElement();
        assertEquals("datasource", root.getTagName());
        assertEquals("Pharma", root.getAttribute("formatted-name"));

        // One <relation type="table"> per dataset (no relationships declared in this fixture).
        NodeList relations = doc.getElementsByTagName("relation");
        assertEquals(2, relations.getLength());

        // Field columns use the [dataset].[field] name.
        boolean sawRegion = false;
        boolean sawMetric = false;
        NodeList columns = doc.getElementsByTagName("column");
        for (int i = 0; i < columns.getLength(); i++) {
            Element col = (Element) columns.item(i);
            if ("[customers].[REGION]".equals(col.getAttribute("name"))) sawRegion = true;
            if ("[net_revenue]".equals(col.getAttribute("name"))) {
                sawMetric = true;
                assertEquals("measure", col.getAttribute("role"));
                Element calc = (Element) col.getElementsByTagName("calculation").item(0);
                assertEquals("SUM([fact_pharma].[NETREVENUE])", calc.getAttribute("formula"));
            }
        }
        assertTrue("expected a customers.REGION column", sawRegion);
        assertTrue("expected a net_revenue calculated field", sawMetric);
    }

    @Test
    public void joinsRelatedDatasets() throws Exception {
        OssieModelDto model = fixture();
        OssieModelDto.Relationship rel = new OssieModelDto.Relationship();
        rel.setName("fact_to_customer");
        rel.setFrom("fact_pharma");
        rel.setTo("customers");
        rel.setFromColumns(java.util.List.of("CUSTOMER_ID"));
        rel.setToColumns(java.util.List.of("ID"));
        model.getRelationships().add(rel);

        TableauTdsExporter exporter = new TableauTdsExporter();
        SemanticExportResult result = exporter.export(model);
        assertTrue(exporter.getSkippedRelationships().isEmpty());

        Document doc = parse(result.getContent());
        NodeList joins = doc.getElementsByTagName("relation");
        // The top-level connection element should now hold exactly one join relation (no
        // independent sibling tables left over, since both datasets are in the join).
        boolean sawJoin = false;
        for (int i = 0; i < joins.getLength(); i++) {
            Element r = (Element) joins.item(i);
            if ("join".equals(r.getAttribute("type"))) sawJoin = true;
        }
        assertTrue(sawJoin);
    }

    @Test
    public void skipsRelationshipReferencingUnknownDataset() throws Exception {
        OssieModelDto model = fixture();
        OssieModelDto.Relationship bad = new OssieModelDto.Relationship();
        bad.setName("bogus");
        bad.setFrom("fact_pharma");
        bad.setTo("does_not_exist");
        model.getRelationships().add(bad);

        TableauTdsExporter exporter = new TableauTdsExporter();
        exporter.export(model);
        assertEquals(1, exporter.getSkippedRelationships().size());
    }

    @Test
    public void rejectsModelWithNoDatasets() {
        OssieModelDto empty = new OssieModelDto();
        empty.setName("Empty");
        assertThrows(SemanticExportException.class, () -> new TableauTdsExporter().export(empty));
    }

    @Test
    public void hiddenFieldGetsHiddenAttribute() throws Exception {
        OssieModelDto model = fixture();
        model.getDatasets().get(1).getFields().get(0).setDisplayHidden(true);
        Document doc = parse(new TableauTdsExporter().export(model).getContent());
        NodeList columns = doc.getElementsByTagName("column");
        boolean sawHidden = false;
        for (int i = 0; i < columns.getLength(); i++) {
            Element col = (Element) columns.item(i);
            if ("[customers].[REGION]".equals(col.getAttribute("name"))) {
                assertEquals("true", col.getAttribute("hidden"));
                sawHidden = true;
            }
        }
        assertTrue(sawHidden);
    }

    private Document parse(byte[] xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private OssieModelDto fixture() {
        OssieModelDto model = new OssieModelDto();
        model.setName("Pharma");
        model.setConnection("Pharma");

        OssieModelDto.Dataset fact = new OssieModelDto.Dataset();
        fact.setName("fact_pharma");
        fact.setSource("FACT_PHARMA");
        OssieModelDto.Field netrev = new OssieModelDto.Field();
        netrev.setName("NETREVENUE");
        fact.getFields().add(netrev);
        model.getDatasets().add(fact);

        OssieModelDto.Dataset customers = new OssieModelDto.Dataset();
        customers.setName("customers");
        customers.setSource("DIM_CUSTOMERS");
        OssieModelDto.Field region = new OssieModelDto.Field();
        region.setName("REGION");
        customers.getFields().add(region);
        model.getDatasets().add(customers);

        OssieModelDto.Metric netRev = new OssieModelDto.Metric();
        netRev.setName("net_revenue");
        netRev.setExpression("SUM(\"fact_pharma\".\"NETREVENUE\")");
        model.getMetrics().add(netRev);
        return model;
    }
}
