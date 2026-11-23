/*
 * Which mode an app opens in (saiku#2009).
 *
 * A saved app opens in read-only "view" — the clean published experience. A
 * freshly created app has nothing to view, so the create flow navigates with
 * `?edit=1` and the author lands in edit mode, where the "add your first tile"
 * guidance lives (saiku#1636). Kiosk (`?chrome=none`) always wins.
 */

export type AppMode = 'edit' | 'view';

const EDIT_ON_OPEN_PARAM = 'edit';
const EDIT_ON_OPEN_VALUE = '1';

/** Route to a newly created app, opening it in edit mode. */
export function newAppHref(base: string, path: string): string {
	return `${base}/apps/${path}?${EDIT_ON_OPEN_PARAM}=${EDIT_ON_OPEN_VALUE}`;
}

/** The mode an app should open in, given the route's query string. */
export function initialAppMode(params: Pick<URLSearchParams, 'get'>): AppMode {
	if (params.get('chrome') === 'none') return 'view';
	return params.get(EDIT_ON_OPEN_PARAM) === EDIT_ON_OPEN_VALUE ? 'edit' : 'view';
}
