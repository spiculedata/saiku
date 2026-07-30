/*
 * Import helpers for the App Builder catalogue.
 *
 * Pure functions that map a chosen dashboard (or a raw name) into the
 * arguments the appDoc store's factory mutators expect. Kept side-effect
 * free so the catalogue UI stays a thin caller and the mapping is unit
 * testable in isolation.
 */
import type { Dashboard, DashboardLayout } from "$lib/api/dashboards";

/** Arguments for {@code appDoc.newAppFromDashboard(name, layout)}. */
export interface AppImportArgs {
  name: string;
  layout: DashboardLayout;
}

/** Derive a readable stem from a repository path (drops folders + the
 *  {@code .saikudash} / {@code .saikuapp} extension). Returns "" when the
 *  path has no usable last segment. */
export function pathStem(path: string): string {
  const last = path.split("/").filter(Boolean).pop() ?? "";
  return last.replace(/\.(saikudash|saikuapp)$/i, "");
}

/** Map a chosen dashboard to the arguments for
 *  {@code appDoc.newAppFromDashboard}. The app's default name is the
 *  dashboard's own name, falling back to a stem derived from its repo path
 *  (and finally a constant) so the create modal never opens name-less. The
 *  layout is passed through verbatim — {@code appFromDashboard} copies it
 *  into page 0's grid. */
export function importArgsFromDashboard(
  dashboard: Pick<Dashboard, "name" | "layout">,
  sourcePath = "",
): AppImportArgs {
  const name = dashboard.name?.trim() || pathStem(sourcePath) || "Imported app";
  return { name, layout: dashboard.layout };
}

/** Slugify a name into a filename stem (lowercase, dashes, trimmed). Mirrors
 *  the dashboard catalogue's slugify so app filenames read the same way. */
export function slugify(name: string): string {
  return (
    name
      .toLowerCase()
      .trim()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 60) || "app"
  );
}

/** Compose the {@code .saikuapp} repository path for a new app from a target
 *  folder + name. Trims stray slashes on the folder and derives the filename
 *  from the slugified name. */
export function composeAppPath(folder: string, name: string): string {
  const f = folder.replace(/^\/+|\/+$/g, "").replace(/\/{2,}/g, "/");
  const filename = `${slugify(name)}.saikuapp`;
  return f ? `${f}/${filename}` : filename;
}
