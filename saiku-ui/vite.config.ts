/// <reference types="node" />
/// <reference types="vitest/config" />
import { sveltekit } from '@sveltejs/kit/vite';
import kitConfig from './kit.config.js';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';
import { fileURLToPath } from 'node:url';

const API_TARGET = process.env.SAIKU_API ?? 'http://localhost:8080';

// Vitest config lives under `test` via module augmentation; we suppress the
// vite.UserConfig shape check so tsc doesn't require the vitest types here.
export default defineConfig({
	plugins: [tailwindcss(), sveltekit(kitConfig)],
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
