/*
 * Tile plugin catalogue client (App Builder Phase 2, saiku#1441).
 *
 * The `plugin` custom tile runs ADMIN-INSTALLED, trusted HTML in a locked-down
 * sandboxed iframe. That HTML comes ONLY from the admin registry — never from
 * tile config — so a dashboard author can only PICK an installed plugin by its
 * slug id, and the markup is fetched here at render time.
 *
 * Talks to TilePluginResource (@ /rest/saiku/api/tile-plugins), full-auth like
 * the rest of the authoring surface. Bare fetch + credentials:"include", the
 * same posture as $lib/api/apps.ts and the other in-app clients.
 */

const REST_BASE = "/rest/saiku/api/tile-plugins";

/** One installed plugin, as TilePluginManifest#asSummary serialises it. */
export interface TilePluginSummary {
  id: string;
  label: string;
  /** Optional JSON Schema for the plugin's author-facing options (unused by the
   *  picker today; carried through for a future options editor). */
  optionSchema?: unknown;
}

/** List installed tile plugins. Throws on any non-2xx. */
export async function listTilePlugins(): Promise<TilePluginSummary[]> {
  const res = await fetch(REST_BASE, {
    credentials: "include",
    headers: { Accept: "application/json" },
  });
  if (!res.ok) throw new Error(`listTilePlugins -> ${res.status}`);
  return (await res.json()) as TilePluginSummary[];
}

/**
 * Fetch an installed plugin's `plugin.html` srcdoc source by id. Returns the raw
 * HTML string; throws on any non-2xx (404 = not installed). The caller wraps it
 * in a strict-CSP srcdoc via {@code buildSrcdoc} — this fetch is the ONLY source
 * of plugin markup on the in-app surface.
 */
export async function fetchTilePluginHtml(id: string): Promise<string> {
  const res = await fetch(`${REST_BASE}/${encodeURIComponent(id)}/html`, {
    credentials: "include",
    headers: { Accept: "text/html" },
  });
  if (!res.ok) throw new Error(`fetchTilePluginHtml(${id}) -> ${res.status}`);
  return await res.text();
}
