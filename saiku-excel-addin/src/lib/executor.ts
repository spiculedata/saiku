import { apiJson } from "./http";
import { toGrid } from "./resultGrid";
import type { AiQueryRequest, AiQueryResponse, Grid } from "./types";

export type RunResult =
  | { ok: true; grid: Grid; resp: AiQueryResponse }
  | { ok: false; status: string; error: string; field?: string | null; available?: string[] };

export async function runQuery(req: AiQueryRequest): Promise<RunResult> {
  const resp = await apiJson<AiQueryResponse>("/rest/saiku/api/ai/query", {
    method: "POST", body: JSON.stringify(req)
  });
  if (resp.status === "SUCCESS") return { ok: true, grid: toGrid(resp), resp };
  return { ok: false, status: resp.status, error: resp.error ?? resp.status, field: resp.field, available: resp.available };
}
