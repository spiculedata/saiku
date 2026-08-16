/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.service.user;

/**
 * Where a user's preferences live in the repository, and the rules for merging an update into them.
 *
 * <p>saiku#1868: the UI had no account-level place to remember a per-user decision, so things like
 * "I have seen the onboarding tour" lived in {@code localStorage} and replayed on every new browser,
 * machine or private window. That is per-BROWSER, not per-person.
 *
 * <p>Kept deliberately small and dumb: a flat JSON object of client-defined keys, stored per user.
 * The server does not interpret the contents — it owns only *whose* preferences these are, which is
 * the part a client cannot be trusted with.
 */
public final class UserPreferences {

    /** Hard ceiling on the stored document, so a client cannot use this as free storage. */
    public static final int MAX_BYTES = 64 * 1024;

    private UserPreferences() {}

    /**
     * Repository path holding {@code username}'s preferences.
     *
     * <p>The username is NOT taken from the request — the caller resolves it from the security
     * context — but it is still sanitised here, because a username containing {@code ../} would
     * otherwise let one account's preferences escape its own home.
     */
    public static String pathFor(String username) {
        return "/homes/" + sanitise(username) + "/.preferences.json";
    }

    /**
     * Strip anything that could traverse or escape a single path segment. Everything outside a
     * conservative allowlist becomes {@code _}, so two distinct usernames can never collapse onto
     * one path by accident of encoding.
     */
    static String sanitise(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required to resolve preferences");
        }
        StringBuilder out = new StringBuilder(username.length());
        for (char c : username.toCharArray()) {
            boolean safe = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.'
                    || c == '@';
            out.append(safe ? c : '_');
        }
        String s = out.toString();
        // Dots are allowlisted because email logins need them, but that lets "../.." survive as
        // ".._..": harmless here (the separators are already gone, so it cannot traverse) yet it
        // hands a literal ".." to whatever repository backend is underneath. Collapse runs of dots
        // and never lead with one, so no path segment can ever read as a parent reference.
        s = s.replaceAll("\\.{2,}", ".").replaceAll("^\\.+", "");
        // A name of only unsafe characters would otherwise become a run of underscores that a
        // different all-unsafe name also maps to. Refuse rather than risk collapsing two users.
        if (s.isEmpty() || s.chars().allMatch(c -> c == '_' || c == '.')) {
            throw new IllegalArgumentException("username has no usable characters: " + username);
        }
        return s;
    }
}
