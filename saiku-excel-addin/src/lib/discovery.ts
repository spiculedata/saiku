import { apiJson, type ServerCtx } from "./http";
import type { Cube, CubeSchema, DimPick } from "./types";

export interface MemberHit { uniqueName: string; name: string; caption: string; }

export const listCubes = (ctx: ServerCtx) => apiJson<Cube[]>(ctx, "/rest/saiku/api/ai/cubes");
export const getSchema = (ctx: ServerCtx, id: string) => apiJson<CubeSchema>(ctx, `/rest/saiku/api/ai/schema/${id}`);

/**
 * Member search for the Filters step. The AI API requires cubeId + dimension +
 * hierarchy + level + q (verified against the running server — a cube/hierarchy/q
 * form returns HTTP 400).
 */
export const searchMembers = (ctx: ServerCtx, cubeId: string, pick: DimPick, q: string) => {
  const qs = new URLSearchParams({
    cubeId, dimension: pick.dimension, hierarchy: pick.hierarchy, level: pick.level, q
  });
  return apiJson<MemberHit[]>(ctx, `/rest/saiku/api/ai/members/search?${qs.toString()}`);
};
