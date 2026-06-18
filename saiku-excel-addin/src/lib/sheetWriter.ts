import type { Grid } from "./types";
declare const Excel: any;

/** Write a grid as an Excel Table on a sheet (idempotent — safe to call on refresh); returns the table name. */
export async function writeTable(sheetName: string, grid: Grid): Promise<string> {
  const tableName = `Saiku_${sheetName}`.replace(/[^A-Za-z0-9_]/g, "_");
  return await Excel.run(async (ctx: any) => {
    let sheet = ctx.workbook.worksheets.getItemOrNullObject(sheetName);
    await ctx.sync();
    if (sheet.isNullObject) {
      sheet = ctx.workbook.worksheets.add(sheetName);
    } else {
      // Existing sheet (refresh): drop any prior Saiku table, then clear leftover content.
      const prior = ctx.workbook.tables.getItemOrNullObject(tableName);
      await ctx.sync();
      if (!prior.isNullObject) prior.delete();
      const used = sheet.getUsedRangeOrNullObject(true);
      await ctx.sync();
      if (!used.isNullObject) used.clear();
      await ctx.sync();
    }
    const range = sheet.getRangeByIndexes(0, 0, grid.rows.length + 1, grid.columns.length);
    range.values = [grid.columns, ...grid.rows];
    const table = sheet.tables.add(range, true /* hasHeaders */);
    table.name = tableName;
    sheet.activate();
    await ctx.sync();
    return tableName;
  });
}

/** Try to build a PivotTable from the table's range; resolves false if the host can't (Mac fallback). */
export async function buildPivot(sheetName: string, tableName: string): Promise<boolean> {
  try {
    await Excel.run(async (ctx: any) => {
      const src = ctx.workbook.tables.getItem(tableName).getRange();
      const dest = ctx.workbook.worksheets.add(`${sheetName}_Pivot`).getRange("A1");
      ctx.workbook.pivotTables.add(`Saiku_${tableName}_pivot`, src, dest);
      await ctx.sync();
    });
    return true;
  } catch {
    return false;
  }
}
