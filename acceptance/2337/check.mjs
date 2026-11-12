import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => readFileSync(join(root, p), 'utf8');
const failures = [];

// C1
const lock = JSON.parse(read('saiku-ui/package-lock.json'));
const v = lock.packages['node_modules/dompurify']?.version ?? '0.0.0';
const [a, b, c] = v.split('.').map(Number);
if (!(a > 3 || (a === 3 && (b > 4 || (b === 4 && c >= 17))))) failures.push(`C1: dompurify is ${v}, need >= 3.4.17`);
const copies = Object.keys(lock.packages).filter((k) => k.endsWith('/dompurify'));
if (copies.length !== 1) failures.push(`C1: expected one dompurify copy, found ${copies.join(', ')}`);

// Split the workflow into top-level jobs.
const wf = read('.github/workflows/security-scan.yml');
const jobsText = wf.split(/^jobs:\s*$/m)[1] ?? '';
const jobs = {};
let name = null;
for (const line of jobsText.split('\n')) {
  const m = line.match(/^  ([A-Za-z0-9_-]+):\s*$/);
  if (m) { name = m[1]; jobs[name] = ''; } else if (name) jobs[name] += line + '\n';
}
const readOnly = (j) => /permissions:\s*\n\s+contents:\s*read\s*\n(?!\s+[a-z-]+:\s*write)/.test(j) && !/:\s*write/.test(j);

// C2
const audit = Object.values(jobs).find((j) => j.includes('npm audit --omit=dev --audit-level=high'));
if (!audit) failures.push('C2: no job runs npm audit --omit=dev --audit-level=high');
else {
  if (!readOnly(audit)) failures.push('C2: npm audit job must have read-only permissions');
  if (/github\.event_name == 'pull_request'\s*$/m.test(audit)) failures.push('C2: npm audit job must not be PR-only');
  if (!/^\s+working-directory: saiku-ui|cd saiku-ui/m.test(audit)) failures.push('C2: npm audit must run in saiku-ui');
}
if (!/^\s+schedule:/m.test(wf)) failures.push('C2: workflow has no schedule trigger');

// C3
const osv = Object.values(jobs).find((j) => /osv-scanner/.test(j));
if (!osv) failures.push('C3: no osv-scanner job');
else {
  if (!/OSV_SCANNER_VERSION:\s*"?\d+\.\d+\.\d+"?/.test(osv)) failures.push('C3: osv-scanner version must be pinned');
  if (!/OSV_SCANNER_SHA256:\s*"[0-9a-f]{64}"/.test(osv) || !/sha256sum -c/.test(osv)) failures.push('C3: osv-scanner must be sha256 verified');
  if (!readOnly(osv)) failures.push('C3: osv-scanner job must have read-only permissions');
  if (/github\.event_name == 'pull_request'\s*$/m.test(osv)) failures.push('C3: osv-scanner job must not be PR-only');
}

// C4
if (!/if: github\.event_name == 'pull_request'/.test(jobs['dependency-review'] ?? '') || !/fail-on-severity: high/.test(jobs['dependency-review'] ?? ''))
  failures.push('C4: dependency-review PR gate changed');

if (failures.length) { failures.forEach((f) => process.stderr.write(f + '\n')); process.exit(1); }
process.stdout.write('dompurify patched; scheduled npm audit and osv-scanner jobs present\n');
