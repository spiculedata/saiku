/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.Properties;
import org.apache.commons.io.IOUtils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.saiku.web.rest.objects.resultset.QueryResult;

public class JSConverter {
    public static String convertToHtml(QueryResult queryResult, boolean wrapcontent) throws IOException {
        StringWriter stringWriter = new StringWriter();
        useJavascriptToConvertToHtml(queryResult, stringWriter);
        String content = stringWriter.toString();
        content = appendSaikuCommercialIfNecessary(content);
        return content;
    }

    private static void useJavascriptToConvertToHtml(QueryResult queryResult, StringWriter stringWriter)
            throws IOException {
        Context javascriptContext = createJavascriptContext();
        Scriptable globalScope = javascriptContext.initStandardObjects();
        loadJavascriptScripts(javascriptContext, globalScope);
        loadDataIntoJsContext(queryResult, globalScope);
        loadStringWriterIntoJsContext(stringWriter, globalScope);
        executeJavascript(javascriptContext, globalScope);
        Context.exit();
    }

    private static void executeJavascript(Context javascriptContext, Scriptable globalScope) {
        String code = "eval('var cellset = ' + data); \n" + "var renderer = new SaikuTableRenderer(); \n"
                + "var html = renderer.render(cellset, { wrapContent : "
                + false + " }); out.write(html);";
        javascriptContext.evaluateString(globalScope, code, "<mem>", 1, null);
    }

    private static void loadStringWriterIntoJsContext(StringWriter stringWriter, Scriptable globalScope) {
        // load StringWriter into JS environment so JS has a place to write to
        Object wrappedOut = Context.javaToJS(stringWriter, globalScope);
        ScriptableObject.putProperty(globalScope, "out", wrappedOut);
    }

    private static void loadDataIntoJsContext(QueryResult queryResult, Scriptable globalScope)
            throws JsonProcessingException {
        // load data of queryResult into JS environment
        ObjectMapper objectMapper = new ObjectMapper();
        String data = objectMapper.writeValueAsString(queryResult);
        Object wrappedQueryResult = Context.javaToJS(data, globalScope);
        ScriptableObject.putProperty(globalScope, "data", wrappedQueryResult);
    }

    private static Context createJavascriptContext() {
        Context javascriptContext = Context.enter();
        javascriptContext.setOptimizationLevel(-1);
        javascriptContext.setLanguageVersion(Context.VERSION_1_5);
        return javascriptContext;
    }

    private static void loadJavascriptScripts(Context javascriptContext, Scriptable globalScope) throws IOException {
        // fetch the .js files and put them on scope of the javascriptContext to be executed.
        // Each resource stream is closed via try-with-resources so a failed/partial export
        // can't leak the underlying classpath InputStreams (saiku#1191).
        try (Reader underscoreReader = new InputStreamReader(JSConverter.class.getResourceAsStream("underscore.js"))) {
            javascriptContext.evaluateReader(globalScope, underscoreReader, "underscore.js", 1, null);
        }
        try (Reader saikuRendererReader =
                new InputStreamReader(JSConverter.class.getResourceAsStream("SaikuRenderer.js"))) {
            javascriptContext.evaluateReader(globalScope, saikuRendererReader, "SaikuRenderer.js", 1, null);
        }
        try (InputStream tableRendererStream = JSConverter.class.getResourceAsStream("SaikuTableRenderer.js")) {
            String result = IOUtils.toString(tableRendererStream);
            javascriptContext.evaluateString(globalScope, result, "SaikuTableRenderer.js", 1, null);
        }
    }

    private static String appendSaikuCommercialIfNecessary(String content) {
        // The "Export Provided By Saiku Analytics Community Edition
        // (http://meteorite.bi)" footer was dropped 2026-06-08 (user
        // feedback: stale branding + dead URL). The method name + call
        // are kept so existing entrypoints don't need rewriting; it now
        // just normalises non-breaking spaces.
        content = content.replaceAll("&nbsp;", " ");
        content = content.replaceAll("&nbsp", " ");
        return content;
    }

    public static String convertToHtml(QueryResult qr) throws IOException {
        return convertToHtml(qr, false);
    }

    private static String getVersion() {
        Properties prop = new Properties();
        String version = "";
        ClassLoader classloader = Thread.currentThread().getContextClassLoader();
        // try-with-resources so the version.properties stream is always closed (saiku#1191).
        try (InputStream is = classloader.getResourceAsStream("org/saiku/web/rest/resources/version.properties")) {
            prop.load(is);
            version = prop.getProperty("VERSION");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return version;
    }
}
