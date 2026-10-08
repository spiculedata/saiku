import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (path) => readFileSync(join(root, path), 'utf8');
const rootPom = read('pom.xml');
const bomPom = read('saiku-bom/pom.xml');
const launcherPom = read('saiku-launcher/pom.xml');

function jacksonVersion(pom) {
  return pom.match(/<jackson\.version>\s*([^<\s]+)\s*<\/jackson\.version>/)?.[1];
}

const databind = launcherPom.match(
  /<dependency>\s*<groupId>com\.fasterxml\.jackson\.core<\/groupId>\s*<artifactId>jackson-databind<\/artifactId>\s*<version>([^<]+)<\/version>/
)?.[1]?.trim();

const failures = [];
if (jacksonVersion(rootPom) !== '2.22.3') failures.push('C1: root jackson.version must be 2.22.3');
if (databind !== '${jackson.version}') failures.push('C1: launcher jackson-databind must use ${jackson.version}');
if (jacksonVersion(bomPom) !== jacksonVersion(rootPom)) failures.push('C2: BOM and root jackson.version must match');

if (failures.length) {
  failures.forEach((failure) => process.stderr.write(`${failure}\n`));
  process.exit(1);
}
process.stdout.write('launcher Jackson databind uses the patched version aligned with the BOM\n');
