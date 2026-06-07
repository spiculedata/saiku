package org.saiku.web.svg;

/**
 * @author Tomasz Nurkiewicz
 * @since 1/15/13, 10:29 AM
 */
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.batik.transcoder.*;
import org.apache.batik.transcoder.image.JPEGTranscoder;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.transcoder.image.TIFFTranscoder;
import org.apache.commons.io.IOUtils;
import org.apache.fop.svg.PDFTranscoder;

public abstract class Converter {

    public abstract void convert(InputStream in, OutputStream out, Integer size)
            throws IOException, TranscoderException;

    private final String contentType;
    private final String extension;

    Converter(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String getContentType() {
        return contentType;
    }

    public String getExtension() {
        return extension;
    }

    public static Converter byType(String type) {
        if (type.equals("SVG")) {
            return new SvgConverter();
        }
        if (type.equals("PNG")) {
            return new PngConverter();
        }
        if (type.equals("TIFF")) {
            return new TiffConverter();
        }
        if (type.equals("JPG")) {
            return new JpgConverter();
        }
        if (type.equals("PDF")) {
            return new PdfConverter();
        }
        return null;
    }
}

class SvgConverter extends Converter {

    public SvgConverter() {
        super("image/svg+xml", "svg");
    }

    public void convert(InputStream in, OutputStream out, Integer size) throws IOException {
        IOUtils.copy(in, out);
    }
}

abstract class BatikConverter extends Converter {

    BatikConverter(String extension) {
        super("image/" + extension, extension);
    }

    BatikConverter(String contentType, String extension) {
        super(contentType, extension);
    }

    /** saiku#1165: cap the in-memory SVG so a giant payload can't OOM the server. */
    private static final int MAX_SVG_BYTES = 20 * 1024 * 1024;

    public void convert(InputStream in, OutputStream out, Integer size) throws TranscoderException, IOException {
        // saiku#1165: read the SVG, reject external references / DOCTYPE (SSRF /
        // local-file-read / XXE via Batik), then transcode from the validated copy.
        byte[] svg = in.readNBytes(MAX_SVG_BYTES + 1);
        if (svg.length > MAX_SVG_BYTES) {
            throw new TranscoderException("SVG exceeds the " + MAX_SVG_BYTES + "-byte export limit");
        }
        SvgSecurity.requireSafe(new String(svg, StandardCharsets.UTF_8));

        Transcoder t = createTranscoder();
        // Defense-in-depth: never run on-load scripts during transcode.
        t.addTranscodingHint(SVGAbstractTranscoder.KEY_EXECUTE_ONLOAD, Boolean.FALSE);
        if (size != null) {
            final float sizeBound = Math.max(Math.min(size, 2000.0f), 32.0f);
            t.addTranscodingHint(SVGAbstractTranscoder.KEY_WIDTH, sizeBound);
        }
        t.transcode(new TranscoderInput(new ByteArrayInputStream(svg)), new TranscoderOutput(out));
    }

    protected abstract Transcoder createTranscoder();
}

class PngConverter extends BatikConverter {

    public PngConverter() {
        super("png");
    }

    protected Transcoder createTranscoder() {
        return new PNGTranscoder();
    }
}

class JpgConverter extends BatikConverter {

    public JpgConverter() {
        super("image/jpeg", "jpg");
    }

    protected Transcoder createTranscoder() {
        Transcoder t = new JPEGTranscoder();
        t.addTranscodingHint(JPEGTranscoder.KEY_QUALITY, 0.95f);
        return t;
    }
}

class TiffConverter extends BatikConverter {

    public TiffConverter() {
        super("tiff");
    }

    protected Transcoder createTranscoder() {
        return new TIFFTranscoder();
    }
}

class PdfConverter extends BatikConverter {

    public PdfConverter() {
        super("application/pdf", "pdf");
    }

    protected Transcoder createTranscoder() {
        return new PDFTranscoder();
    }
}
