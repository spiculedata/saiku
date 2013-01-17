package org.saiku.web.svg;

import java.io.*;

import javax.servlet.*;
import javax.servlet.http.*;

import org.apache.batik.transcoder.*;
import org.slf4j.*;

/**
 * @author Tomasz Nurkiewicz
 * @since 1/15/13, 10:46 AM
 */
public class SvgConverterServlet extends HttpServlet
{

    private static final Logger log = LoggerFactory.getLogger(SvgConverterServlet.class);

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        try
        {
            convert(req, resp);
        }
        catch (TranscoderException e)
        {
            log.error("TranscoderException", e);
            log.error("Nested", e.getException());
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
        catch (Exception e)
        {
            log.error("Exception", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void convert(HttpServletRequest req, HttpServletResponse resp) throws IOException, TranscoderException
    {
        if (req.getParameter("type") == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing 'type' parameter");
            return;
        }
        final String imageType = req.getParameter("type").toUpperCase();
        Converter converter = Converter.byType(imageType);
        if (converter == null)
        {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unrecognized: " + imageType);
            return;
        }
        resp.setContentType(converter.getContentType());
        resp.setHeader("Content-disposition", "attachment; filename=chart." + converter.getExtension());
        final Integer size = req.getParameter("size") != null? Integer.parseInt(req.getParameter("size")) : null;
        final String svgDocument = req.getParameter("svg");
        if (svgDocument == null)
        {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing 'svg' parameter");
            return;
        }
        final InputStream in = new ByteArrayInputStream(svgDocument.getBytes("UTF-8"));
        final OutputStream out = resp.getOutputStream();
        converter.convert(in, out, size);
        out.flush();
    }

}
