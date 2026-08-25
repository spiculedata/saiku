/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.semantic.mondrian;

import java.io.StringWriter;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.saiku.semantic.ir.CubeDefinition;
import org.saiku.semantic.ir.Dimension;
import org.saiku.semantic.ir.Level;
import org.saiku.semantic.ir.Measure;

/**
 * Deterministic IR → Mondrian XML compiler. Given the same {@link CubeDefinition},
 * byte-identical output every time — enables golden-file tests.
 */
public final class MondrianCompiler {

    private static final XMLOutputFactory FACTORY = XMLOutputFactory.newFactory();

    public String compile(CubeDefinition cube) {
        StringWriter buf = new StringWriter();
        try {
            XMLStreamWriter out = FACTORY.createXMLStreamWriter(buf);
            out.writeStartDocument("UTF-8", "1.0");
            writeSchema(out, cube);
            out.writeEndDocument();
            out.flush();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Mondrian XML generation failed for cube '" + cube.name() + "'", e);
        }
        return buf.toString();
    }

    private void writeSchema(XMLStreamWriter out, CubeDefinition cube) throws XMLStreamException {
        out.writeStartElement("Schema");
        out.writeAttribute("name", cube.name());

        out.writeStartElement("Cube");
        out.writeAttribute("name", cube.name());
        out.writeAttribute("visible", "true");

        writeFact(out, cube);
        for (Dimension d : cube.dimensions()) {
            writeDimension(out, d);
        }
        for (Measure m : cube.measures()) {
            writeMeasure(out, m);
        }

        out.writeEndElement(); // Cube
        out.writeEndElement(); // Schema
    }

    private void writeFact(XMLStreamWriter out, CubeDefinition cube) throws XMLStreamException {
        out.writeStartElement("Table");
        out.writeAttribute("name", cube.fact().table());
        if (cube.fact().schema() != null && !cube.fact().schema().isBlank()) {
            out.writeAttribute("schema", cube.fact().schema());
        }
        out.writeEndElement();
    }

    private void writeDimension(XMLStreamWriter out, Dimension d) throws XMLStreamException {
        out.writeStartElement("Dimension");
        out.writeAttribute("name", d.name());
        if (d.foreignKey() != null) {
            out.writeAttribute("foreignKey", d.foreignKey());
        }

        out.writeStartElement("Hierarchy");
        out.writeAttribute("hasAll", "true");
        if (d.primaryKey() != null) {
            out.writeAttribute("primaryKey", d.primaryKey());
        }

        if (d.table() != null) {
            out.writeStartElement("Table");
            out.writeAttribute("name", d.table());
            out.writeEndElement();
        }

        for (Level l : d.levels()) {
            writeLevel(out, l);
        }

        out.writeEndElement(); // Hierarchy
        out.writeEndElement(); // Dimension
    }

    private void writeLevel(XMLStreamWriter out, Level l) throws XMLStreamException {
        out.writeStartElement("Level");
        out.writeAttribute("name", l.name());
        out.writeAttribute("column", l.column());
        if (l.type() != null) {
            out.writeAttribute("type", l.type());
        }
        if (l.nameColumn() != null) {
            out.writeAttribute("nameColumn", l.nameColumn());
        }
        if (l.ordinalColumn() != null) {
            out.writeAttribute("ordinalColumn", l.ordinalColumn());
        }
        out.writeAttribute("uniqueMembers", "false");
        out.writeEndElement();
    }

    private void writeMeasure(XMLStreamWriter out, Measure m) throws XMLStreamException {
        out.writeStartElement("Measure");
        out.writeAttribute("name", m.name());
        out.writeAttribute("column", m.column());
        out.writeAttribute("aggregator", m.aggregator());
        out.writeAttribute("datatype", m.datatype());
        if (m.formatString() != null) {
            out.writeAttribute("formatString", m.formatString());
        }
        out.writeEndElement();
    }
}
