/*
 * Dashboard export (issue #929) — CLIENT-SIDE PNG / PDF export.
 *
 * The team chose a browser-only design over the issue's original
 * headless-Chrome server proposal to avoid the SSRF / infra surface of a
 * server-side renderer. Everything here runs in the user's browser:
 *
 *   - PNG: rasterise the live dashboard grid element with html-to-image
 *     (`toPng`), then trigger an <a download> click.
 *   - PDF: rasterise the same element to a PNG data URL, then place it in a
 *     jsPDF A4 document, slicing the image across pages when it is taller
 *     than one page (see {@link paginate}).
 *
 * Both outputs include a small export-only header (dashboard title + a
 * one-line applied-filter summary) and footer (timestamp + "Saiku"
 * branding). The header / footer are NOT part of the live DOM: we build a
 * throwaway off-screen wrapper that clones the grid between the two bands,
 * capture that, then discard it — the live DOM is never mutated, so theme,
 * scroll position and interactivity are untouched.
 *
 * This module keeps the heavy DOM-capture side effects thin and isolates
 * the pure logic (filename building, filter-summary string, pagination
 * math, member-caption parsing) so it can be unit-tested without a DOM or
 * the html-to-image / jspdf libraries — see dashboardExport.test.ts.
 */

import type { ActiveFilter } from "$lib/stores/activeFilters.svelte";

export type ExportFormat = "png" | "pdf";

/** Metadata that decorates the exported image's header / footer. */
export interface ExportMeta {
  /** Dashboard display name; falls back to "Dashboard" when blank. */
  title: string;
  /** The active filter set, used to render the one-line filter summary. */
  filters: ActiveFilter[];
  /** Capture instant. Defaults to `new Date()` at call time; injectable
   *  for deterministic tests. */
  now?: Date;
}

/* --------------------------------------------------------------------------
 * Pure helpers (unit-tested) — no DOM, no external libs.
 * ------------------------------------------------------------------------ */

/**
 * Turn an MDX unique name into a human-friendly caption: the last
 * bracketed segment, brackets stripped. `[Time].[Time].[1997].[Q2]` →
 * `Q2`. Non-bracketed input is returned trimmed and unchanged.
 */
export function memberCaption(uniqueName: string): string {
  const matches = uniqueName.match(/\[([^\]]*)\]/g);
  if (matches && matches.length > 0) {
    const last = matches[matches.length - 1];
    return last.slice(1, -1);
  }
  return uniqueName.trim();
}

/**
 * Build a single-line summary of the applied filters for the export
 * header, e.g. `Store: USA, Canada · Time: Q2`. Filters with no selected
 * members ("any") are skipped — they don't slice anything. Returns an
 * empty string when nothing is active.
 *
 * Each filter is labelled by its dimension; members are de-duplicated,
 * captioned, and capped (excess collapsed to "+N more") so the line stays
 * readable for wide selections.
 */
export function buildFilterSummary(
  filters: ActiveFilter[],
  opts: { maxMembersPerFilter?: number } = {},
): string {
  const maxMembers = opts.maxMembersPerFilter ?? 3;
  const parts: string[] = [];
  for (const af of filters) {
    const members = af.filter.members ?? [];
    if (members.length === 0) continue; // "any" — no-op slice
    const dim = memberCaption(af.filter.dimension) || af.filter.dimension;
    const captions = Array.from(new Set(members.map(memberCaption)));
    let value: string;
    if (captions.length > maxMembers) {
      const shown = captions.slice(0, maxMembers).join(", ");
      value = `${shown} +${captions.length - maxMembers} more`;
    } else {
      value = captions.join(", ");
    }
    parts.push(`${dim}: ${value}`);
  }
  return parts.join(" · ");
}

/** Sanitise a dashboard title into a safe filename stem (no extension). */
export function sanitiseFilenameStem(title: string): string {
  const cleaned = (title || "dashboard")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return cleaned || "dashboard";
}

/** Format a timestamp for the export filename: `YYYYMMDD-HHmm` (local). */
export function timestampStamp(d: Date): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return (
    `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}` +
    `-${p(d.getHours())}${p(d.getMinutes())}`
  );
}

/**
 * Compose the download filename, e.g.
 * `sales-overview-20260607-1530.png`.
 */
export function buildExportFilename(title: string, format: ExportFormat, now: Date): string {
  return `${sanitiseFilenameStem(title)}-${timestampStamp(now)}.${format}`;
}

/** A single output page: the source-image y-offset and the slice height,
 *  both in image (source) pixels. */
export interface PageSlice {
  /** y offset into the source image, in source px. */
  sourceY: number;
  /** height of this slice, in source px. */
  sliceHeight: number;
}

/**
 * Slice a tall captured image into page-height bands for multi-page PDF
 * assembly. All inputs are in source-image pixels; the caller maps each
 * slice onto the PDF page via the shared width scale.
 *
 *   - `imageHeight` ≤ `pageContentHeight` → a single full-image page.
 *   - otherwise → consecutive non-overlapping bands top-to-bottom; the
 *     last band is whatever remains.
 *
 * `pageContentHeight` is the page height (in source px) available for the
 * image after the caller reserves any margins — derived by the caller from
 * the PDF page size and the width scale so 1 source px maps consistently.
 */
export function paginate(imageHeight: number, pageContentHeight: number): PageSlice[] {
  if (imageHeight <= 0 || pageContentHeight <= 0) {
    return [{ sourceY: 0, sliceHeight: Math.max(0, imageHeight) }];
  }
  if (imageHeight <= pageContentHeight) {
    return [{ sourceY: 0, sliceHeight: imageHeight }];
  }
  const slices: PageSlice[] = [];
  let y = 0;
  while (y < imageHeight) {
    const sliceHeight = Math.min(pageContentHeight, imageHeight - y);
    slices.push({ sourceY: y, sliceHeight });
    y += sliceHeight;
  }
  return slices;
}

/* --------------------------------------------------------------------------
 * DOM capture (thin side-effecting layer) — exercised by the verifier's
 * build / manual test, not the unit suite.
 * ------------------------------------------------------------------------ */

/** Read the current theme background so the export bands blend with the
 *  rendered tiles instead of flashing white in dark mode. Falls back to
 *  white / black on SSR or when the vars are absent. */
function readThemeColors(el: HTMLElement): { bg: string; fg: string; muted: string } {
  if (typeof window === "undefined") {
    return { bg: "#ffffff", fg: "#111111", muted: "#666666" };
  }
  const cs = window.getComputedStyle(el);
  const bg = cs.getPropertyValue("--bg").trim() || cs.backgroundColor || "#ffffff";
  const fg = cs.getPropertyValue("--fg").trim() || cs.color || "#111111";
  const muted = cs.getPropertyValue("--fg-muted").trim() || "#666666";
  return { bg, fg, muted };
}

/**
 * Build an off-screen wrapper that stacks an export header, a clone of the
 * dashboard grid, and an export footer — sized to the grid's full scroll
 * size so nothing is clipped. The wrapper is appended to <body> off-screen
 * for capture and must be removed by the caller (returned `cleanup`).
 *
 * We clone the grid (rather than wrapping the live node) so the live
 * layout / scroll position / ResizeObserver are never disturbed. ECharts
 * canvases serialise fine because html-to-image rasterises <canvas>
 * content directly.
 */
function buildCaptureWrapper(grid: HTMLElement, meta: ExportMeta): { node: HTMLElement; cleanup: () => void } {
  const now = meta.now ?? new Date();
  const { bg, fg, muted } = readThemeColors(grid);

  const wrapper = document.createElement("div");
  wrapper.style.position = "fixed";
  wrapper.style.top = "0";
  wrapper.style.left = "-100000px"; // off-screen but laid out (not display:none)
  wrapper.style.zIndex = "-1";
  wrapper.style.background = bg;
  wrapper.style.color = fg;
  wrapper.style.boxSizing = "border-box";
  wrapper.style.padding = "16px";
  // Match the live grid's rendered width so tile layout reflows identically.
  const gridWidth = grid.getBoundingClientRect().width || grid.clientWidth || 1200;
  wrapper.style.width = `${Math.round(gridWidth)}px`;

  // --- header -------------------------------------------------------------
  const header = document.createElement("div");
  header.style.borderBottom = `1px solid ${muted}`;
  header.style.paddingBottom = "8px";
  header.style.marginBottom = "12px";

  const titleEl = document.createElement("div");
  titleEl.textContent = meta.title?.trim() || "Dashboard";
  titleEl.style.fontSize = "18px";
  titleEl.style.fontWeight = "600";
  header.appendChild(titleEl);

  const summary = buildFilterSummary(meta.filters);
  if (summary) {
    const summaryEl = document.createElement("div");
    summaryEl.textContent = summary;
    summaryEl.style.fontSize = "12px";
    summaryEl.style.color = muted;
    summaryEl.style.marginTop = "4px";
    header.appendChild(summaryEl);
  }
  wrapper.appendChild(header);

  // --- grid clone ---------------------------------------------------------
  const clone = grid.cloneNode(true) as HTMLElement;
  // Let the clone grow to its full content height (the live grid scrolls).
  clone.style.overflow = "visible";
  clone.style.height = "auto";
  clone.style.maxHeight = "none";
  clone.style.flex = "none";
  // cloneNode(true) copies <canvas> ELEMENTS but NOT their drawn bitmap, so
  // every ECharts tile would rasterise blank. Repaint each clone canvas from
  // its live counterpart (same document order) before capture. drawImage from
  // a 2D canvas is safe; guard per-canvas so one tainted/zero-size canvas
  // can't abort the whole export.
  const liveCanvases = grid.querySelectorAll("canvas");
  const cloneCanvases = clone.querySelectorAll("canvas");
  for (let i = 0; i < cloneCanvases.length; i++) {
    const src = liveCanvases[i];
    const dst = cloneCanvases[i] as HTMLCanvasElement | undefined;
    if (!src || !dst || src.width === 0 || src.height === 0) continue;
    dst.width = src.width;
    dst.height = src.height;
    try {
      dst.getContext("2d")?.drawImage(src, 0, 0);
    } catch {
      // Cross-origin-tainted source canvas — skip it; toPng surfaces the
      // wider failure if the whole capture is blocked.
    }
  }
  wrapper.appendChild(clone);

  // --- footer -------------------------------------------------------------
  const footer = document.createElement("div");
  footer.style.borderTop = `1px solid ${muted}`;
  footer.style.paddingTop = "8px";
  footer.style.marginTop = "12px";
  footer.style.display = "flex";
  footer.style.justifyContent = "space-between";
  footer.style.fontSize = "11px";
  footer.style.color = muted;

  const tsEl = document.createElement("span");
  tsEl.textContent = now.toLocaleString();
  const brandEl = document.createElement("span");
  brandEl.textContent = "Saiku";
  brandEl.style.fontWeight = "600";
  footer.appendChild(tsEl);
  footer.appendChild(brandEl);
  wrapper.appendChild(footer);

  document.body.appendChild(wrapper);

  return { node: wrapper, cleanup: () => wrapper.remove() };
}

/** Trigger a browser download of a data URL (or blob URL). */
function triggerDownload(dataUrl: string, filename: string): void {
  const a = document.createElement("a");
  a.href = dataUrl;
  a.download = filename;
  a.style.display = "none";
  document.body.appendChild(a);
  a.click();
  a.remove();
}

/**
 * Export the dashboard grid as a PNG, including the export header /
 * footer, and trigger a download. Resolves once the download has been
 * triggered.
 */
export async function exportDashboardPng(grid: HTMLElement, meta: ExportMeta): Promise<void> {
  const { toPng } = await import("html-to-image");
  const { node, cleanup } = buildCaptureWrapper(grid, meta);
  try {
    const { bg } = readThemeColors(grid);
    const dataUrl = await toPng(node, {
      pixelRatio: 2,
      backgroundColor: bg || undefined,
      cacheBust: true,
    });
    triggerDownload(dataUrl, buildExportFilename(meta.title, "png", meta.now ?? new Date()));
  } finally {
    cleanup();
  }
}

/**
 * Export the dashboard grid as a (possibly multi-page) A4 PDF, including
 * the export header / footer, and trigger a download. The captured image
 * is sliced across pages by {@link paginate} when it is taller than one
 * page; each slice is drawn from an off-screen canvas so jsPDF receives a
 * single-page image per page.
 */
export async function exportDashboardPdf(grid: HTMLElement, meta: ExportMeta): Promise<void> {
  const [{ toPng }, { jsPDF }] = await Promise.all([import("html-to-image"), import("jspdf")]);
  const { node, cleanup } = buildCaptureWrapper(grid, meta);
  try {
    const { bg } = readThemeColors(grid);
    const dataUrl = await toPng(node, {
      pixelRatio: 2,
      backgroundColor: bg || undefined,
      cacheBust: true,
    });

    const img = await loadImage(dataUrl);
    const imgW = img.naturalWidth;
    const imgH = img.naturalHeight;

    // A4 portrait in pt. Margin keeps the content off the page edges.
    const pdf = new jsPDF({ orientation: "portrait", unit: "pt", format: "a4" });
    const pageW = pdf.internal.pageSize.getWidth();
    const pageH = pdf.internal.pageSize.getHeight();
    const margin = 24;
    const contentW = pageW - margin * 2;
    const contentH = pageH - margin * 2;

    // Width scale: source px → pt. One source px maps to `scale` pt.
    const scale = contentW / imgW;
    // Available page height expressed back in SOURCE px so paginate() and
    // the slice canvas share one coordinate system.
    const pageContentHeightPx = contentH / scale;

    const slices = paginate(imgH, pageContentHeightPx);
    slices.forEach((slice, i) => {
      if (i > 0) pdf.addPage();
      const sliceCanvas = document.createElement("canvas");
      sliceCanvas.width = imgW;
      sliceCanvas.height = Math.round(slice.sliceHeight);
      const ctx = sliceCanvas.getContext("2d");
      if (ctx) {
        if (bg) {
          ctx.fillStyle = bg;
          ctx.fillRect(0, 0, sliceCanvas.width, sliceCanvas.height);
        }
        ctx.drawImage(
          img,
          0,
          slice.sourceY,
          imgW,
          slice.sliceHeight,
          0,
          0,
          imgW,
          slice.sliceHeight,
        );
      }
      const sliceUrl = sliceCanvas.toDataURL("image/png");
      const drawH = slice.sliceHeight * scale;
      pdf.addImage(sliceUrl, "PNG", margin, margin, contentW, drawH, undefined, "FAST");
    });

    pdf.save(buildExportFilename(meta.title, "pdf", meta.now ?? new Date()));
  } finally {
    cleanup();
  }
}

/** Promise wrapper around HTMLImageElement load. */
function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = reject;
    img.src = src;
  });
}

/** Dispatch to the right exporter for a chosen format. */
export async function exportDashboard(
  grid: HTMLElement,
  meta: ExportMeta,
  format: ExportFormat,
): Promise<void> {
  if (format === "pdf") {
    await exportDashboardPdf(grid, meta);
  } else {
    await exportDashboardPng(grid, meta);
  }
}
