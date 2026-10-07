#!/usr/bin/env node
/*
 * Build every embed Web Component bundle (<saiku-embed>, <saiku-chart>,
 * <saiku-dashboard> — issue #1103) by invoking `vite build --config
 * vite.config.embed.ts` once per tag, selecting the entry via the
 * EMBED_ENTRY env var that vite.config.embed.ts reads.
 *
 * Set on the CHILD PROCESS's env (spawnSync's `env` option), not via
 * shell syntax like `EMBED_ENTRY=x vite build` — that form doesn't work
 * on Windows cmd.exe, and this script is the thing CI and local Windows
 * dev both run, so it has to be portable without a `cross-env` dep.
 */
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');

// Keep in sync with the EMBED_ENTRIES map in vite.config.embed.ts.
const ENTRIES = ['saiku-embed', 'saiku-chart', 'saiku-dashboard'];

for (const entryKey of ENTRIES) {
	console.log(`build-embed: building ${entryKey}.js`);
	const result = spawnSync('npx', ['vite', 'build', '--config', 'vite.config.embed.ts'], {
		cwd: root,
		stdio: 'inherit',
		shell: process.platform === 'win32',
		env: { ...process.env, EMBED_ENTRY: entryKey }
	});
	if (result.status !== 0) {
		console.error(`build-embed: ${entryKey} build failed`);
		process.exit(result.status ?? 1);
	}
}
