export interface SaikuCube {
  connection: string;
  catalog: string;
  schema: string;
  name: string;
  caption: string;
  uniqueName: string;
  visible: boolean;
  /** Declarative time-intelligence directives parsed from the cube's
   *  {@code <TimeCalcs>} schema section (saiku#1221 Phase 3). Surfaced by
   *  the server so the SPA date-filter modal can offer them as one-click
   *  measures rather than asking the user to author MDX. Empty / undefined
   *  for cubes that don't ship any (the common case today). */
  timeCalcs?: SaikuTimeCalc[];
}

export interface SaikuTimeCalc {
  /** Display name; also the {@code [Measures]} suffix the agent uses. */
  name: string;
  /** One of {@code yoy} / {@code pop} / {@code ytd} / {@code rolling}. */
  type: string;
  /** Underlying measure the calc derives from. */
  measure: string;
  /** Time dimension the calc operates against. */
  timeDimension: string;
  /** For {@code type=\"rolling\"}: window size; undefined otherwise. */
  window?: number;
  /** For {@code type=\"rolling\"}: aggregator (sum / avg / min / max). */
  function?: string;
  /** Mondrian format string for the resulting cell values. */
  formatString?: string;
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
  /** Mondrian-native level type (olap4j {@code Level.Type}): one of
   *  {@code TimeYears} / {@code TimeHalfYears} / {@code TimeQuarters} /
   *  {@code TimeMonths} / {@code TimeWeeks} / {@code TimeDays} /
   *  {@code TimeHours} / {@code TimeMinutes} / {@code TimeSeconds} /
   *  {@code TimeUndefined} / {@code Regular}. Set from
   *  {@code <Attribute levelType="...">} in the schema. saiku#1221 added
   *  the JSON surface so the SPA can detect time levels deterministically
   *  rather than substring-matching captions. */
  levelType?: string;
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
  /** Mondrian-native dimension type from olap4j {@code Dimension.Type}:
   *  one of {@code TIME} / {@code MEASURE} / {@code OTHER}. Set from
   *  {@code <Dimension type="TIME">} in the schema. saiku#1221 added
   *  this so the SPA's time-filter detection has a dimension-level
   *  signal independent of caption text. */
  dimensionType?: string;
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
