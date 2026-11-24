// The acceptance spec for spiculedata/saiku#2525, run as a command spec.
//
// The vulnerability: folder ACLs live in an `acl.json` sidecar inside the folder they protect,
// keyed by absolute path — and nothing reserved that name. Any caller who could write repository
// content could overwrite the sidecar and take ownership of the folder (reading files the owner
// marked PRIVATE and locking the owner out), any reader could read the sidecar (disclosing the
// server's absolute saiku-home path, every grantee and the names of private files), a DELETE of
// the sidecar wiped every share, and a folder move left every entry keyed under the old absolute
// path, dropping the protection behind it. An api spec cannot drive the form-param endpoints
// (POST /repository/resource, /repository/resource/move, /repository/resource/acl), so — like
// #2439 — this check makes those exact requests itself.
//
// Without ACCEPTANCE_BASE_URL (CI runs command specs against the source tree) it checks that the
// tree carries the fix:
//   S1: FilesystemRepositoryManager reserves acl.json — a sidecar guard on every content door
//       (save, internal save, binary save, remove, internal remove, read, internal reads, ACL
//       API, folder delete) and on both ends of move; a move that carries ACL state needs GRANT
//       on the source and re-keys the entries; listings skip the sidecar.
//   S2: BasicRepositoryResource2 answers the contracted 403 for sidecar reads/writes/deletes and
//       both ends of a move before the service layer is reached.
//   S3: the manager-level regression tests pin the refusals, the GRANT rule on protected moves,
//       re-keying after a permitted move, stale-entry removal on delete, and the owner's own
//       access.
// With ACCEPTANCE_BASE_URL it also checks a live launcher (admin session):
//   C1: the sidecar is not served as content (repository GET answers 403, any case or
//       trailing-slash spelling; the dashboards door does not answer 200) and the repository
//       tree does not list it (C5).
//   C2: writing, deleting and moving the sidecar as content are refused (403) even for the
//       owner — only the ACL API changes ACLs.
//   C4: after a permitted move, the ACL lookup at the new path reports the same owner and type,
//       the owner still reads the moved file, and can re-ACL the moved folder.
//
// Exit non-zero on the first broken clause, with the reason on stderr.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

const base = (process.env.ACCEPTANCE_BASE_URL || '').replace(/\/+$/, '');
const user = process.env.ACCEPTANCE_USER;
const password = process.env.ACCEPTANCE_PASSWORD;

function fail(message) {
  console.error(`#2525: ${message}`);
  process.exit(1);
}

function read(rel) {
  try {
    return readFileSync(join(root, rel), 'utf8');
  } catch {
    fail(`cannot read ${rel}`);
  }
}

// ---- S1: the manager reserves the sidecar ------------------------------------------------------------
const managerSrc = read('saiku-core/saiku-service/src/main/java/org/saiku/repository/FilesystemRepositoryManager.java');

if (!/static final String ACL_SIDECAR_FILENAME = "acl\.json"/.test(managerSrc)) {
  fail('S1: FilesystemRepositoryManager has no reserved ACL_SIDECAR_FILENAME ("acl.json")');
}
if (!/public static boolean isAclSidecarPath\(/.test(managerSrc)) {
  fail('S1: FilesystemRepositoryManager.isAclSidecarPath is missing (the resource reuses it for the 403)');
}
const guardCalls = managerSrc.match(/refuseAclSidecarPath\(/g) || [];
if (guardCalls.length < 10) {
  fail(`S1: refuseAclSidecarPath is called only ${guardCalls.length} time(s); every content door (save, internal saves, remove, internal remove, read, internal reads, ACL API, folder delete, move ends) must be guarded`);
}
if (!/refuseAclSidecarPath\(source\)/.test(managerSrc) || !/refuseAclSidecarPath\(target\)/.test(managerSrc)) {
  fail('S1: moveFile must refuse the sidecar as BOTH the source and the target');
}
if (!/carryAclStateAfterMove\(/.test(managerSrc) || !/rekeyAclSidecarsUnder\(/.test(managerSrc)) {
  fail('S1: a permitted move must re-key the entries beneath the moved node (carryAclStateAfterMove / rekeyAclSidecarsUnder)');
}
if (!/subtreeHasAclState\(/.test(managerSrc) || !/canGrant\(\s*src/.test(managerSrc)) {
  fail('S1: moveFile must require GRANT on the source when it carries ACL state (subtreeHasAclState + canGrant)');
}
if (!/!isAclSidecarFile\(file\)/.test(managerSrc)) {
  fail('S1: the repository listing must skip the sidecar (getRepoObjects: !isAclSidecarFile(file))');
}

// ---- S2: the REST repository surface answers 403 -------------------------------------------------------
const resourceSrc = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/BasicRepositoryResource2.java');
if (!resourceSrc.includes('FilesystemRepositoryManager.isAclSidecarPath')) {
  fail('S2: BasicRepositoryResource2 does not reuse the manager\'s isAclSidecarPath guard');
}
const refused = resourceSrc.match(/refuseAclSidecar\(/g) || [];
if (refused.length < 5) {
  fail(`S2: refuseAclSidecar guards ${refused.length} call site(s); the resource must 403 sidecar reads, saves, deletes and both ends of a move`);
}

// ---- S3: the manager-level regression tests pin the fix -------------------------------------------------
const testSrc = read('saiku-core/saiku-service/src/test/java/org/saiku/repository/FilesystemRepositoryManagerAclSidecarTest.java');
for (const pin of [
  'sidecar_save_is_refused_even_for_the_owner',
  'sidecar_read_is_refused_even_for_the_owner',
  'sidecar_remove_is_refused_even_for_the_owner',
  'sidecar_cannot_be_move_source_or_target',
  'listing_never_contains_the_sidecar',
  'write_only_user_cannot_move_protected_folder',
  'owner_move_keeps_protection_and_rekeys_entries',
  'moved_file_keeps_its_per_file_entry',
  'delete_removes_the_stale_entry',
  'owner_can_still_read_and_reacl_own_folder'
]) {
  if (!testSrc.includes(pin)) fail(`S3: FilesystemRepositoryManagerAclSidecarTest does not pin ${pin}`);
}

if (!base) {
  console.log('#2525: static checks pass (S1-S3); ACCEPTANCE_BASE_URL is unset, so the live C1/C2/C4 checks did not run');
  process.exit(0);
}
if (!user || !password) fail('ACCEPTANCE_USER / ACCEPTANCE_PASSWORD are not set');

const form = (pairs) => new URLSearchParams(pairs).toString();
const repoBase = '/rest/saiku/api';

// ---- the admin session ----------------------------------------------------------------------------------
const cookies = new Map();
let xsrf = '';

async function send(path, init = {}) {
  const headers = new Headers(init.headers || {});
  if (cookies.size) headers.set('Cookie', [...cookies].map(([k, v]) => `${k}=${v}`).join('; '));
  if (xsrf && init.method && init.method.toUpperCase() !== 'GET' && init.method.toUpperCase() !== 'HEAD') {
    headers.set('X-XSRF-TOKEN', xsrf);
  }
  return fetch(base + path, { redirect: 'manual', ...init, headers });
}

function absorb(response) {
  const all = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [];
  for (const c of all) {
    const [pair, ...attrs] = c.split(';');
    const eq = pair.indexOf('=');
    const name = pair.slice(0, eq).trim();
    const value = pair.slice(eq + 1).trim();
    if (attrs.some((a) => /max-age\s*=\s*0/i.test(a))) {
      cookies.delete(name);
    } else {
      cookies.set(name, value);
      if (name === 'XSRF-TOKEN') xsrf = value;
    }
  }
}

const login = await send('/rest/saiku/session', {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: form({ username: user, password })
});
absorb(login);
await login.arrayBuffer();
if (login.status !== 200) fail(`login as ${user} answered ${login.status}, expected 200`);

const FOLDER = '/homes/admin/acl2525';
const DOC = `${FOLDER}/doc.txt`;
const SIDECAR = `${FOLDER}/acl.json`;
const MOVED = '/homes/admin/acl2525-moved';

async function saveResource(file, content) {
  return send(`${repoBase}/repository/resource`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: form({ file, content })
  });
}

// ---- setup: the folder, its sidecar, and a document ----------------------------------------------------
const seed = await saveResource(DOC, 'hello');
absorb(seed);
await seed.arrayBuffer();
if (seed.status !== 200) fail(`setup: saving ${DOC} answered ${seed.status}, expected 200`);
const seedAcl = await send(`${repoBase}/repository/resource/acl`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: form({ file: FOLDER, acl: JSON.stringify({ owner: 'admin', type: 'PRIVATE', roles: null, users: null }) })
});
absorb(seedAcl);
await seedAcl.arrayBuffer();
if (seedAcl.status !== 200) fail(`setup: setting the ACL on ${FOLDER} answered ${seedAcl.status}, expected 200`);

// ---- C1: the sidecar is not content ---------------------------------------------------------------------
for (const spelling of [SIDECAR, `${FOLDER}/ACL.JSON`, `${FOLDER}/acl.json/`]) {
  const get = await send(`${repoBase}/repository/resource?file=${encodeURIComponent(spelling)}`);
  await get.arrayBuffer();
  if (get.status !== 403) {
    fail(`C1: GET the sidecar (${spelling}) answered ${get.status}, expected the contracted 403`);
  }
}
const viaDashboards = await send(`${repoBase}/dashboards${SIDECAR}`);
await viaDashboards.arrayBuffer();
if (viaDashboards.status === 200) {
  fail(`C1: the dashboards door serves the sidecar: GET /dashboards${SIDECAR} answered 200`);
}

// ---- C5: listings do not disclose it --------------------------------------------------------------------
const tree = await send(`${repoBase}/repository`);
const treeBody = await tree.arrayBuffer().then((b) => new TextDecoder().decode(b));
if (tree.status !== 200) fail(`C5: GET /repository answered ${tree.status}, expected 200`);
if (treeBody.includes('acl.json')) {
  fail('C5: the repository tree lists acl.json — whose content is a map of the server\'s absolute paths');
}

// ---- C2: the sidecar cannot be written, deleted or moved as content --------------------------------------
const sidecarWrite = await saveResource(SIDECAR, '{"stolen":true}');
absorb(sidecarWrite);
await sidecarWrite.arrayBuffer();
if (sidecarWrite.status !== 403) {
  fail(`C2: POST /repository/resource with file=${SIDECAR} answered ${sidecarWrite.status}, expected the contracted 403`);
}
const sidecarDelete = await send(`${repoBase}/repository/resource?file=${encodeURIComponent(SIDECAR)}`, { method: 'DELETE' });
absorb(sidecarDelete);
await sidecarDelete.arrayBuffer();
if (sidecarDelete.status !== 403) {
  fail(`C2: DELETE /repository/resource?file=${SIDECAR} answered ${sidecarDelete.status}, expected the contracted 403`);
}
const dashboardsWrite = await send(`${repoBase}/dashboards${SIDECAR}`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: '{"stolen":true}'
});
absorb(dashboardsWrite);
await dashboardsWrite.arrayBuffer();
if (dashboardsWrite.status === 200) {
  fail(`C2: the dashboards door accepts a sidecar write: POST /dashboards${SIDECAR} answered 200`);
}
const moveSource = await send(`${repoBase}/repository/resource/move`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: form({ source: SIDECAR, target: '/homes/admin/acl2525-sidecar-taken' })
});
absorb(moveSource);
await moveSource.arrayBuffer();
if (moveSource.status !== 403) {
  fail(`C2: moving the sidecar as the source answered ${moveSource.status}, expected the contracted 403`);
}
const moveTarget = await saveResource('/homes/admin/other.txt', 'x').then((r) => {
  absorb(r);
  return r.arrayBuffer().then(() => r.status);
});
if (moveTarget !== 200) fail(`setup: saving /homes/admin/other.txt answered ${moveTarget}, expected 200`);
const moveTargetResp = await send(`${repoBase}/repository/resource/move`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: form({ source: '/homes/admin/other.txt', target: SIDECAR })
});
absorb(moveTargetResp);
await moveTargetResp.arrayBuffer();
if (moveTargetResp.status !== 403) {
  fail(`C2: using the sidecar as a move target answered ${moveTargetResp.status}, expected the contracted 403`);
}

// ---- C4: a permitted move keeps the ACL entries in force -------------------------------------------------
const moved = await send(`${repoBase}/repository/resource/move`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: form({ source: FOLDER, target: MOVED })
});
absorb(moved);
await moved.arrayBuffer();
if (moved.status !== 200) fail(`C4: the owner's move of ${FOLDER} answered ${moved.status}, expected 200`);
const aclAtNewPath = await send(`${repoBase}/repository/resource/acl?file=${encodeURIComponent(MOVED)}`);
const aclBody = await aclAtNewPath.arrayBuffer().then((b) => new TextDecoder().decode(b));
if (aclAtNewPath.status !== 200) fail(`C4: GET the ACL of ${MOVED} answered ${aclAtNewPath.status}, expected 200`);
let aclJson;
try {
  aclJson = JSON.parse(aclBody);
} catch {
  fail(`C4: the ACL of the moved folder is not JSON: ${aclBody.slice(0, 200)}`);
}
if (aclJson.owner !== 'admin' || String(aclJson.type).toUpperCase() !== 'PRIVATE') {
  fail(`C4: after the move the entry was dropped, not re-keyed: owner=${JSON.stringify(aclJson.owner)} type=${JSON.stringify(aclJson.type)}`);
}
const movedDoc = await send(`${repoBase}/repository/resource?file=${encodeURIComponent(`${MOVED}/doc.txt`)}`);
const docBody = await movedDoc.arrayBuffer().then((b) => new TextDecoder().decode(b));
if (movedDoc.status !== 200 || docBody !== 'hello') {
  fail(`C4: the owner cannot read the moved file (${movedDoc.status}: ${docBody.slice(0, 80)})`);
}
const reAcl = await send(`${repoBase}/repository/resource/acl`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: form({ file: MOVED, acl: JSON.stringify({ owner: 'admin', type: 'PRIVATE', roles: null, users: null }) })
});
absorb(reAcl);
await reAcl.arrayBuffer();
if (reAcl.status !== 200) fail(`C4: re-ACLing the moved folder answered ${reAcl.status}, expected 200`);

console.log('#2525: the sidecar is not content, listings hide it, and a permitted move keeps the entries in force');
