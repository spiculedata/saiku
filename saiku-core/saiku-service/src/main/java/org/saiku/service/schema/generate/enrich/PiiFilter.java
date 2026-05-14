package org.saiku.service.schema.generate.enrich;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strips PII-looking column samples before they are sent to an external {@link
 * org.saiku.service.schema.generate.enrich.provider.LlmProvider}.
 *
 * <p>Deny-list based. A sample-map entry is removed when its column name (the portion after the
 * last {@code .} in the key, or the whole key if no dot is present) matches one of the configured
 * patterns, case-insensitively. Patterns may include a single {@code *} wildcard which matches any
 * run of characters.
 *
 * <p>Stateless and thread-safe; construct once and reuse.
 */
public final class PiiFilter {

    /** Case-insensitive default deny list — see javadoc on the class-level contract in Task B3. */
    private static final Set<String> DEFAULT_PATTERNS = Set.of(
            "ssn",
            "password",
            "passwd",
            "pwd",
            "secret",
            "api_key",
            "email",
            "email_address",
            "*_email",
            "social_security",
            "credit_card",
            "card_number",
            "cvv",
            "phone",
            "phone_number",
            "ip_address",
            "token",
            "auth_token");

    private final List<Pattern> compiled;

    public PiiFilter() {
        this(Collections.emptySet());
    }

    public PiiFilter(Set<String> extraPatterns) {
        Set<String> all = new HashSet<>(DEFAULT_PATTERNS);
        if (extraPatterns != null) {
            for (String p : extraPatterns) {
                if (p != null && !p.isBlank()) {
                    all.add(p.toLowerCase(Locale.ROOT));
                }
            }
        }
        this.compiled = all.stream().map(PiiFilter::compile).toList();
    }

    private static Pattern compile(String glob) {
        // Escape regex metacharacters except our wildcard marker.
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if ("\\.[]{}()+-?^$|".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    /**
     * Return a new map containing every entry from {@code samples} whose column name is not on the
     * deny list. Input is not mutated. Iteration order is preserved.
     */
    public Map<String, List<String>> filter(Map<String, List<String>> samples) {
        if (samples == null || samples.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>(samples.size());
        for (Map.Entry<String, List<String>> e : samples.entrySet()) {
            String key = e.getKey();
            if (key == null) {
                continue;
            }
            String column = columnOf(key);
            if (!isPii(column)) {
                out.put(key, e.getValue());
            }
        }
        return out;
    }

    private static String columnOf(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? key : key.substring(dot + 1);
    }

    private boolean isPii(String column) {
        for (Pattern p : compiled) {
            if (p.matcher(column).matches()) {
                return true;
            }
        }
        return false;
    }
}
