import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/**
 * Standalone package config — no SvelteKit here. `svelte-package` only needs
 * `preprocess` so the `lang="ts"` blocks in the components are stripped to
 * plain JS on the way into dist/, with .d.ts emitted alongside.
 *
 * @type {import('@sveltejs/vite-plugin-svelte').SvelteConfig}
 */
export default {
	preprocess: vitePreprocess()
};
