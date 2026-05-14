package org.saiku.web.servlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Serves operator branding overlays from {@code <saiku.home>/branding/} at the URL
 * {@code /ui/branding/*}. If the Saiku home branding directory or requested file does
 * not exist, the servlet responds with 404 so the UI can silently fall back to the
 * built-in defaults.
 *
 * <p>Only a small allow-list of file extensions is served (css, png, jpg, jpeg, svg,
 * ico, webp, gif), and path traversal is blocked by resolving the request path
 * relative to the branding root and rejecting anything that escapes it.</p>
 */
public class BrandingServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Strip leading slash; reject traversal attempts.
        String relative = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        if (relative.isEmpty() || relative.contains("..") || relative.contains("\\")) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String ext = extensionOf(relative);
        String contentType = contentTypeFor(ext);
        if (contentType == null) {
            // Only serve a small, safe allow-list.
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        Path root = brandingRoot();
        if (root == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        Path file = root.resolve(relative).normalize();
        // Ensure resolved path is still inside the branding root.
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        resp.setContentType(contentType);
        resp.setHeader("Cache-Control", "public, max-age=60");
        long length = Files.size(file);
        if (length <= Integer.MAX_VALUE) {
            resp.setContentLength((int) length);
        }
        try (OutputStream out = resp.getOutputStream()) {
            Files.copy(file, out);
        }
    }

    private Path brandingRoot() {
        String home = System.getProperty("saiku.home");
        if (home == null || home.isEmpty()) {
            return null;
        }
        Path root = Paths.get(home, "branding").toAbsolutePath().normalize();
        return Files.isDirectory(root) ? root : null;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String contentTypeFor(String ext) {
        switch (ext) {
            case "css":
                return "text/css; charset=utf-8";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "svg":
                return "image/svg+xml";
            case "ico":
                return "image/x-icon";
            case "webp":
                return "image/webp";
            case "gif":
                return "image/gif";
            default:
                return null;
        }
    }
}
