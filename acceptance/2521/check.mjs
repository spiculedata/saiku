import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const frmSrc = readFileSync(
  join(root, 'saiku-core/saiku-service/src/main/java/org/saiku/repository/FilesystemRepositoryManager.java'),
  'utf8'
);
const resSrc = readFileSync(
  join(root, 'saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/BasicRepositoryResource2.java'),
  'utf8'
);

const failures = [];

// C2: the guard lives in the repository manager, resolves first, decides after.
const guardStart = frmSrc.indexOf('private void requireAdminForDatasourceDescriptor(');
if (guardStart < 0) {
  failures.push('C2: requireAdminForDatasourceDescriptor must exist in FilesystemRepositoryManager');
} else {
  const guardEnd = frmSrc.indexOf('\n    }\n', guardStart);
  const guard = frmSrc.slice(guardStart, guardEnd);
  const resolve = guard.indexOf('resolveWithinDatadir(');
  const decide = guard.indexOf('isDatasourceDescriptorPath(');
  if (resolve < 0) failures.push('C1/C2: the guard must resolve the request path (resolveWithinDatadir) before deciding');
  if (decide < 0) failures.push('C2: the guard must decide on isDatasourceDescriptorPath');
  if (resolve >= 0 && decide >= 0 && resolve > decide) {
    failures.push('C1/C2: the path must be RESOLVED before the descriptor check, so x.sds/ cannot pass while x.sds is written');
  }
}

// C2: save and BOTH ends of move go through the guard.
const saveStart = frmSrc.indexOf('public Object saveFile(Object file, String path');
const moveStart = frmSrc.indexOf('public void moveFile(String source, String target');
if (saveStart < 0 || moveStart < 0) {
  failures.push('C2: saveFile/moveFile not found in FilesystemRepositoryManager');
} else {
  const save = frmSrc.slice(saveStart, frmSrc.indexOf('\n    }\n', saveStart));
  const move = frmSrc.slice(moveStart, frmSrc.indexOf('\n    }\n', moveStart));
  if (!/requireAdminForDatasourceDescriptor\(\s*path,/.test(save)) {
    failures.push('C2: saveFile must gate the saved path with requireAdminForDatasourceDescriptor');
  }
  const guardSource = move.indexOf('requireAdminForDatasourceDescriptor(source, roles)');
  const guardTarget = move.indexOf('requireAdminForDatasourceDescriptor(target, roles)');
  if (guardSource < 0 || guardTarget < 0) {
    failures.push('C2: moveFile must gate BOTH ends (source and target) with requireAdminForDatasourceDescriptor');
  } else {
    // The guards must run before any filesystem mutation of the target.
    const mkdirs = move.indexOf('mkdirs()');
    if (mkdirs >= 0 && guardTarget > mkdirs) {
      failures.push('C1: the move guard must run before any destination directory is created');
    }
  }
}

// C1/C4: the predicate both guards share collapses trailing separators and dot segments.
for (const [label, src] of [
  ['repository manager', frmSrc],
  ['web resource', resSrc],
]) {
  const predStart = src.indexOf('static boolean isDatasourceDescriptorPath(');
  if (predStart < 0) {
    failures.push(`C4: isDatasourceDescriptorPath missing in ${label}`);
    continue;
  }
  const pred = src.slice(predStart, src.indexOf('\n    }\n', predStart));
  if (!pred.includes('collapsed')) {
    failures.push(`C4: the ${label} predicate must collapse trailing separators and dot segments (x.sds/ resolves to x.sds)`);
  }
}

// C3: the loader lists only the /datasources subtree.
const loadStart = frmSrc.indexOf('public List<DataSource> getAllDataSources()');
if (loadStart < 0) {
  failures.push('C3: getAllDataSources not found');
} else {
  const load = frmSrc.slice(loadStart, frmSrc.indexOf('\n    }\n', loadStart));
  if (!load.includes('listFiles(new File(getDatadir() + "datasources")')) {
    failures.push('C3: getAllDataSources must list *.sds only under the /datasources subtree');
  }
}

if (failures.length) {
  failures.forEach((f) => process.stderr.write(`${f}\n`));
  process.exit(1);
}
process.stdout.write('descriptor writes are gated on the resolved name and the loader reads only /datasources\n');
