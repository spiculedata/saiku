#!/usr/bin/env node
/*
 * Stage the contents of `embed-react-npm/` for `npm publish` by:
 *   1. Copying the hand-authored index.js + index.d.ts + README.md
 *      from src/embed-react/ in.
 *   2. Rewriting the version in package.json to match the root
 *      saiku-ui version, and pinning the base @concepttocloud/saiku-embed
 *      dependency to the same version so consumers can't accidentally
 *      pair mismatched majors.
 *
 * This mirrors scripts/stage-embed-npm.mjs — same shape, different
 * source dir. The release.yml workflow runs
 * `npm publish embed-react-npm --access public` from saiku-ui/ on tag
 * push, alongside the base bundle's publish.
 */
import { copyFileSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');
const src = resolve(root, 'src/embed-react');
const pkg = resolve(root, 'embed-react-npm');

function copy(source, dst) {
	if (!existsSync(source)) {
		console.error(`stage-embed-react-npm: missing ${source}`);
		process.exit(1);
	}
	copyFileSync(source, dst);
	console.log(`  ${source} -> ${dst}`);
}

console.log('staging embed-react-npm/');
copy(resolve(src, 'index.js'), resolve(pkg, 'index.js'));
copy(resolve(src, 'index.d.ts'), resolve(pkg, 'index.d.ts'));
copy(resolve(src, 'README.md'), resolve(pkg, 'README.md'));

const rootPkg = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8'));
const templatePath = resolve(pkg, 'package.template.json');
const embedPkg = JSON.parse(readFileSync(templatePath, 'utf8'));
// Mirror the saiku-ui version so npm releases track 1:1 with the base
// bundle. The template carries 0.0.0-PLACEHOLDER for both the package's
// own version AND its @concepttocloud/saiku-embed dep — we bump both to
// the same value so a consumer can never accidentally mix majors across
// the base bundle and the React wrapper.
embedPkg.version = rootPkg.version;
if (
	embedPkg.dependencies &&
	embedPkg.dependencies['@concepttocloud/saiku-embed'] === '0.0.0-PLACEHOLDER'
) {
	embedPkg.dependencies['@concepttocloud/saiku-embed'] = rootPkg.version;
}
const embedPkgPath = resolve(pkg, 'package.json');
writeFileSync(embedPkgPath, JSON.stringify(embedPkg, null, 2) + '\n');
console.log(`  version -> ${embedPkg.version}`);
console.log(`  base dep -> ${embedPkg.dependencies['@concepttocloud/saiku-embed']}`);
console.log('done');
