/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Pulls human-readable prose out of a JSON document that is still being <em>streamed</em> in
 * fragments (saiku#1484).
 *
 * <p>Every provider here forces the model to answer through a tool, so "streaming the answer" means
 * "streaming a JSON string value inside a tool input, as its characters arrive": Anthropic sends
 * {@code input_json_delta.partial_json}, OpenAI sends {@code tool_calls[].function.arguments}, and
 * both are raw JSON fragments that concatenate to the tool's input object. Forwarding those
 * fragments verbatim would put {@code {"markdown":"Q4 revenue} in front of a user, so this class
 * decodes them and emits only the prose <em>values</em> — the ones a human reads.
 *
 * <p>Design notes:
 *
 * <ul>
 *   <li>The whole accumulated buffer is re-scanned on every fragment. That is O(n²) in the worst
 *       case, but n is a few kilobytes of markdown and fragments arrive per model token, so the
 *       real cost is negligible — and it buys a scanner with no incremental state machine to get
 *       wrong.
 *   <li>Only the keys in {@link #PROSE_KEYS} stream. A structured payload (the AiQueryRequest of
 *       {@code emit_query}, the tile list of {@code emit_dashboard}) has nothing to show while it
 *       is half-built, so it produces no deltas at all.
 *   <li>Escapes are decoded, so {@code \n}, {@code \"}, {@code \\} and {@code é} arrive as the
 *       characters they mean, and a fragment boundary that splits an escape sequence produces no
 *       junk: an unterminated trailing escape is simply not decoded yet and arrives with the next
 *       fragment.
 * </ul>
 *
 * <p>Package-private: an implementation detail of the streaming provider decoders, not an SPI.
 */
final class ProseDeltaScanner {

    /**
     * The tool-input properties whose values are prose. Chosen to cover every insight-shaped tool:
     * {@code emit_insight}'s markdown, {@code emit_email_draft}'s summary and {@code
     * emit_view_change}'s reason.
     *
     * <p>Deliberately excludes {@code emit_insight}'s {@code headline}: it is a one-line label the
     * client renders in its own slot from the terminal envelope, and streaming it inline would
     * splice it into the body text the chunks build. That is also exactly what the pre-streaming
     * replay streamed, so the two paths stay interchangeable.
     */
    private static final Set<String> PROSE_KEYS = Set.of("markdown", "summary", "reason");

    /** Accumulated raw JSON fragments, in arrival order. */
    private final StringBuilder raw = new StringBuilder();

    /**
     * Per-key watermark: how many decoded characters of that key's value have already been handed
     * out. A {@link LinkedHashMap} only so the diagnostic {@link #toString()} is stable.
     */
    private final Map<String, Integer> emitted = new LinkedHashMap<>();

    /**
     * Append one raw JSON fragment and return the prose it completed — the newly-decoded tail of
     * whichever prose value is currently open. Returns {@code ""} when the fragment carried no
     * prose (a structured field, a key name, or a repeat of already-emitted characters).
     */
    String accept(String fragment) {
        if (fragment != null && !fragment.isEmpty()) {
            raw.append(fragment);
        }
        // Only the earliest prose value with something left to deliver is advanced, so deltas
        // follow the order the model wrote the fields in rather than the iteration order of the
        // key set. A value is still a candidate on the fragment that closes it — the text that
        // arrived alongside the closing quote is undelivered until then.
        String key = earliestUndeliveredProseKey();
        if (key == null) {
            return "";
        }
        return deltaFor(key);
    }

    /** The prose key with the earliest value that still has undelivered text. */
    private String earliestUndeliveredProseKey() {
        String earliestKey = null;
        int earliest = Integer.MAX_VALUE;
        for (String key : PROSE_KEYS) {
            int start = findValueStart(key);
            if (start < 0 || start >= earliest) {
                continue;
            }
            if (decodedFor(key).length() <= emitted.getOrDefault(key, 0)) {
                continue; // fully delivered already
            }
            earliest = start;
            earliestKey = key;
        }
        return earliestKey;
    }

    /** The accumulated raw JSON — the complete tool input once the stream ends. */
    String raw() {
        return raw.toString();
    }

    /**
     * The not-yet-emitted tail of {@code key}'s value, or {@code ""} when the key isn't present,
     * its value is closed, or it was already fully delivered.
     */
    private String deltaFor(String key) {
        String decoded = decodedFor(key);
        int already = emitted.getOrDefault(key, 0);
        if (decoded.length() <= already) {
            return "";
        }
        emitted.put(key, decoded.length());
        return decoded.substring(already);
    }

    /** The value of {@code key} decoded so far — up to its closing quote, or up to the buffer end. */
    private String decodedFor(String key) {
        int valueStart = findValueStart(key);
        if (valueStart < 0) {
            return "";
        }
        String buffer = raw.toString();
        int close = findStringEnd(buffer, valueStart);
        int end = close < 0 ? buffer.length() : close;
        return decode(buffer, valueStart, end);
    }

    /**
     * Index of the first character after the opening quote of {@code key}'s string value, or -1 when
     * the key hasn't been fully streamed yet. Matches the literal {@code "key":"} so it works on a
     * fragment boundary that lands mid-key.
     */
    private int findValueStart(String key) {
        String needle = "\"" + key + "\":\"";
        int at = raw.toString().indexOf(needle);
        return at < 0 ? -1 : at + needle.length();
    }

    /**
     * Index of the closing quote of the string value starting at {@code from}, or -1 when the value
     * is still open. Skips escaped quotes so a prose value containing {@code \"} doesn't end early.
     */
    private static int findStringEnd(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++; // skip the escaped character
                continue;
            }
            if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Decode the JSON string body {@code s[from, to)} — a raw, possibly unterminated string
     * literal. A trailing incomplete escape (a lone {@code \} or a partial {@code \\uXXXX}) is
     * left undecoded rather than guessed at; it arrives on the next scan.
     */
    static String decode(String s, int from, int to) {
        StringBuilder out = new StringBuilder(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i + 1 >= to) {
                break; // dangling backslash — the escape isn't complete yet
            }
            char esc = s.charAt(++i);
            switch (esc) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'u' -> {
                    if (i + 4 >= to) {
                        return out.toString(); // partial unicode escape — wait for the rest
                    }
                    String hex = s.substring(i + 1, i + 5);
                    try {
                        out.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException e) {
                        out.append("\\u").append(hex);
                    }
                    i += 4;
                }
                default -> out.append(esc);
            }
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return "ProseDeltaScanner{chars=" + raw.length() + ", emitted=" + emitted + "}";
    }
}
