export type AuthStrategyKind = "basic" | "oidc";
export interface ServerEntry { id: string; label: string; baseUrl: string; strategy: AuthStrategyKind; }
export interface BasicCredential { kind: "basic"; username: string; password: string; }
export interface OidcCredential { kind: "oidc"; accessToken: string; refreshToken?: string; expiresAt?: number; }
export type Credential = BasicCredential | OidcCredential;
export interface OidcInfo { issuer: string; clientId: string; authorizeUrl?: string; }
export interface Capabilities { basic: boolean; oidc?: OidcInfo; }

export interface Cube {
  connectionName: string; catalog: string; schema: string;
  cubeName: string; cubeCaption: string; defaultMeasure?: string; measureCount?: number;
}
export const cubeId = (c: Cube) => `${c.connectionName}/${c.catalog}/${c.schema}/${c.cubeName}`;

export interface MeasureMeta { name: string; uniqueName: string; description?: string; }
export interface LevelMeta { name: string; uniqueName: string; sampleMembers?: string[]; cardinality?: number; }
export interface CubeSchema {
  cubeName: string;
  measures: Record<string, MeasureMeta>;
  dimensions: Record<string, { hierarchies: Record<string, { levels: Record<string, LevelMeta> }> }>;
}

export interface DimPick { dimension: string; hierarchy: string; level: string; }
export interface FilterPick extends DimPick { members: string[]; }
export interface WizardState { cubeId: string; measures: string[]; dimensions: DimPick[]; filters: FilterPick[]; }

export interface AiQueryRequest {
  cube: string;
  measures: { name: string }[];
  rows: DimPick[];
  filters?: { dimension: string; hierarchy: string; level: string; op: string; members: string[] }[];
  nonEmpty: boolean;
}
export interface MeasureCell { value: number | null; formatted: string; unit: string | null; }
export interface AiQueryResponse {
  queryId: string | null;
  status: "SUCCESS" | "VALIDATION_ERROR" | "EXECUTION_ERROR" | "PERMISSION_DENIED" | string;
  format?: "records" | "matrix";
  metadata?: { measures: string[]; generatedMdx?: string; freshness?: { computedAt?: string; cached?: boolean } };
  data?: Record<string, string | MeasureCell>[];
  totalRows?: number; runtimeMs?: number;
  error?: string | null; field?: string | null; available?: string[];
}
export interface Grid { columns: string[]; rows: (string | number | null)[][]; }
