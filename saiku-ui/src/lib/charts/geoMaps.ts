/*
 * Lazy GeoJSON map registration for ECharts (#1071, map chart).
 *
 * The pure builder (build.ts) only emits the `map` series; ECharts needs the
 * GeoJSON registered globally via echarts.registerMap(name, geoJSON) BEFORE
 * setOption. That's a side effect (echarts import + fetch), so it lives here,
 * outside the pure builder, and both rendering surfaces (ChartView,
 * ChartTile) call ensureGeoMap() before rendering a map.
 *
 * The GeoJSON is fetched once, lazily, from the bundled static asset
 * (static/geo/<name>.json) so it never weighs down the main JS bundle and
 * works fully offline (on-prem) once shipped. Phase 1 ships "world"
 * (Natural Earth 110m admin-0, public domain).
 */

import * as echarts from "echarts";
import { base } from "$app/paths";

const registered = new Set<string>();
const inflight = new Map<string, Promise<void>>();

/** Has this map already been registered with ECharts in this session? */
export function isGeoMapRegistered(name = "world"): boolean {
  return registered.has(name);
}

/** Fetch + register a bundled GeoJSON map with ECharts, exactly once. Safe to
 *  call on every render — it resolves immediately once registered and
 *  de-dupes concurrent calls. Resolves when the map is ready; rejects only on
 *  fetch/parse failure (the caller can leave the canvas blank + log). */
export async function ensureGeoMap(name = "world"): Promise<void> {
  if (registered.has(name)) return;
  const existing = inflight.get(name);
  if (existing) return existing;
  const p = (async () => {
    const res = await fetch(`${base}/geo/${name}.json`, {
      headers: { Accept: "application/json" },
    });
    if (!res.ok) throw new Error(`geo map "${name}" HTTP ${res.status}`);
    const geo = await res.json();
    echarts.registerMap(name, geo);
    registered.add(name);
  })();
  inflight.set(name, p);
  try {
    await p;
  } finally {
    inflight.delete(name);
  }
}
