/*
 * Generate the CycloneDX SBOM for the npm/UI half of what we ship (saiku#2000).
 *
 * Why a wrapper instead of calling cyclonedx-npm directly: SvelteKit 3 writes
 * its generated types/tsconfig to `node_modules/$app/` (SvelteKit's
 * `svelte-kit sync`, which `vite build` runs). `$app` isn't a package, so
 * `npm ls` reports it as "extraneous" and cyclonedx-npm aborts with
 * `Invalid name: "$app"`. We move that generated directory aside for the
 * duration of the SBOM run and put it back afterwards, so the SBOM stays
 * strict (we do NOT pass --ignore-npm-errors, which would also hide real
 * missing/invalid dependencies) and dev tooling keeps its types.
 */
import { spawnSync } from 'node:child_process';
import { existsSync, renameSync } from 'node:fs';
import { join } from 'node:path';

const GENERATED = join('node_modules', '$app');
const STASH = join('node_modules', '.saiku-sbom-app-stash');

const CYCLONEDX_ARGS = [
	'--output-file',
	'sbom/saiku-ui-sbom.cdx.json',
	'--output-format',
	'JSON',
	'--spec-version',
	'1.6',
	// `--omit dev` mirrors the Maven SBOM's test-scope exclusion: the SBOM
	// describes what ships, not the build/test harness.
	'--omit',
	'dev',
	'--mc-type',
	'application'
];

const isStashed = existsSync(GENERATED);
if (isStashed) renameSync(GENERATED, STASH);

let status = 1;
try {
	const result = spawnSync('cyclonedx-npm', CYCLONEDX_ARGS, {
		stdio: 'inherit',
		// npm puts node_modules/.bin on PATH; on Windows the shim is a .cmd.
		shell: process.platform === 'win32'
	});
	if (result.error) throw result.error;
	status = result.status ?? 1;
} finally {
	if (isStashed) renameSync(STASH, GENERATED);
}
process.exit(status);
