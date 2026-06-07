/*
 * Pure path / tree helpers for the dashboard catalogue's folder view
 * (#937). A dashboard's repository path IS its folder location — there is
 * no separate folder abstraction on the server. These helpers turn the
 * flat list of `.saikudash` repo paths into a navigable folder tree and
 * compute the new paths a move / rename implies, so the Svelte view can
 * call the existing repository move / save endpoints with the result.
 *
 * Everything here is pure (no DOM, no fetch) and unit-tested in
 * catalogueTree.test.ts — the view layer (DashboardIndex.svelte) keeps the
 * fetch + reactive state and delegates all path arithmetic here.
 */

/** Minimal per-dashboard shape the tree builder consumes. Mirrors the
 *  subset of CatalogueEntry the folder view needs to render a leaf. */
export interface CatalogueLeaf {
  /** Repo-relative path including the `.saikudash` extension, e.g.
   *  {@code homes/admin/Sales/q3.saikudash}. */
  path: string;
  /** Human-readable title (falls back to basename in the view). */
  title: string | null;
  /** Filename without the `.saikudash` extension. */
  basename: string;
}

/** A folder node in the catalogue tree. {@code path} is the repo-relative
 *  folder path (empty string for the synthetic root); {@code folders} and
 *  {@code dashboards} are its direct children. */
export interface FolderNode {
  /** Folder name (last path segment). Empty string for the root. */
  name: string;
  /** Full repo-relative folder path, e.g. {@code homes/admin/Sales}.
   *  Empty string for the synthetic root. */
  path: string;
  /** Child folders, sorted by name (case-insensitive). */
  folders: FolderNode[];
  /** Dashboards that live directly in this folder, sorted by display
   *  name (title ?? basename), case-insensitive. */
  dashboards: CatalogueLeaf[];
}

/** Split a repo path into clean segments, dropping empties so leading /
 *  trailing / doubled slashes don't produce blank folders. */
export function pathSegments(path: string): string[] {
  return path.split("/").filter((s) => s.length > 0);
}

/** The folder portion of a dashboard path (everything before the last
 *  segment). {@code homes/admin/Sales/q3.saikudash} → {@code homes/admin/Sales};
 *  a bare {@code q3.saikudash} → {@code ""} (the root). */
export function parentFolder(path: string): string {
  const segs = pathSegments(path);
  segs.pop();
  return segs.join("/");
}

/** The last path segment (file or folder name). */
export function lastSegment(path: string): string {
  const segs = pathSegments(path);
  return segs.length ? segs[segs.length - 1] : "";
}

/** Join a folder path and a child name into a clean repo path. A blank
 *  folder yields just the child (root-level). */
export function joinPath(folder: string, child: string): string {
  const f = pathSegments(folder).join("/");
  return f ? `${f}/${child}` : child;
}

/**
 * Build a folder tree from a flat list of dashboard leaves. Every
 * ancestor folder implied by a leaf's path is materialised even when no
 * dashboard sits directly in it, so e.g. a single
 * {@code Sales/2026/Q3/x.saikudash} produces the full Sales → 2026 → Q3
 * chain. Extra empty folders (folders that exist in the repo but hold no
 * dashboards) can be seeded via {@code extraFolders} so a freshly-created
 * empty folder still shows up.
 *
 * Returns the synthetic root node ({@code path === ""}); its
 * {@code folders} / {@code dashboards} are the top level.
 */
export function buildFolderTree(
  leaves: ReadonlyArray<CatalogueLeaf>,
  extraFolders: ReadonlyArray<string> = [],
): FolderNode {
  const root: FolderNode = { name: "", path: "", folders: [], dashboards: [] };
  // Index folder nodes by full path for O(1) descent / reuse.
  const byPath = new Map<string, FolderNode>([["", root]]);

  const ensureFolder = (folderPath: string): FolderNode => {
    const segs = pathSegments(folderPath);
    let current = root;
    let acc = "";
    for (const seg of segs) {
      acc = acc ? `${acc}/${seg}` : seg;
      let next = byPath.get(acc);
      if (!next) {
        next = { name: seg, path: acc, folders: [], dashboards: [] };
        byPath.set(acc, next);
        current.folders.push(next);
      }
      current = next;
    }
    return current;
  };

  for (const folder of extraFolders) ensureFolder(folder);

  for (const leaf of leaves) {
    const folder = ensureFolder(parentFolder(leaf.path));
    folder.dashboards.push(leaf);
  }

  sortFolderNode(root);
  return root;
}

/** Recursively sort a folder node's children: folders by name, dashboards
 *  by display name (title ?? basename), both case-insensitive. */
function sortFolderNode(node: FolderNode): void {
  node.folders.sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
  node.dashboards.sort((a, b) => {
    const ax = (a.title ?? a.basename).toLowerCase();
    const bx = (b.title ?? b.basename).toLowerCase();
    return ax.localeCompare(bx);
  });
  for (const child of node.folders) sortFolderNode(child);
}

/** Collect every folder path present in a tree (excluding the synthetic
 *  root), sorted depth-first by path. Used to populate a "move to folder"
 *  picker. */
export function collectFolderPaths(root: FolderNode): string[] {
  const out: string[] = [];
  const walk = (node: FolderNode): void => {
    for (const child of node.folders) {
      out.push(child.path);
      walk(child);
    }
  };
  walk(root);
  return out;
}

/**
 * Compute the new dashboard path when moving a dashboard into a target
 * folder. The filename (last segment) is preserved; only the parent
 * folder changes. {@code targetFolder === ""} moves it to the root.
 *
 *   moveDashboardPath("homes/admin/Sales/q3.saikudash", "homes/admin/Archive")
 *     → "homes/admin/Archive/q3.saikudash"
 */
export function moveDashboardPath(dashboardPath: string, targetFolder: string): string {
  const file = lastSegment(dashboardPath);
  return joinPath(targetFolder, file);
}

/**
 * Compute the path rewrites implied by renaming a folder. Every dashboard
 * whose path is under {@code oldFolder} (or is {@code oldFolder} itself,
 * for the degenerate case) gets its {@code oldFolder} prefix swapped for
 * the sibling folder named {@code newName}. Returns one {from, to} pair
 * per affected dashboard so the caller can issue a move per file — the
 * repository has no folder-rename primitive, so a folder rename is the
 * set of its descendants' moves.
 *
 * Paths outside {@code oldFolder} are left untouched (not returned).
 * {@code newName} is the new *leaf* name, not a full path — the folder
 * stays under the same parent.
 */
export function renameFolderMoves(
  oldFolder: string,
  newName: string,
  dashboardPaths: ReadonlyArray<string>,
): { from: string; to: string }[] {
  const oldSegs = pathSegments(oldFolder);
  if (oldSegs.length === 0) return []; // can't rename the root
  const cleanNew = lastSegment(newName).trim();
  if (!cleanNew) return []; // blank / whitespace-only name → no-op (#937)
  const parent = oldSegs.slice(0, -1).join("/");
  const newFolder = joinPath(parent, cleanNew);
  if (newFolder === pathSegments(oldFolder).join("/")) return []; // no-op rename

  const prefix = `${pathSegments(oldFolder).join("/")}/`;
  const out: { from: string; to: string }[] = [];
  for (const p of dashboardPaths) {
    const clean = pathSegments(p).join("/");
    if (clean.startsWith(prefix)) {
      const rest = clean.slice(prefix.length);
      out.push({ from: p, to: `${newFolder}/${rest}` });
    }
  }
  return out;
}

/** True when {@code child} is the same as, or nested under, {@code folder}.
 *  Used to stop the UI offering "move into myself / my descendant". */
export function isDescendantOf(child: string, folder: string): boolean {
  const c = pathSegments(child).join("/");
  const f = pathSegments(folder).join("/");
  if (f === "") return true; // everything is under the root
  return c === f || c.startsWith(`${f}/`);
}

/** Validate a proposed new folder name (single path segment). Returns an
 *  error key suffix string (for i18n) or {@code null} when valid. Rejects
 *  blanks, slashes, and the `.saikudash` reserved extension. */
export function validateFolderName(name: string): "empty" | "slash" | "reserved" | null {
  const t = name.trim();
  if (!t) return "empty";
  if (t.includes("/")) return "slash";
  if (t.toLowerCase().endsWith(".saikudash")) return "reserved";
  return null;
}
