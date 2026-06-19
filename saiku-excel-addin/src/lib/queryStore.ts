import type { AiQueryRequest } from "./types";
import type { Registry } from "./serverRegistry";
declare const Office: any;

export interface SavedQuery { id: string; serverId: string; title: string; sheet: string; request: AiQueryRequest; }
const QKEY = "saiku.queries";
const RKEY = "saiku.registry";

const settings = () => Office.context.document.settings;
const save = () => new Promise<void>((res, rej) =>
  settings().saveAsync((r: { status: string }) => (r.status === "succeeded" ? res() : rej(new Error("save failed")))));

export async function listQueries(): Promise<SavedQuery[]> {
  return (settings().get(QKEY) as SavedQuery[]) ?? [];
}
export async function saveQuery(q: SavedQuery): Promise<void> {
  const list = (await listQueries()).filter((x) => x.id !== q.id);
  list.push(q);
  settings().set(QKEY, list);
  await save();
}
export async function removeQuery(id: string): Promise<void> {
  settings().set(QKEY, (await listQueries()).filter((x) => x.id !== id));
  await save();
}
export async function loadRegistry(): Promise<Registry> {
  return (settings().get(RKEY) as Registry) ?? { servers: [], activeId: null };
}
export async function saveRegistry(r: Registry): Promise<void> {
  settings().set(RKEY, r);
  await save();
}

/** Everything persisted to the workbook (for the "no secrets in workbook" invariant test). */
export function workbookPayload(): { registry: unknown; queries: unknown } {
  return { registry: settings().get(RKEY), queries: settings().get(QKEY) };
}
