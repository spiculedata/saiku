import type { WizardState, AiQueryRequest } from "./types";

export function buildRequest(s: WizardState): AiQueryRequest {
  const req: AiQueryRequest = {
    cube: s.cubeId,
    measures: s.measures.map((name) => ({ name })),
    rows: s.dimensions.map((d) => ({ dimension: d.dimension, hierarchy: d.hierarchy, level: d.level })),
    nonEmpty: true
  };
  if (s.filters.length) {
    req.filters = s.filters.map((f) => ({
      dimension: f.dimension, hierarchy: f.hierarchy, level: f.level, op: "in", members: f.members
    }));
  }
  return req;
}
