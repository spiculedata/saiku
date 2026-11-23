#!/usr/bin/env node
/*
 * Stage the contents of `embed-npm/` for `npm publish` by:
 *   1. Copying the built bundles (dist/{saiku-embed,saiku-chart,
 *      saiku-dashboard}.js[.map] — issue #1103 split the latter two off
 *      the original all-in-one element) in.
 *   2. Copying the user-facing README from src/embed/README.md.
 *   3. Rewriting the version in package.json to match the root
 *      saiku-ui version, so the npm release tracks the launcher
 *      release one-to-one without a separate manual bump.
 *
 * Run after `build:embed` so the bundles exist. The release.yml
 * workflow then runs `npm publish embed-npm --access public` from
 * this dir on tag push.
 */
import { copyFileSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');
const dist = resolve(root, 'dist');
const pkg = resolve(root, 'embed-npm');

function copy(src, dst) {
	if (!existsSync(src)) {
		console.error(`stage-embed-npm: missing ${src} — did you run \`build:embed\`?`);
		process.exit(1);
	}
	copyFileSync(src, dst);
	console.log(`  ${src} -> ${dst}`);
}

console.log('staging embed-npm/');
// Keep in sync with the ENTRIES list in scripts/build-embed.mjs.
for (const bundle of ['saiku-embed', 'saiku-chart', 'saiku-dashboard']) {
	copy(resolve(dist, `${bundle}.js`), resolve(pkg, `${bundle}.js`));
	copy(resolve(dist, `${bundle}.js.map`), resolve(pkg, `${bundle}.js.map`));
}
copy(resolve(root, 'src/embed/README.md'), resolve(pkg, 'README.md'));

const rootPkg = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8'));
const templatePath = resolve(pkg, 'package.template.json');
const embedPkg = JSON.parse(readFileSync(templatePath, 'utf8'));
// Mirror the saiku-ui version so npm releases track the launcher 1:1.
// The template carries 0.0.0-PLACEHOLDER and is the only committed
// manifest; the generated embed-npm/package.json is .gitignore'd so
// every build produces a fresh, versioned copy.
embedPkg.version = rootPkg.version;
const embedPkgPath = resolve(pkg, 'package.json');
writeFileSync(embedPkgPath, JSON.stringify(embedPkg, null, 2) + '\n');
console.log(`  version -> ${embedPkg.version}`);
console.log('done');
