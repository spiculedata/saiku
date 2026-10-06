/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;

/**
 * saiku#779 — the single definition of how a caller's Spring authorities resolve to Mondrian roles on
 * a datasource.
 *
 * <p>{@code SecurityAwareConnectionManager.applySecurity} enforces this at connection time, and the
 * admin role API ({@code /saiku/admin/roles}) previews it ("test as this user / these roles"). Both
 * call the same code, so a preview can't drift from what enforcement actually does.
 *
 * <p>The policy is carried on the datasource's properties:
 *
 * <ul>
 *   <li>{@code security.enabled} — {@code true} turns role security on.
 *   <li>{@code security.type} — {@code one2one} (a Spring authority maps to the Mondrian role of the
 *       same name), {@code lookup} (an explicit {@code security.mapping} table), or
 *       {@code passthrough} (the user's credentials are forwarded to the warehouse; no Mondrian
 *       role).
 *   <li>{@code security.mapping} — for {@code lookup}: {@code springRole=MondrianRole;...}. A Spring
 *       role may appear more than once to grant several Mondrian roles.
 * </ul>
 *
 * <p>Stateless and side-effect free.
 */
public final class MondrianRolePolicy {

    /** How a datasource resolves roles. */
    public enum Mode {
        /** Security disabled — every caller gets the Mondrian root role. */
        DISABLED,
        /** {@code one2one}: Spring authority name == Mondrian role name. */
        ONE2ONE,
        /** {@code lookup}: explicit {@code security.mapping} table. */
        LOOKUP,
        /** {@code passthrough}: credentials forwarded to the warehouse, no Mondrian role applied. */
        PASSTHROUGH,
        /** Security enabled with a missing or unrecognised {@code security.type}; no role applied. */
        UNKNOWN
    }

    /** What a caller gets on a datasource. */
    public enum Access {
        /** Security disabled: full access for everyone. */
        UNSECURED,
        /** Scoped to the resolved Mondrian role(s). */
        SCOPED,
        /** No role resolved, but the caller is an admin: Mondrian root (full access). */
        FULL_ADMIN,
        /** No role resolved and not an admin: connection denied (saiku#1968 fail-closed). */
        DENIED,
        /** Pass-through: access is decided by the warehouse, not by a Mondrian role. */
        PASSTHROUGH,
        /** Security enabled with an unrecognised type: no role is applied. */
        UNKNOWN
    }

    /** A preview of one caller on one datasource. */
    public static final class Resolution {
        private final Access access;
        private final List<String> mondrianRoles;

        Resolution(Access access, List<String> mondrianRoles) {
            this.access = access;
            this.mondrianRoles = Collections.unmodifiableList(new ArrayList<>(mondrianRoles));
        }

        public Access getAccess() {
            return access;
        }

        public List<String> getMondrianRoles() {
            return mondrianRoles;
        }
    }

    private MondrianRolePolicy() {}

    /** The resolution mode a datasource's properties select. */
    public static Mode modeOf(SaikuDatasource datasource) {
        Properties props = datasource == null ? null : datasource.getProperties();
        if (props == null || !Boolean.parseBoolean(props.getProperty(ISaikuConnection.SECURITY_ENABLED_KEY, "false"))) {
            return Mode.DISABLED;
        }
        String type = props.getProperty(ISaikuConnection.SECURITY_TYPE_KEY);
        if (ISaikuConnection.SECURITY_TYPE_SPRING2MONDRIAN_VALUE.equals(type)) {
            return Mode.ONE2ONE;
        }
        if (ISaikuConnection.SECURITY_TYPE_SPRINGLOOKUPMONDRIAN_VALUE.equals(type)) {
            return Mode.LOOKUP;
        }
        if (ISaikuConnection.SECURITY_TYPE_PASSTHROUGH_VALUE.equals(type)) {
            return Mode.PASSTHROUGH;
        }
        return Mode.UNKNOWN;
    }

    /**
     * Parse a {@code security.mapping} value ({@code springRole=MondrianRole;...}) into an
     * insertion-ordered map. Entries that aren't exactly {@code a=b} are skipped, as they always have
     * been at enforcement time.
     */
    public static Map<String, List<String>> parseMapping(String mapping) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (mapping == null) {
            return result;
        }
        for (String entry : mapping.split(";")) {
            String[] m = entry.split("=");
            // saiku#1972: a blank value (ROLE_X= ) maps to no role, not to a role named " ".
            if (m.length == 2 && !m[1].trim().isEmpty()) {
                result.computeIfAbsent(m[0], k -> new ArrayList<>()).add(m[1]);
            }
        }
        return result;
    }

    /** The {@code security.mapping} of a datasource, parsed. Empty when absent. */
    public static Map<String, List<String>> mappingOf(SaikuDatasource datasource) {
        Properties props = datasource == null ? null : datasource.getProperties();
        return parseMapping(props == null ? null : props.getProperty(ISaikuConnection.SECURITY_LOOKUP_KEY));
    }

    /** Serialise a mapping back to the {@code security.mapping} wire format. */
    public static String formatMapping(Map<String, List<String>> mapping) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : mapping.entrySet()) {
            for (String mondrianRole : e.getValue()) {
                if (sb.length() > 0) {
                    sb.append(';');
                }
                sb.append(e.getKey()).append('=').append(mondrianRole);
            }
        }
        return sb.toString();
    }

    /**
     * Return a copy of {@code mapping} with {@code springRole}'s grants replaced by {@code
     * mondrianRoles}. An empty list removes the Spring role from the mapping.
     */
    public static Map<String, List<String>> withGrants(
            Map<String, List<String>> mapping, String springRole, Collection<String> mondrianRoles) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : mapping.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        List<String> grants = new ArrayList<>(new LinkedHashSet<>(mondrianRoles));
        if (grants.isEmpty()) {
            copy.remove(springRole);
        } else {
            copy.put(springRole, grants);
        }
        return copy;
    }

    /**
     * Whether {@code name} can be stored in {@code security.mapping} as a Spring or Mondrian role
     * name: non-blank, no surrounding whitespace, and free of the {@code ;}/{@code =} delimiters and
     * the {@code ,} that {@code applySecurity} uses to join multiple roles.
     */
    public static boolean isValidRoleName(String name) {
        if (name == null || name.isEmpty() || !name.equals(name.trim())) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == ';' || c == '=' || c == ',' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The Mondrian roles the given Spring authorities resolve to, in authority order, de-duplicated.
     * Empty when nothing resolves (and always empty for modes that don't apply a Mondrian role).
     *
     * @param availableMondrianRoles the roles the datasource's schema declares; only consulted by
     *     {@link Mode#ONE2ONE}. {@link #preview} also checks lookup grants against it.
     * @param mapping the parsed {@code security.mapping}; only consulted by {@link Mode#LOOKUP}
     */
    public static List<String> resolveMondrianRoles(
            Mode mode,
            Collection<String> springRoles,
            Collection<String> availableMondrianRoles,
            Map<String, List<String>> mapping) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        if (springRoles == null) {
            return new ArrayList<>();
        }
        if (mode == Mode.ONE2ONE && availableMondrianRoles != null) {
            for (String sprRole : springRoles) {
                if (availableMondrianRoles.contains(sprRole)) {
                    resolved.add(sprRole);
                }
            }
        } else if (mode == Mode.LOOKUP && mapping != null) {
            for (String sprRole : springRoles) {
                List<String> roles = mapping.get(sprRole);
                if (roles != null) {
                    for (String role : roles) {
                        if (role != null && !role.trim().isEmpty()) { // saiku#1972: blank is no role
                            resolved.add(role);
                        }
                    }
                }
            }
        }
        return new ArrayList<>(resolved);
    }

    /**
     * Preview what a caller holding {@code springRoles} gets on a datasource, mirroring {@code
     * SecurityAwareConnectionManager.applySecurity} including its saiku#1968 fail-closed rule.
     *
     * <p>A resolved role the schema doesn't declare can't be applied, and enforcement denies the
     * connection (admin or not), so the preview reports {@link Access#DENIED}. When {@code
     * availableMondrianRoles} is {@code null} the schema couldn't be read, and the mapping is
     * reported as-is.
     *
     * @param admin whether any of the caller's authorities is a configured admin role
     */
    public static Resolution preview(
            SaikuDatasource datasource,
            Collection<String> springRoles,
            Collection<String> availableMondrianRoles,
            boolean admin) {
        Mode mode = modeOf(datasource);
        switch (mode) {
            case DISABLED:
                return new Resolution(Access.UNSECURED, List.of());
            case PASSTHROUGH:
                return new Resolution(Access.PASSTHROUGH, List.of());
            case UNKNOWN:
                return new Resolution(Access.UNKNOWN, List.of());
            default:
                List<String> roles =
                        resolveMondrianRoles(mode, springRoles, availableMondrianRoles, mappingOf(datasource));
                if (!roles.isEmpty()) {
                    if (availableMondrianRoles != null && !availableMondrianRoles.containsAll(roles)) {
                        return new Resolution(Access.DENIED, List.of());
                    }
                    return new Resolution(Access.SCOPED, roles);
                }
                return new Resolution(admin ? Access.FULL_ADMIN : Access.DENIED, List.of());
        }
    }
}
