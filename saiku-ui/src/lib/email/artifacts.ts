/*
 * "Email me this" (issue TBD) — client-side artifact helpers.
 *
 * On Send, the browser produces the email's chart image and result PDF as
 * base64 strings (no download prompt, no server-side rendering). This
 * mirrors the browser-only design of `dashboard/dashboardExport.ts`
 * (rasterise the live DOM with html-to-image, place the PNG in a jsPDF
 * document) but returns the encoded payload instead of triggering a
 * download — see {@link resultToPdfBase64}.
 */

/**
 * Strip a `data:<mime>;base64,<payload>` (or `data:...;filename=...;base64,`)
 * prefix down to just the base64 payload. Returns the input unchanged if
 * there is no comma to split on.
 */
export function stripDataUriPrefix(dataUri: string): string {
	const idx = dataUri.indexOf(',');
	return idx === -1 ? dataUri : dataUri.slice(idx + 1);
}

/**
 * Read the live chart canvas (present only in chart view mode) and return
 * its PNG contents as base64, with no `data:` prefix. Returns `null` when
 * no chart canvas is rendered (e.g. table/pivot view mode).
 */
export function chartPngBase64(): string | null {
	const c = document.querySelector('.result-host canvas') as HTMLCanvasElement | null;
	if (!c) return null;
	return stripDataUriPrefix(c.toDataURL('image/png'));
}

/**
 * Rasterise `node` and lay it into a single-page-scaled jsPDF document,
 * returning the PDF's contents as base64 (no `data:` prefix). Returns
 * `null` when `node` is `null` (nothing to render).
 *
 * Mirrors the `toPng(node)` → `new jsPDF()` → `addImage` core of
 * `dashboardExport.ts`'s `exportDashboardPdf`, but resolves the encoded
 * document via `pdf.output('datauristring')` instead of calling
 * `pdf.save()`. This is a single-page rendering (the image is scaled to
 * fit one A4 page rather than paginated with `paginate()`) — the email
 * attachment is a single result/chart snapshot, not a multi-tile
 * dashboard, so pagination was judged unnecessary for v1.
 */
export async function resultToPdfBase64(node: HTMLElement | null): Promise<string | null> {
	if (!node) return null;

	const [{ toPng }, { jsPDF }] = await Promise.all([import('html-to-image'), import('jspdf')]);

	const dataUrl = await toPng(node, { pixelRatio: 2, cacheBust: true });
	const img = await loadImage(dataUrl);
	const imgW = img.naturalWidth;
	const imgH = img.naturalHeight;

	// A4 portrait in pt, matching dashboardExport's page setup.
	const pdf = new jsPDF({ orientation: 'portrait', unit: 'pt', format: 'a4' });
	const pageW = pdf.internal.pageSize.getWidth();
	const pageH = pdf.internal.pageSize.getHeight();
	const margin = 24;
	const contentW = pageW - margin * 2;
	const contentH = pageH - margin * 2;

	// Scale the image to fit within one page, preserving aspect ratio.
	const scale = Math.min(contentW / imgW, contentH / imgH);
	const drawW = imgW * scale;
	const drawH = imgH * scale;

	pdf.addImage(dataUrl, 'PNG', margin, margin, drawW, drawH, undefined, 'FAST');

	return stripDataUriPrefix(pdf.output('datauristring'));
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
