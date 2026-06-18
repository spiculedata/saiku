import type { AiQueryResponse, Grid, MeasureCell } from "./types";

const isMeasure = (v: unknown): v is MeasureCell =>
  typeof v === "object" && v !== null && "value" in (v as object);

export function toGrid(resp: AiQueryResponse): Grid {
  const data = resp.data ?? [];
  if (data.length === 0) return { columns: [], rows: [] };
  const measureSet = new Set(resp.metadata?.measures ?? []);
  const keys = Object.keys(data[0]);
  const columns = keys;
  const rows = data.map((rec) =>
    keys.map((k) => {
      const v = rec[k];
      if (measureSet.has(k) || isMeasure(v)) {
        const cell = v as MeasureCell;
        return cell.value ?? cell.formatted ?? null;
      }
      return v as string;
    })
  );
  return { columns, rows };
}
