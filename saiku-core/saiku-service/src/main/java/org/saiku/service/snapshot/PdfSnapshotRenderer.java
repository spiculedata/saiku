/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.snapshot;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Lays a resolved {@link Snapshot} out as a paginated PDF with OpenPDF (saiku#1810) — the attachment
 * format for scheduled subscriptions (#943).
 *
 * <p><b>No browser.</b> OpenPDF is a pure-Java writer; the artifact is produced with no DOM, no
 * JavaScript runtime and no display attached, which is the whole point of the issue. Font metrics come
 * from OpenPDF's built-in Helvetica (a standard-14 PDF font), so the render is deterministic across
 * hosts and needs no font files staged into the distribution.
 *
 * <p>Layout is deliberately plain — a heading, an optional generated-at line, and a two-column
 * label/value table — because the renderer is a distribution primitive, not a pixel-accurate
 * reproduction of the SvelteKit dashboard. It composes the same owner-scoped values the interactive
 * dashboard would show.
 *
 * <p>All text goes through OpenPDF's paragraph API, which escapes/encodes it; a label containing
 * angle brackets or a parenthesis is data, never markup.
 */
public final class PdfSnapshotRenderer implements SnapshotRenderer {

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
    private static final Font META_FONT = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(0x66, 0x66, 0x66));
    private static final Font HEADER_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
    private static final Font CELL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 11);
    private static final Font LABEL_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);

    /** Bounded so a runaway label can never balloon the artifact; the service caps bytes too. */
    private static final int MAX_CELL_CHARS = 512;

    private static final Color RULE = new Color(0xD0, 0xD0, 0xD0);

    @Override
    public SnapshotFormat format() {
        return SnapshotFormat.PDF;
    }

    @Override
    public byte[] render(Snapshot snapshot) {
        if (snapshot == null) {
            throw new SnapshotRenderException("snapshot is required");
        }
        Document document = new Document(PageSize.A4, 36, 36, 42, 42);
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            document.add(title(snapshot.title()));
            document.add(meta(snapshot));
            document.add(table(snapshot.rows()));
            document.close();
        } catch (DocumentException e) {
            throw new SnapshotRenderException("snapshot PDF could not be composed", e);
        }
        byte[] bytes = out.toByteArray();
        if (bytes.length == 0) {
            throw new SnapshotRenderException("snapshot PDF encoder produced no output");
        }
        return bytes;
    }

    private static Paragraph title(String title) {
        String text = (title == null || title.isBlank()) ? "Dashboard snapshot" : truncate(title);
        Paragraph p = new Paragraph(text, TITLE_FONT);
        p.setSpacingAfter(2f);
        return p;
    }

    private static Paragraph meta(Snapshot snapshot) {
        Paragraph p = new Paragraph(
                "Generated " + java.time.Instant.ofEpochMilli(System.currentTimeMillis()) + " UTC by Saiku", META_FONT);
        p.setSpacingAfter(10f);
        return p;
    }

    private static PdfPTable table(List<SnapshotRenderer.Snapshot.Row> rows) {
        PdfPTable table = new PdfPTable(new float[] {3f, 2f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(6f);
        table.setWidths(new float[] {3f, 2f});

        PdfPCell labelHeader = headerCell("Measure");
        labelHeader.setHorizontalAlignment(Element.ALIGN_LEFT);
        PdfPCell valueHeader = headerCell("Value");
        valueHeader.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(labelHeader);
        table.addCell(valueHeader);

        for (SnapshotRenderer.Snapshot.Row row : rows) {
            table.addCell(bodyCell(row.label(), LABEL_FONT, Element.ALIGN_LEFT));
            table.addCell(bodyCell(row.value(), CELL_FONT, Element.ALIGN_RIGHT));
        }
        return table;
    }

    private static PdfPCell headerCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, HEADER_FONT));
        cell.setPadding(6f);
        cell.setBorderWidth(0f);
        cell.setBorderWidthBottom(0.75f);
        cell.setBorderColor(RULE);
        return cell;
    }

    private static PdfPCell bodyCell(String text, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(truncate(text == null ? "" : text), font));
        cell.setPadding(6f);
        cell.setHorizontalAlignment(alignment);
        cell.setBorderWidth(0f);
        cell.setBorderWidthBottom(0.5f);
        cell.setBorderColor(RULE);
        return cell;
    }

    private static String truncate(String s) {
        return s.length() <= MAX_CELL_CHARS ? s : s.substring(0, MAX_CELL_CHARS - 1) + "…";
    }
}
