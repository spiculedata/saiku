/*
 * Which app the App Builder loads, and when (saiku#1766).
 *
 * The route component re-renders for reasons that have nothing to do with the
 * app it is showing (a filter changes, a store writes, the path prop is
 * re-derived), so the load can't simply hang off a reactive read of the path —
 * and it must not be duplicated into a mount hook plus a path-watching effect
 * either. That is exactly what the App Editor used to do, and the second
 * response landed after the deep-link restore had already run: it reset the app
 * to page 0 and the URL mirror rewrote `?p=` to page 1's id, so a shared page
 * link opened the Overview and destroyed itself in the process.
 *
 * One load per distinct path, expressed as a tiny stateful function rather than
 * a pair of lifecycle callbacks, is the whole fix — and being plain
 * TypeScript it is directly testable.
 */

export interface AppLoadRequest {
	/** Repository path of the .saikuapp to load, e.g. `homes/admin/demo.saikuapp`. */
	path: string;
	/** Page id carried by the `?p=` deep link, or null when the link names no
	 *  page. Resolved against the loaded app by the store. */
	pageId: string | null;
}

/** Build a loader that invokes `request` once per distinct, non-empty `path`.
 *
 *  `pageId` is passed through on every call rather than latched: a client-side
 *  navigation to a different app brings its own page id with it, and the URL is
 *  the only place that knows which. */
export function createAppLoader(
	request: (req: AppLoadRequest) => void
): (path: string, pageId: string | null) => void {
	let lastPath: string | null = null;
	return (path, pageId) => {
		if (!path || path === lastPath) return;
		lastPath = path;
		request({ path, pageId });
	};
}
