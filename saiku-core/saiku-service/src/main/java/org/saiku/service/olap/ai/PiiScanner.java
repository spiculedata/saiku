/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pattern-matches measure / level names at schema-build time and flags ones
 * that LOOK like personally identifiable data (saiku#902 phase 3). The
 * scanner SUGGESTS — never auto-applies — so the operator is always the one
 * making the policy call. False positives are by design preferred over silent
 * misses, mirroring the saiku-cloud regex redactor's
 * "false-positive-leaning" posture.
 *
 * <p>What the scanner sees:
 * <ul>
 *   <li>Every {@link AiSchema.Measure#name}</li>
 *   <li>Every {@link AiSchema.Level#name}</li>
 *   <li>Plus their canonical (lower-cased) keys, since aliases / display names
 *       may already have been folded.</li>
 * </ul>
 *
 * <p>What it does NOT do:
 * <ul>
 *   <li>Inspect ACTUAL member values — that's a separate (much higher-cost)
 *       path. The scanner is a one-shot at schema-build, not a row-walker.</li>
 *   <li>Recurse into XML annotations — annotations are the operator's
 *       declaration, the scanner is the safety net BENEATH that declaration.</li>
 * </ul>
 *
 * <p>Patterns are intentionally conservative and aligned with the saiku-cloud
 * regex redactor's coverage (issue #12 there) so the two layers reinforce
 * each other.
 */
public final class PiiScanner {

    private static final Logger log = LoggerFactory.getLogger(PiiScanner.class);

    /** One named match — the pattern that caught it + the offending name. */
    public static final class Match {
        /** Name of the matched pattern (e.g. {@code "email"}, {@code "ssn"}). */
        public final String pattern;
        /** The column / level / measure name that matched. */
        public final String name;
        /** Where in the schema the match was found, for the suggestion target.
         *  Format: {@code "measures.<name>"} or
         *  {@code "dimensions.<dim>.hierarchies.<hier>.levels.<level>"}. */
        public final String path;

        public Match(String pattern, String name, String path) {
            this.pattern = pattern;
            this.name = name;
            this.path = path;
        }
    }

    /**
     * Single rule — name + regex. Regex match is case-insensitive and tested
     * against the column's canonical (lower-cased) name. Order in
     * {@link #PATTERNS} doesn't matter — multiple matches per name are
     * deduped at the caller level.
     */
    private static final class Rule {
        final String name;
        final Pattern regex;

        Rule(String name, String regex) {
            this.name = name;
            this.regex = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        }
    }

    /**
     * The default pattern set. Each entry is a single-pattern rule that flags
     * column names matching common PII shapes.
     *
     * <p>The {@code \\b} word boundaries are critical — they're the
     * difference between catching {@code "email"} but ignoring
     * {@code "email_count"} (a COUNT(emails) aggregate which is not PII).
     * Without word boundaries, {@code "email"} would match
     * {@code "emailshipped"} (a useful operational column) too, which would
     * over-flag and dilute the signal.
     *
     * <p>Pattern ordering doesn't matter — the scanner returns a deduped list
     * keyed by (path, pattern-name).
     */
    private static final List<Rule> PATTERNS = Collections.unmodifiableList(Arrays.asList(
            // Email — substring "email" / "e_mail" / "mail" as a standalone word.
            // Excludes "email_count" via \b on the right (count is the next word
            // boundary). "mailing_list" → matches, which is the right call:
            // mailing lists ARE PII.
            new Rule("email", "\\b(e?_?mail)\\b"),
            // SSN / national identifier — US "ssn"/"social_security", UK
            // "nin" (national insurance number), Canada "sin".
            new Rule("ssn", "\\b(ssn|social_?security|nin|sin)\\b"),
            // Phone — "phone", "mobile", "tel", "tel_no".
            new Rule("phone", "\\b(phone|mobile|tel_?(no|num|number)?)\\b"),
            // Name — any of the common surface forms. Deliberately matches
            // bare "name" alone, which IS often a PII column (customer name,
            // employee name); the false-positive cost is a single suggested
            // annotation an operator can dismiss.
            new Rule("name", "\\b(first_?name|last_?name|surname|given_?name|full_?name|customer_?name|name)\\b"),
            // Date of birth — "dob", "date_of_birth", "birth_date", "birthdate".
            new Rule("dob", "\\b(dob|date_of_birth|birth_?date|birthdate)\\b"),
            // Address fields — "address", "street", "postcode"/"postal_code",
            // "zip"/"zipcode". "address_count" / "address_line_count" are
            // aggregate columns; "address_id" is a foreign key that might
            // join to a PII table but isn't itself PII.
            new Rule("address", "\\b(address|street|postcode|postal_?code|zip_?code|zip)\\b"),
            // Medical — UK NHS number, US "medical_record_number" / MRN.
            new Rule("medical_record", "\\b(nhs|medical_record|mrn)\\b"),
            // Financial — "account_number", "iban", "swift" (BIC), credit
            // card / "ccn". Note: standalone "account" is too broad (every
            // SaaS app has an account_id which isn't PII) — must be followed
            // by "_number" or "_no".
            new Rule("financial", "\\b(account_?(number|no)|iban|swift|bic|ccn|credit_?card)\\b")));

    private PiiScanner() {}

    /**
     * Scan one cube's measures + levels. Returns a list of {@link Match}
     * entries — one per (name, rule) that fires AND that doesn't already have
     * {@code saiku.semantic.pii=true} set. Matches are deduped per path —
     * a single column matching three rules surfaces as three distinct
     * suggestions; a column already explicitly annotated is silently skipped
     * (the operator has spoken).
     *
     * <p>Side effect: a WARN log line per finding so an operator tailing the
     * launcher logs sees the scanner's work in real time. The returned list
     * is the SUGGESTION SURFACE — the caller stashes it for the
     * {@code /pii-suggestions} admin endpoint.
     */
    public static List<Match> scan(AiSchema schema) {
        if (schema == null) return Collections.emptyList();
        List<Match> findings = new ArrayList<>();
        for (AiSchema.Measure m : schema.measures.values()) {
            if (m.pii) continue;
            String path = "measures." + m.name;
            checkAll(m.name, path, findings);
        }
        for (AiSchema.Dimension d : schema.dimensions.values()) {
            for (AiSchema.Hierarchy h : d.hierarchies.values()) {
                for (AiSchema.Level l : h.levels.values()) {
                    if (l.pii) continue;
                    String path = "dimensions." + d.name + ".hierarchies." + h.name + ".levels." + l.name;
                    checkAll(l.name, path, findings);
                }
            }
        }
        for (Match m : findings) {
            log.warn(
                    "PII scanner: '{}' matches pattern '{}' at {}; consider adding"
                            + " <Annotation name=\"saiku.semantic.pii\">true</Annotation>",
                    m.name,
                    m.pattern,
                    m.path);
        }
        return findings;
    }

    private static void checkAll(String name, String path, List<Match> findings) {
        for (Rule rule : PATTERNS) {
            if (rule.regex.matcher(name).find()) {
                findings.add(new Match(rule.name, name, path));
            }
        }
    }
}
