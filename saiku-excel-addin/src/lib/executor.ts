import { api, type ServerCtx } from "./http";
import { toGrid } from "./resultGrid";
import type { AiQueryRequest, AiQueryResponse, Grid } from "./types";

export type RunResult =
  | { ok: true; grid: Grid; resp: AiQueryResponse }
  | { ok: false; status: string; error: string; field?: string | null; available?: string[] };

export async function runQuery(ctx: ServerCtx, req: AiQueryRequest): Promise<RunResult> {
  // The AI API returns HTTP 400 (with a VALIDATION_ERROR body carrying field/available)
  // for bad requests, so we must read the body regardless of status — apiJson would
  // throw on the 400 first and discard the hints the wizard needs to self-correct.
  const res = await api(ctx, "/rest/saiku/api/ai/query", { method: "POST", body: JSON.stringify(req) });
  let resp: AiQueryResponse;
  try {
    resp = (await res.json()) as AiQueryResponse;
  } catch {
    return { ok: false, status: `HTTP_${res.status}`, error: `Query failed (HTTP ${res.status})` };
  }
  if (res.ok && resp.status === "SUCCESS") return { ok: true, grid: toGrid(resp), resp };
  return {
    ok: false,
    status: resp.status ?? `HTTP_${res.status}`,
    error: resp.error ?? resp.status ?? `Query failed (HTTP ${res.status})`,
    field: resp.field,
    available: resp.available
  };
}
