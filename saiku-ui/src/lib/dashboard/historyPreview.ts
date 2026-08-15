/*
 * Helpers for the read-only history version preview (#947 follow-up). The
 * preview opens in a new tab at /ui/preview?dashboard=…&version=… so it renders
 * in its own JS context — its own dashboardStore — and never clobbers the
 * editing tab's state. Pure (no DOM) so it unit-tests cleanly.
 */

/** Build the preview URL for one archived version. `origin`+`base` are passed
 *  in to stay pure; omit them for a root-relative URL. */
export function buildHistoryPreviewUrl(
	dashboardPath: string,
	version: string,
	opts?: { origin?: string; base?: string }
): string {
	const origin = opts?.origin ?? '';
	const base = opts?.base ?? '';
	const q = new URLSearchParams({ dashboard: dashboardPath, version });
	return `${origin}${base}/preview?${q.toString()}`;
}

/** Extract `{dashboard, version}` from a preview URL's query string. Returns
 *  null if either is missing. Accepts a URL or a raw search string. */
export function parseHistoryPreviewParams(search: string | URLSearchParams): {
	dashboard: string;
	version: string;
} | null {
	const params = typeof search === 'string' ? new URLSearchParams(search) : search;
	const dashboard = params.get('dashboard');
	const version = params.get('version');
	if (!dashboard || !version) {
		return null;
	}
	return { dashboard, version };
}
