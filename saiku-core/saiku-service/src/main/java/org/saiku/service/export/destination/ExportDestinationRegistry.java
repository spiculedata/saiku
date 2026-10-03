/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Registry of every {@link ExportDestination} in this deployment (saiku#1987). The file-delivery
 * sibling of the {@code JobHandler} map {@code saiku-beans.xml} already builds for the job scheduler,
 * and of the channel map the threshold-alert handler takes.
 *
 * <p>Wired the same way — a {@code <property name="destinations"><map>…</map></property>} — so adding
 * a destination is a one-bean change in {@code saiku-beans.xml}:
 *
 * <pre>{@code
 * <bean id="exportDestinationRegistry" class="org.saiku.service.export.destination.ExportDestinationRegistry">
 *   <property name="destinations">
 *     <map>
 *       <entry key="GOOGLE_DRIVE" value-ref="googleDriveExportDestination"/>
 *     </map>
 *   </property>
 * </bean>
 * }</pre>
 *
 * <p>When the same destination is supplied under two keys the {@link ExportDestination#id()} wins, so
 * a stale bean id in the XML cannot create an alias an operator's job payload could use to bypass a
 * per-id policy. A bean whose declared id disagrees with its map key is dropped with an error rather
 * than registered under a second name.
 */
public class ExportDestinationRegistry {

    private final Map<String, ExportDestination> destinations = new TreeMap<>();

    /** Spring setter. Accepts the {@code <map>} wired in {@code saiku-beans.xml}. */
    public void setDestinations(Map<String, ExportDestination> configured) {
        destinations.clear();
        if (configured == null) {
            return;
        }
        for (Map.Entry<String, ExportDestination> entry : configured.entrySet()) {
            register(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Register one destination under {@code key}.
     *
     * @throws IllegalArgumentException on a null key/destination, a non upper-snake-case key, or a key
     *     that disagrees with the destination's own {@link ExportDestination#id()}
     */
    public void register(String key, ExportDestination destination) {
        if (destination == null) {
            throw new IllegalArgumentException("export destination bean is null for key '" + key + "'");
        }
        if (key == null || !key.equals(key.trim().toUpperCase(Locale.ROOT)) || !key.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException(
                    "export destination key must be UPPER_SNAKE_CASE and free of whitespace, got '" + key + "'");
        }
        String declared = destination.id();
        if (declared == null || declared.isBlank()) {
            throw new IllegalArgumentException("export destination " + key + " has no id()");
        }
        if (!key.equals(declared.trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "export destination bean " + key + " declares id() '" + declared + "' — the two must match");
        }
        if (destinations.containsKey(key)) {
            throw new IllegalArgumentException("export destination '" + key + "' is already registered");
        }
        destinations.put(key, destination);
    }

    /** Every registered destination, ordered by id. */
    public List<ExportDestination> list() {
        return new ArrayList<>(destinations.values());
    }

    public List<String> ids() {
        return List.copyOf(destinations.keySet());
    }

    /** The destination registered under {@code id}, or null. Lookup is case-insensitive. */
    public ExportDestination find(String id) {
        return id == null ? null : destinations.get(id.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * The destination registered under {@code id}, failing loudly.
     *
     * @throws ExportDeliveryException if nothing is registered under that id — the scheduled job
     *     records this as a FAILED run and backs off, rather than silently doing nothing
     */
    public ExportDestination require(String id) throws ExportDeliveryException {
        ExportDestination d = find(id);
        if (d == null) {
            throw new ExportDeliveryException("no export destination is registered with id '" + id + "' (registered: "
                    + String.join(", ", ids()) + ")");
        }
        return d;
    }

    public int size() {
        return destinations.size();
    }

    @Override
    public String toString() {
        return "ExportDestinationRegistry" + new LinkedHashMap<>(destinations).keySet();
    }

    /** Test convenience: register and return, so a test reads as one statement. */
    public ExportDestinationRegistry with(String key, ExportDestination destination) {
        register(key, destination);
        return this;
    }

    /** Test convenience: the destinations sorted by display name, for stable admin listings. */
    public List<ExportDestination> listByDisplayName() {
        List<ExportDestination> out = new ArrayList<>(destinations.values());
        out.sort(Comparator.comparing(ExportDestination::displayName, Comparator.nullsLast(String::compareTo)));
        return out;
    }
}
