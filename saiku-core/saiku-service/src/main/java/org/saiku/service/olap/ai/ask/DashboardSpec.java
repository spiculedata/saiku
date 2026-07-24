/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Validated, hardened output of {@link AiAskService#buildDashboard} — a multi-tile dashboard the AI
 * authored from one natural-language request. An INTERMEDIATE producer of the persisted dashboard
 * model (the frontend's D2 slice drops these tiles into Saiku's real dashboard), NOT the persisted
 * {@code Dashboard} object itself.
 *
 * <p>Degrade signalling mirrors {@link AiAskService.AskOutcome}: on success {@code degraded=false}
 * and {@code tiles} is non-empty; on a degrade (provider failure, refusal, or no valid tiles)
 * {@code degraded=true}, {@code reason} carries a generic client-facing message, and {@code tiles}
 * is empty. Pure DTO — no behaviour beyond the factories.
 *
 * @param title dashboard name, sanitised plain text (never blank on success)
 * @param tiles the validated tiles; empty when {@code degraded}
 * @param degraded {@code true} iff no usable dashboard could be produced
 * @param reason generic explanation when {@code degraded}; empty otherwise
 * @param model echo of the model id the provider used; {@code null} when not applicable
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardSpec(
        String title, List<DashboardTileSpec> tiles, boolean degraded, String reason, String model) {

    public DashboardSpec {
        tiles = tiles == null ? List.of() : List.copyOf(tiles);
        reason = reason == null ? "" : reason;
    }

    /** A successful build: a titled dashboard with one or more validated tiles. */
    public static DashboardSpec ok(String title, List<DashboardTileSpec> tiles, String model) {
        return new DashboardSpec(title, tiles, false, "", model);
    }

    /** A degrade: no usable dashboard. {@code reason} is a generic, client-safe message. */
    public static DashboardSpec degraded(String reason, String model) {
        return new DashboardSpec(null, List.of(), true, reason, model);
    }
}
