/// <reference types="node" />
/// <reference types="vitest/config" />
import { sveltekit } from '@sveltejs/kit/vite';
import kitConfig from './kit.config.js';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig, type Connect, type Plugin } from 'vite';
import { fileURLToPath } from 'node:url';
import { PLUGIN_FRAME_CSP, PLUGIN_FRAME_PATH } from './src/lib/dashboard/custom/pluginBridge.ts';

const API_TARGET = process.env.SAIKU_API ?? 'http://localhost:8080';

/**
 * saiku#2440: serve the plugin frame bootstrap with its own sandboxed policy under
 * `vite dev` / `vite preview`, as SecurityHeadersFilter does in the launcher, so the
 * mocked e2e suite exercises the same headers. Without it the frame would still get the
 * bootstrap's <meta> policy, but not the `sandbox` and `frame-ancestors` directives (a
 * <meta> policy cannot carry either).
 */
function pluginFrameHeaders(): Plugin {
	const frameCsp = `${PLUGIN_FRAME_CSP}; frame-ancestors 'self'`;
	const middleware: Connect.NextHandleFunction = (req, res, next) => {
		const path = (req.url ?? '').split('?')[0];
		if (path.endsWith(`/${PLUGIN_FRAME_PATH}`)) {
			res.setHeader('Content-Security-Policy', frameCsp);
		}
		next();
	};
	return {
		name: 'saiku-plugin-frame-headers',
		configureServer: (server) => void server.middlewares.use(middleware),
		configurePreviewServer: (server) => void server.middlewares.use(middleware)
	};
}

// Vitest config lives under `test` via module augmentation; we suppress the
// vite.UserConfig shape check so tsc doesn't require the vitest types here.
export default defineConfig({
	plugins: [pluginFrameHeaders(), tailwindcss(), sveltekit(kitConfig)],
	build: {
		rollupOptions: {
			// rolldown's pluginTimings check flags the long-running tailwindcss/sveltekit
			// plugin hooks as warnings and the build fails on them; the timings are
			// diagnostics, not defects, so suppress the check.
			checks: { pluginTimings: false },
			onwarn: (warning, handler) => {
				// SvelteKit 3 emits a config deprecation warning for `alias` on every
				// compiled module; migrating ~390 `$lib` imports to `#lib` is a
				// follow-up. Suppress so the build doesn't fail on it.
				if (warning.code?.includes('config_option_deprecated')) return;
				handler(warning);
			}
		}
	},
	server: {
		port: 5173,
		proxy: {
			'/rest': { target: API_TARGET, changeOrigin: true }
		}
	},
	resolve: {
		alias: {
			// Mirrored here for vitest so `$lib/...` imports resolve outside the
			// SvelteKit dev/build pipeline.
			$lib: fileURLToPath(new URL('./src/lib', import.meta.url))
		}
	},
	test: {
		environment: 'node',
		include: ['src/**/*.test.ts']
	}
});
