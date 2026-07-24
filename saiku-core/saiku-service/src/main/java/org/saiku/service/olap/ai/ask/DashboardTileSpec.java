/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.saiku.service.olap.ai.AiQueryRequest;

/**
 * One validated tile in a {@link DashboardSpec}. An intermediate producer of the persisted
 * dashboard-tile shape (the frontend's D2 slice maps this onto the real {@code DashboardTile}); it
 * is NOT the persisted model.
 *
 * <p>Every field here has already passed server-side hardening in {@link
 * AiAskService#buildDashboard}: the {@code title} is sanitised plain text, {@code type} is one of
 * {chart, table, kpi}, {@code chartType} is either an allowlisted subtype (chart tiles) or {@code
 * null} (non-chart tiles), and {@code query} is an {@link AiQueryRequest} that converts cleanly
 * against the live cube with its cube pinned to the session ref. Pure DTO — no behaviour.
 *
 * @param title tile heading, sanitised plain text (never blank)
 * @param type tile kind: {@code chart} | {@code table} | {@code kpi}
 * @param chartType chart subtype when {@code type == "chart"}; {@code null} otherwise
 * @param query the per-tile query, cube-pinned and validated against the live cube
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardTileSpec(String title, String type, String chartType, AiQueryRequest query) {}
