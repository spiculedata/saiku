package org.saiku.web.svg;

/**
 * @author Tomasz Nurkiewicz
 * @since 1/15/13, 10:29 AM
 */

import java.io.*;

import org.apache.batik.transcoder.*;
import org.apache.batik.transcoder.image.*;
import org.apache.commons.io.*;
import org.apache.fop.svg.*;

public abstract class Converter
{

    abstract void convert(InputStream in, OutputStream out, Integer size) throws IOException, TranscoderException;

    private final String contentType;
    private final String extension;

    protected Converter(String contentType, String extension)
    {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String getContentType()
    {
        return contentType;
    }

    public String getExtension()
    {
        return extension;
    }

    public static Converter byType(String type)
    {
        if (type.equals("SVG")) { return new SvgConverter(); }
        if (type.equals("PNG")) { return new PngConverter(); }
        if (type.equals("TIFF")) { return new TiffConverter(); }
        if (type.equals("JPG")) { return new JpgConverter(); }
        if (type.equals("PDF")) { return new PdfConverter(); }
        return null;
    }
}

class SvgConverter extends Converter
{

    public SvgConverter()
    {
        super("image/svg+xml", "svg");
    }

    void convert(InputStream in, OutputStream out, Integer size) throws IOException
    {
        IOUtils.copy(in, out);
    }

}

abstract class BatikConverter extends Converter
{

    protected BatikConverter(String extension)
    {
        super("image/" + extension, extension);
    }

    protected BatikConverter(String contentType, String extension)
    {
        super(contentType, extension);
    }

    void convert(InputStream in, OutputStream out, Integer size) throws TranscoderException
    {
        Transcoder t = createTranscoder();
        if (size != null)
        {
            final float sizeBound = Math.max(Math.min(size, 2000.0f), 32.0f);
            t.addTranscodingHint(SVGAbstractTranscoder.KEY_WIDTH, sizeBound);
        }
        t.transcode(new TranscoderInput(in), new TranscoderOutput(out));
    }

    protected abstract Transcoder createTranscoder();

}

class PngConverter extends BatikConverter
{

    public PngConverter()
    {
        super("png");
    }

    protected Transcoder createTranscoder() { return new PNGTranscoder();}

}

class JpgConverter extends BatikConverter
{

    public JpgConverter()
    {
        super("image/jpeg", "jpg");
    }

    protected Transcoder createTranscoder()
    {
        Transcoder t = new JPEGTranscoder();
        t.addTranscodingHint(JPEGTranscoder.KEY_QUALITY, 0.95f);
        return t;
    }

}

class TiffConverter extends BatikConverter
{

    public TiffConverter()
    {
        super("tiff");
    }

    protected Transcoder createTranscoder() {return new TIFFTranscoder();}

}

class PdfConverter extends BatikConverter
{

    public PdfConverter()
    {
        super("application/pdf", "pdf");
    }

    protected Transcoder createTranscoder() {return new PDFTranscoder();}

}