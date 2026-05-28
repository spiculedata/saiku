export interface SaikuCube {
  connection: string;
  catalog: string;
  schema: string;
  name: string;
  caption: string;
  uniqueName: string;
  visible: boolean;
}

export interface SaikuSchema {
  name: string;
  cubes: SaikuCube[];
}

export interface SaikuCatalog {
  name: string;
  schemas: SaikuSchema[];
}

export interface SaikuConnection {
  name: string;
  catalogs: SaikuCatalog[];
}

export interface SaikuMember {
  uniqueName: string;
  name: string;
  caption: string;
  description?: string;
  levelUniqueName?: string;
  hierarchyUniqueName?: string;
  dimensionUniqueName?: string;
}

export interface SaikuLevel {
  name: string;
  caption: string;
  uniqueName: string;
  description?: string;
}

export interface SaikuHierarchy {
  name: string;
  caption: string;
  uniqueName: string;
  levels?: SaikuLevel[];
}

export interface SaikuDimension {
  name: string;
  caption: string;
  uniqueName: string;
  hierarchies: SaikuHierarchy[];
}

export interface SaikuMeasure {
  name: string;
  caption: string;
  uniqueName: string;
  formula?: string;
  calculated?: boolean;
  /** Backend may surface visible=false for helper measures. The server
   *  filters these out unless ?includeHidden=true is set (see saiku#778
   *  / OlapDiscoverResource.getCubeMeasures). The field is forwarded
   *  raw so admin UIs can badge "(hidden)" rows without re-checking. */
  visible?: boolean;
}

const REST_BASE = "/rest/saiku";

function cubeUrl(username: string, c: SaikuCube): string {
  return `${REST_BASE}/${encodeURIComponent(username)}/discover/${encodeURIComponent(c.connection)}/${encodeURIComponent(c.catalog)}/${encodeURIComponent(c.schema)}/${encodeURIComponent(c.name)}`;
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url, { credentials: "include", headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`${url} -> ${res.status}`);
  return (await res.json()) as T;
}

export async function listConnections(username: string): Promise<SaikuConnection[]> {
  return getJson<SaikuConnection[]>(`${REST_BASE}/${encodeURIComponent(username)}/discover`);
}

export async function refreshConnections(username: string): Promise<SaikuConnection[]> {
  return getJson<SaikuConnection[]>(
    `${REST_BASE}/${encodeURIComponent(username)}/discover/refresh`,
  );
}

export async function listDimensions(username: string, cube: SaikuCube): Promise<SaikuDimension[]> {
  return getJson<SaikuDimension[]>(`${cubeUrl(username, cube)}/dimensions`);
}

/**
 * Fetch the measure tree for a cube.
 *
 * @param includeHidden When true, append `?includeHidden=true` so the
 *   server returns helper measures the schema marks `visible="false"`.
 *   Default false matches the server default (saiku#778 — analyst-safe).
 *   Admin tooling opts in via the UI toggle (#834); the server still
 *   enforces the gate, so the param is a UI convenience, not a security
 *   boundary.
 */
export async function listMeasures(
  username: string,
  cube: SaikuCube,
  includeHidden = false,
): Promise<SaikuMeasure[]> {
  const base = `${cubeUrl(username, cube)}/measures/`;
  const url = includeHidden ? `${base}?includeHidden=true` : base;
  return getJson<SaikuMeasure[]>(url);
}

export async function listMemberChildren(
  username: string,
  cube: SaikuCube,
  memberUniqueName: string,
): Promise<SaikuMember[]> {
  return getJson<SaikuMember[]>(
    `${cubeUrl(username, cube)}/member/${encodeURIComponent(memberUniqueName)}/children`,
  );
}

export async function listLevelMembers(
  username: string,
  cube: SaikuCube,
  dimensionName: string,
  hierarchyUniqueName: string,
  levelName: string,
): Promise<SaikuMember[]> {
  const base = cubeUrl(username, cube);
  return getJson<SaikuMember[]>(
    `${base}/dimensions/${encodeURIComponent(dimensionName)}/hierarchies/${encodeURIComponent(hierarchyUniqueName)}/levels/${encodeURIComponent(levelName)}`,
  );
}

export async function listRootMembers(
  username: string,
  cube: SaikuCube,
  hierarchyUniqueName: string,
): Promise<SaikuMember[]> {
  return getJson<SaikuMember[]>(
    `${cubeUrl(username, cube)}/hierarchies/${encodeURIComponent(hierarchyUniqueName)}/rootmembers`,
  );
}
