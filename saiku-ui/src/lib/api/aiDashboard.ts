/*
 * AI Dashboard-builder client — TypeScript bindings to
 * POST /rest/saiku/api/ai/ask/dashboard.
 *
 * From one natural-language question + cube ref (+ optional history) the
 * backend LLM emits a validated, server-hardened multi-tile DashboardSpec.
 * Non-streaming: unlike the chain endpoint this is a single request /
 * single JSON response (~30-90s on a local model), so it is a plain fetch
 * → await res.json(), mirroring askAi()'s envelope + auth/cookie handling.
 *
 * Wire contract (pinned by the D1 backend + QA reports):
 *   - `degraded` is the always-present discriminator (a primitive boolean).
 *   - On success: {title, tiles:[...], degraded:false, reason:"", model?}.
 *   - On degrade: {tiles:[], degraded:true, reason, model?} — `title` absent.
 *   - Per tile: `title`, `type`, `query` always present; `chartType` present
 *     only for chart tiles (absent on table/kpi).
 *   - type ∈ {chart, table, kpi}; chartType ∈ {bar, line, pie, area, scatter}
 *     — exact UI-valid subsets, no casing drift.
 *   - Each tile `query` is a full AiQueryRequest (cube pinned to the session
 *     ref) — the same shape /ai/query consumes and InlineQuery.body expects.
 *
 * Backend: AiQueryResource.askDashboard → AiAskService.buildDashboard.
 */

import { AiAskTransportError, type AiCubeRef, type NlAskMessageDto } from "./aiAsk";

const DASHBOARD_URL = "/rest/saiku/api/ai/ask/dashboard";

/** Request envelope — the same AskRequest the ask endpoints accept, narrowed
 *  to the fields a dashboard build uses (no cellset digest: the build reasons
 *  over schema only, never executed cell data). */
export interface BuildDashboardRequest {
  question: string;
  cube: AiCubeRef;
  history?: NlAskMessageDto[];
}

/** One assembled tile in the spec. `query` is an opaque full AiQueryRequest —
 *  the dashboard layer treats it as a body to hand to /ai/query, exactly like
 *  {@link import("./dashboards").InlineQuery}.body. */
export interface DashboardTileSpec {
  /** Tile heading — sanitised plain text. Rendered as TEXT only, never @html. */
  title: string;
  type: "chart" | "table" | "kpi";
  /** Present only for chart tiles (absent on table/kpi). */
  chartType?: "bar" | "line" | "pie" | "area" | "scatter";
  /** Full AiQueryRequest (cube pinned to the session ref). */
  query: Record<string, unknown>;
}

export interface DashboardSpec {
  /** Dashboard name — sanitised plain text. Absent (NON_NULL) when degraded. */
  title?: string;
  /** Assembled tiles; empty when degraded. */
  tiles: DashboardTileSpec[];
  /** Always present. true ⇒ build failed/empty; render `reason`, do not assemble. */
  degraded: boolean;
  /** Generic, user-safe message; present ("" on success) — populated when degraded. */
  reason?: string;
  /** Provider model id; may be absent. */
  model?: string;
}

/** Type guard: does a parsed body look like a DashboardSpec? The `degraded`
 *  boolean discriminator is always present on a real spec (success or
 *  degrade), so its presence tells a spec envelope apart from an error body
 *  a non-2xx status might carry (e.g. a bare {error:"…"} from a 500). */
function isDashboardSpec(body: unknown): body is DashboardSpec {
  return (
    !!body &&
    typeof body === "object" &&
    typeof (body as { degraded?: unknown }).degraded === "boolean"
  );
}

/**
 * POST the build request and return the parsed DashboardSpec.
 *
 * A degraded build is a normal 200 (or a preamble 503 for "not configured")
 * whose body carries `degraded:true` — it is returned as-is, NOT thrown, so
 * the caller can render the generic `reason`. A transport failure, an empty /
 * non-JSON body, or a non-2xx response that is not a spec envelope (e.g. a
 * 500) throws {@link AiAskTransportError} so it is surfaced distinctly from a
 * degrade.
 */
export async function buildAiDashboard(
  req: BuildDashboardRequest,
  signal?: AbortSignal,
): Promise<DashboardSpec> {
  let res: Response;
  try {
    res = await fetch(DASHBOARD_URL, {
      method: "POST",
      credentials: "include",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: JSON.stringify(req),
      signal,
    });
  } catch (e) {
    // A user-initiated cancel aborts the fetch — surface the AbortError raw so
    // the caller can dismiss quietly (NOT as a transport/error toast).
    if ((e as Error)?.name === "AbortError") throw e;
    throw new AiAskTransportError(`buildAiDashboard: transport error (${(e as Error).message})`, 0);
  }

  const text = await res.text();
  if (!text) {
    throw new AiAskTransportError(`buildAiDashboard -> ${res.status}: empty body`, res.status);
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (e) {
    throw new AiAskTransportError(
      `buildAiDashboard -> ${res.status}: non-JSON response (${(e as Error).message})`,
      res.status,
    );
  }
  // A well-formed spec envelope (degraded discriminator present) is returned
  // as-is regardless of status — the degrade path carries the reason. Anything
  // else on a non-2xx is a real failure to surface.
  if (isDashboardSpec(parsed)) {
    return parsed;
  }
  if (!res.ok) {
    throw new AiAskTransportError(`buildAiDashboard -> ${res.status}`, res.status);
  }
  // 2xx but not a recognisable spec — defensive; treat as a transport-level
  // contract breach rather than silently returning a malformed object.
  throw new AiAskTransportError(
    `buildAiDashboard -> ${res.status}: response is not a DashboardSpec`,
    res.status,
  );
}
