import { resolve } from '$app/paths';

/**
 * The configured `kit.paths.base` (e.g. `/ui`, or `''` at the root), with no
 * trailing slash.
 *
 * SvelteKit 3 removed the `base` export from `$app/paths` in favour of
 * `resolve()`. Most call sites here build `${base}/some/path` strings or hand
 * `base` to URL helpers, so this keeps that shape: `resolve('/')` is
 * `<base>/`, and we drop the slash. Call it at use time (not module load) so
 * it always reflects the running app.
 */
export function basePath(): string {
	return resolve('/').replace(/\/$/, '');
}
