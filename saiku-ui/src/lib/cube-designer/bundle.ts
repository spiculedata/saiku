/**
 * Canvas schema designer — JSON bundle export/import.
 *
 * The canvas localStorage doc isn't portable — it lives in one
 * browser, under one origin. A bundle is the same SchemaCanvasState
 * wrapped in a versioned envelope with a discriminator tag so the
 * importer can tell what file it's looking at. Modelled after
 * pipecleaner's `_type` / `_version` / `_exportedAt` pattern so the
 * shape evolves cleanly.
 */
import type { SchemaCanvasState } from "./types.js";

const BUNDLE_TYPE = "saiku-canvas-bundle";
const BUNDLE_VERSION = 1;

export interface SchemaCanvasBundle {
  _type: typeof BUNDLE_TYPE;
  _version: number;
  _exportedAt: string;
  /** Connection the doc was authored against. The importer uses it
   *  as a hint only — the user can land it on any connection. */
  connectionId: string;
  doc: SchemaCanvasState;
}

export function exportBundle(state: SchemaCanvasState): SchemaCanvasBundle {
  return {
    _type: BUNDLE_TYPE,
    _version: BUNDLE_VERSION,
    _exportedAt: new Date().toISOString(),
    connectionId: state.connectionId,
    doc: state,
  };
}

export function serializeBundle(state: SchemaCanvasState): string {
  return JSON.stringify(exportBundle(state), null, 2);
}

export interface ImportedBundle {
  connectionId: string;
  doc: SchemaCanvasState;
}

/**
 * Parse + validate a JSON bundle. Throws with a human-readable message
 * if the file isn't a Saiku canvas bundle or is from a future schema
 * version we don't understand yet.
 */
export function parseBundle(json: string): ImportedBundle {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    throw new Error("That doesn't look like JSON.");
  }
  if (typeof parsed !== "object" || parsed === null) {
    throw new Error("Bundle is empty or malformed.");
  }
  const b = parsed as Partial<SchemaCanvasBundle>;
  if (b._type !== BUNDLE_TYPE) {
    throw new Error(
      `Not a Saiku canvas bundle (found _type="${String(b._type)}"). Expected "${BUNDLE_TYPE}".`,
    );
  }
  if (typeof b._version !== "number" || b._version > BUNDLE_VERSION) {
    throw new Error(
      `Bundle version ${String(b._version)} is newer than this dashboard knows about (max ${BUNDLE_VERSION}).`,
    );
  }
  if (!b.doc || typeof b.doc !== "object") {
    throw new Error("Bundle has no canvas doc inside.");
  }
  const doc = b.doc as SchemaCanvasState;
  if (doc.version !== 1) {
    throw new Error(`Canvas doc version ${doc.version} is not supported.`);
  }
  return {
    connectionId:
      typeof b.connectionId === "string" ? b.connectionId : doc.connectionId,
    doc,
  };
}
