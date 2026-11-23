/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.saiku.olap.query2.Parameter;
import org.saiku.olap.query2.Parameter.ParameterType;
import org.saiku.service.util.exception.SaikuServiceException;

/**
 * Typed replacement for the untyped {@code ${name}} + meta-character-deny-list flow in {@link
 * MdxParameterSubstitutor} (saiku#832, follow-up to saiku#780).
 *
 * <p>{@link MdxParameterSubstitutor} closed the injection surface by rejecting any parameter
 * value containing an MDX-meta character ({@code [ ] { } ' " ;}), which also blocks every
 * legitimate use of those characters — a member reference like {@code [Time].[1997]} or a quoted
 * string can never be substituted through it. This class takes the alternative the issue asks
 * for: bind each {@code :name} placeholder according to a <em>declared type</em>
 * ({@link ParameterType}), validate the value against that type's own MDX grammar, and render it
 * as the corresponding MDX literal. A meta character is safe exactly where its type's grammar
 * says it's safe — inside a member's brackets, inside a quoted string's escaped quotes — and
 * rejected everywhere else. The value never has to pass a blanket deny list, and it never reaches
 * the MDX parser as free-form text: it's only ever inserted as a literal that already conforms to
 * the syntax {@code renderLiteral} constructed for it.
 *
 * <p>A placeholder with no corresponding entry in {@code parameters} is left untouched — this
 * keeps a query that also happens to contain an incidental {@code :} (e.g. the MDX range
 * operator, which is always followed by whitespace or {@code [}, never by an identifier) from
 * being disturbed.
 */
public final class TypedMdxParameterBinder {

    /** Matches {@code :name} placeholders — a colon followed by an identifier. */
    private static final Pattern PLACEHOLDER = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * A single member unique name: one or more bracketed segments joined by dots, e.g. {@code
     * [Time].[1997].[Q1]}. Whatever appears between a matched pair of brackets is opaque to this
     * validator, exactly as the MDX lexer treats it as opaque identifier text once it sees the
     * opening {@code [} — so a member value can't use a meta character to break out of its own
     * reference. (A member name that itself contains a literal {@code ]}, escaped in MDX by
     * doubling it, is not accepted by this pattern; that's a known limitation, not a soundness
     * gap — it fails closed rather than open.)
     */
    private static final Pattern MEMBER_NAME = Pattern.compile("^(\\[[^\\[\\]]+])(\\.\\[[^\\[\\]]+])*$");

    private static final Pattern NUMBER_LITERAL = Pattern.compile("^-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?$");

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\u0000-\\u001f]");

    private TypedMdxParameterBinder() {}

    /**
     * Bind every {@code :name} placeholder in {@code query} that has a matching entry in {@code
     * parameters} to its typed MDX literal.
     *
     * @throws SaikuServiceException if a value doesn't match its declared type's grammar
     */
    public static String bind(String query, Map<String, Parameter> parameters) {
        if (query == null || query.isEmpty()) return query;
        if (parameters == null || parameters.isEmpty()) return query;

        Matcher m = PLACEHOLDER.matcher(query);
        StringBuilder out = new StringBuilder(query.length() + 32);
        while (m.find()) {
            String name = m.group(1);
            Parameter param = caseInsensitiveGet(parameters, name);
            if (param == null) {
                continue; // not a declared parameter - leave the literal ":name" text in place
            }
            String literal = renderLiteral(param);
            m.appendReplacement(out, Matcher.quoteReplacement(literal));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String renderLiteral(Parameter param) {
        ParameterType type = param.getType();
        if (type == null) {
            throw new SaikuServiceException("Parameter '" + param.getName() + "' has no declared type");
        }
        Object value = param.getValue();
        switch (type) {
            case STRING:
                return renderString(param.getName(), value);
            case NUMBER:
                return renderNumber(param.getName(), value);
            case MEMBER:
                return renderMember(param.getName(), value);
            case SET:
                return renderList(param.getName(), value, "{", "}");
            case TUPLE:
                return renderList(param.getName(), value, "(", ")");
            default:
                throw new SaikuServiceException("Unsupported parameter type: " + type);
        }
    }

    /** Rendered as an MDX string literal — embedded double quotes are escaped by doubling, the
     *  standard MDX string-literal escape, rather than rejected. */
    private static String renderString(String name, Object value) {
        String s = requireString(name, value);
        if (CONTROL_CHARS.matcher(s).find()) {
            throw new SaikuServiceException("Parameter '" + name + "' contains control characters");
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** Rendered as a bare MDX numeric literal - no quoting needed. */
    private static String renderNumber(String name, Object value) {
        String s = value instanceof Number ? value.toString() : requireString(name, value);
        if (!NUMBER_LITERAL.matcher(s).matches()) {
            throw new SaikuServiceException(
                    "Parameter '" + name + "' is declared as a number but its value is not numeric: " + s);
        }
        return s;
    }

    /** Rendered as-is once validated - a member unique name is already valid MDX syntax. */
    private static String renderMember(String name, Object value) {
        String s = requireString(name, value);
        if (!MEMBER_NAME.matcher(s).matches()) {
            throw new SaikuServiceException("Parameter '" + name
                    + "' is declared as a member but its value is not a well-formed member unique name: " + s);
        }
        return s;
    }

    /** Rendered as an MDX set ({@code {a, b}}) or tuple ({@code (a, b)}) of validated members. */
    private static String renderList(String name, Object value, String open, String close) {
        if (!(value instanceof List)) {
            throw new SaikuServiceException("Parameter '" + name + "' is declared as a "
                    + (open.equals("{") ? "set" : "tuple") + " but its value is not a list of member unique names");
        }
        List<?> items = (List<?>) value;
        if (items.isEmpty()) {
            throw new SaikuServiceException("Parameter '" + name + "' must contain at least one member");
        }
        StringBuilder sb = new StringBuilder(open);
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(renderMember(name, items.get(i)));
        }
        return sb.append(close).toString();
    }

    private static String requireString(String name, Object value) {
        if (!(value instanceof String)) {
            throw new SaikuServiceException("Parameter '" + name + "' must be a string value");
        }
        return (String) value;
    }

    private static Parameter caseInsensitiveGet(Map<String, Parameter> parameters, String name) {
        if (parameters.containsKey(name)) return parameters.get(name);
        for (Map.Entry<String, Parameter> e : parameters.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }
}
