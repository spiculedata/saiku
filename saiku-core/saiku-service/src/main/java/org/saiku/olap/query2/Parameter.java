/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query2;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import org.saiku.service.util.TypedMdxParameterBinder;

/**
 * A single MDX query parameter. {@link ParameterType#STRING}, {@link ParameterType#NUMBER},
 * {@link ParameterType#MEMBER}, {@link ParameterType#SET} and {@link ParameterType#TUPLE} are the
 * typed shapes bound by {@link TypedMdxParameterBinder} (saiku#832) — {@code value} holds a {@code
 * String} for STRING/NUMBER/MEMBER, or a {@code List<String>} of member unique names for
 * SET/TUPLE. {@link ParameterType#SIMPLE}, {@link ParameterType#LIST} and {@link
 * ParameterType#MDX} predate the typed API and are kept for source compatibility; nothing in this
 * codebase produces or consumes them today.
 */
public class Parameter {

    private String name;
    private Object value;
    private ParameterType type = ParameterType.SIMPLE;
    private boolean mandatory = false;

    public enum ParameterType {
        SIMPLE,
        LIST,
        MDX,
        STRING,
        NUMBER,
        MEMBER,
        SET,
        TUPLE;

        /** Accepts the wire form case-insensitively (e.g. {@code "member"}, {@code "MEMBER"}). */
        @JsonCreator
        public static ParameterType fromWire(String value) {
            if (value == null) {
                return null;
            }
            return ParameterType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        @JsonValue
        public String toWire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public Parameter() {}

    public Parameter(ParameterType type, String name, Object value, boolean mandatory) {
        this.type = type;
        this.value = value;
        this.name = name;
        this.mandatory = mandatory;
    }

    /** Convenience constructor for the typed API (saiku#832) — not mandatory by default. */
    public Parameter(String name, ParameterType type, Object value) {
        this(type, name, value, false);
    }

    /**
     * @return the name
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return the value
     */
    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    /**
     * @return the type
     */
    public ParameterType getType() {
        return type;
    }

    public void setType(ParameterType type) {
        this.type = type;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public void setMandatory(boolean mandatory) {
        this.mandatory = mandatory;
    }
}
