import { apiJson } from "./http";
import type { Cube, CubeSchema } from "./types";
export const listCubes = () => apiJson<Cube[]>("/rest/saiku/api/ai/cubes");
export const getSchema = (id: string) => apiJson<CubeSchema>(`/rest/saiku/api/ai/schema/${id}`);
export const searchMembers = (cubeId: string, hierarchy: string, q: string) =>
  apiJson<{ uniqueName: string; caption: string }[]>(
    `/rest/saiku/api/ai/members/search?cube=${encodeURIComponent(cubeId)}&hierarchy=${encodeURIComponent(hierarchy)}&q=${encodeURIComponent(q)}`);
