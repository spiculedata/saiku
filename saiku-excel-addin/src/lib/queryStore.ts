import type { AiQueryRequest } from "./types";
declare const Office: any;

export interface SavedQuery { id: string; title: string; sheet: string; request: AiQueryRequest; }
const KEY = "saiku.queries";

const settings = () => Office.context.document.settings;
const save = () => new Promise<void>((res, rej) =>
  settings().saveAsync((r: { status: string }) => (r.status === "succeeded" ? res() : rej(new Error("save failed")))));

export async function listQueries(): Promise<SavedQuery[]> {
  return (settings().get(KEY) as SavedQuery[]) ?? [];
}
export async function saveQuery(q: SavedQuery): Promise<void> {
  const list = (await listQueries()).filter((x) => x.id !== q.id);
  list.push(q);
  settings().set(KEY, list);
  await save();
}
export async function removeQuery(id: string): Promise<void> {
  settings().set(KEY, (await listQueries()).filter((x) => x.id !== id));
  await save();
}
