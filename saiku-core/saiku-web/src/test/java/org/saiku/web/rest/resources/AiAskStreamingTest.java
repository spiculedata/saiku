/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.saiku.web.rest.resources.AiQueryResource.SseWriter;

/**
 * Unit tests for the SSE plumbing on {@code POST /ai/ask/stream} (saiku#1433) — the
 * package-visible helpers {@link AiQueryResource#emitChunks(SseWriter, String)}, {@link
 * AiQueryResource#jsonString(String)}, and {@link SseWriter#event(String, String)}. The full
 * endpoint round-trip is covered downstream by the resource integration test — this file locks
 * the wire format.
 */
public class AiAskStreamingTest {

    @Test
    public void sseWriterEmitsWhatwgFrame() throws IOException {
        StringWriter out = new StringWriter();
        SseWriter sse = new SseWriter(out);
        sse.event("model", "{\"model\":\"claude-sonnet-4-6\"}");
        assertEquals("event: model\ndata: {\"model\":\"claude-sonnet-4-6\"}\n\n", out.toString());
    }

    @Test
    public void sseWriterHandlesMultiLineData() throws IOException {
        // Per the WHATWG SSE spec, multi-line data payloads must have `data: ` prefixed on every
        // line. Our JSON is single-line by convention but the writer must be honest — a future
        // serialiser choosing pretty-print mustn't break the wire format.
        StringWriter out = new StringWriter();
        SseWriter sse = new SseWriter(out);
        sse.event("chunk", "line-one\nline-two\nline-three");
        String expected = "event: chunk\ndata: line-one\ndata: line-two\ndata: line-three\n\n";
        assertEquals(expected, out.toString());
    }

    @Test
    public void jsonStringEscapesRequiredCharacters() {
        assertEquals("\"hello\"", AiQueryResource.jsonString("hello"));
        assertEquals("\"say \\\"hi\\\"\"", AiQueryResource.jsonString("say \"hi\""));
        assertEquals("\"back\\\\slash\"", AiQueryResource.jsonString("back\\slash"));
        assertEquals("\"line\\nbreak\"", AiQueryResource.jsonString("line\nbreak"));
        assertEquals("\"tab\\there\"", AiQueryResource.jsonString("tab\there"));
        assertEquals("\"\\u0001control\"", AiQueryResource.jsonString("control"));
    }

    @Test
    public void emitChunksSplitsOnWordBoundariesAndPreservesWhitespace() throws IOException {
        StringWriter out = new StringWriter();
        SseWriter sse = new SseWriter(out);
        AiQueryResource.emitChunks(sse, "Store sales trended up.");

        List<String> deltas = parseChunkDeltas(out.toString());
        // Each chunk carries a word + the trailing whitespace so `String.join("", deltas)` == input.
        assertEquals("Store sales trended up.", String.join("", deltas));
        // At least three chunks — the exact count depends on whitespace runs; the invariant is
        // that concatenation is lossless.
        assertTrue("expected multiple chunks, got " + deltas.size(), deltas.size() >= 3);
    }

    @Test
    public void emitChunksHandlesLongMarkdown() throws IOException {
        StringWriter out = new StringWriter();
        SseWriter sse = new SseWriter(out);
        String markdown = "The Northeast region led sales at $250K, followed by the Midwest at "
                + "$180K. Product Family 'Food' dominated: 62% of the total. Non-consumables "
                + "trailed at 8%, a 4-point drop week-on-week.";
        AiQueryResource.emitChunks(sse, markdown);

        List<String> deltas = parseChunkDeltas(out.toString());
        assertEquals("concatenated deltas recover the source", markdown, String.join("", deltas));
    }

    @Test
    public void emitChunksHandlesEmptyString() throws IOException {
        StringWriter out = new StringWriter();
        SseWriter sse = new SseWriter(out);
        AiQueryResource.emitChunks(sse, "");
        assertEquals("empty prose emits no events", "", out.toString());
    }

    @Test
    public void emitChunksHandlesEmbeddedQuotes() throws IOException {
        // The insight might contain quoted member names — verify the JSON escaping survives the
        // chunk pipeline so the client's JSON parser doesn't choke.
        StringWriter out = new StringWriter();
        SseWriter sse = new SseWriter(out);
        AiQueryResource.emitChunks(sse, "Region \"Northeast\" up.");

        String frame = out.toString();
        // Every chunk event must contain a well-formed JSON string field.
        assertFalse("frame must not contain unescaped bare quote", frame.contains("\"Northeast\""));
        assertTrue(frame.contains("\\\"Northeast\\\""));
    }

    /** Extract the {@code delta} values from a stream of chunk events for round-trip assertions. */
    private static List<String> parseChunkDeltas(String frame) {
        Pattern p = Pattern.compile("event: chunk\\ndata: \\{\"delta\":\"((?:\\\\.|[^\"\\\\])*)\"\\}");
        Matcher m = p.matcher(frame);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(unescape(m.group(1)));
        }
        return out;
    }

    /** Undo the JSON-string escapes {@link AiQueryResource#jsonString} produces. */
    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                        i += 4;
                    }
                    default -> b.append(n);
                }
                i += 2;
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }
}
