import { apiJson } from "./http";
import type { Cube, CubeSchema, DimPick } from "./types";

export interface MemberHit { uniqueName: string; name: string; caption: string; }

export const listCubes = () => apiJson<Cube[]>("/rest/saiku/api/ai/cubes");
export const getSchema = (id: string) => apiJson<CubeSchema>(`/rest/saiku/api/ai/schema/${id}`);

/**
 * Member search for the Filters step. The AI API requires cubeId + dimension +
 * hierarchy + level + q (verified against the running server — a cube/hierarchy/q
 * form returns HTTP 400).
 */
export const searchMembers = (cubeId: string, pick: DimPick, q: string) => {
  const qs = new URLSearchParams({
    cubeId, dimension: pick.dimension, hierarchy: pick.hierarchy, level: pick.level, q
  });
  return apiJson<MemberHit[]>(`/rest/saiku/api/ai/members/search?${qs.toString()}`);
};
