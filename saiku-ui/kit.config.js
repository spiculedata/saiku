import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

// SvelteKit 3 no longer reads svelte.config.js: this options object is passed
// to the `sveltekit(...)` Vite plugin in vite.config.ts. It lives in its own
// module so eslint.config.js can hand the same preprocess/compilerOptions to
// the Svelte parser.

const basePath = /** @type {'' | `/${string}`} */ (process.env.SAIKU_BASE_PATH ?? '');

/** @type {import('@sveltejs/kit/vite').Config} */
export default {
	preprocess: vitePreprocess(),
	adapter: adapter({
		pages: 'dist',
		assets: 'dist',
		fallback: 'index.html',
		precompress: false,
		strict: true
	}),
	prerender: {
		// The branding overlay is served at runtime from <saiku.home>/branding/
		// and is expected to 404 at build time; don't fail the build for it.
		// Match with/without the SAIKU_BASE_PATH=/ui prefix so both the
		// Maven-driven build and a bare `npm run build` succeed.
		handleHttpError: ({ path, referrer, message }) => {
			if (path.includes('/branding/')) return;
			throw new Error(`${message} (linked from ${referrer})`);
		}
	},
	// saiku#1917: the SPA shell carries SvelteKit's inline bootstrap <script>, so a header-level
	// `script-src 'self'` would block it. Hash mode stamps the policy into index.html as a
	// <meta http-equiv> with the bootstrap's sha256, which keeps `script-src` strict (no
	// 'unsafe-inline') without any server-side knowledge of the hash. frame-ancestors cannot be
	// set from <meta>; SecurityHeadersFilter sends it as a header.
	csp: {
		mode: 'hash',
		directives: {
			// saiku#2440: default-src 'self' is also the frame-src fallback, and it is what keeps a
			// foreign document out of a plugin tile's frame (an ancestor navigating that frame to
			// another origin, data: or blob: is refused). The tile's boot handshake is unauthenticated
			// by design (pluginBridge.routeFrameMessage), so widening frame-src beyond 'self' must
			// revisit it; e2e/plugin-tile-csp.spec.ts fails if this stops holding.
			'default-src': ['self'],
			'script-src': ['self'],
			'style-src': ['self', 'unsafe-inline'],
			'img-src': ['self', 'data:', 'blob:', 'https:'],
			'font-src': ['self', 'data:'],
			'worker-src': ['self', 'blob:'],
			'connect-src': ['self'],
			'object-src': ['none'],
			'base-uri': ['self'],
			// saiku#2430: form-action does not fall back to default-src.
			'form-action': ['self']
		}
	},
	paths: {
		base: basePath,
		relative: true
	},
	// SvelteKit 3 removed the built-in `$lib` in favour of the `#lib` subpath
	// import. `alias` is deprecated but still supported and keeps the ~390
	// existing `$lib/...` imports working; migrating them is a follow-up.
	alias: {
		$lib: 'src/lib'
	}
};
