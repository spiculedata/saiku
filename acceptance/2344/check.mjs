// Acceptance spec for spiculedata/saiku#2344, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2344`.
//
// The behavioural half is ScopedRepoTest and SessionServiceSessionPersistenceTest (JUnit, run
// by `mvn verify`). This file guards the structural invariant: there is no process-wide
// "last session" for a request without a session of its own to fall back to.

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => (existsSync(join(root, p)) ? readFileSync(join(root, p), 'utf8') : null);
const failures = [];

const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');

const scoped = read('saiku-core/saiku-service/src/main/java/org/saiku/repository/ScopedRepo.java');
if (scoped === null) {
  failures.push('C1: ScopedRepo.java is missing');
} else {
  const code = strip(scoped);
  if (/fallbackSession/.test(code)) failures.push('C1: ScopedRepo still caches a fallbackSession');
  if (/void\s+setSession\s*\(/.test(code)) failures.push('C1: ScopedRepo still has setSession()');
  if (/HttpSessionCreatedEvent/.test(code)) failures.push('C1: ScopedRepo still listens for HttpSessionCreatedEvent');
}

const service = read('saiku-core/saiku-web/src/main/java/org/saiku/web/service/SessionService.java');
if (service === null) failures.push('C2: SessionService.java is missing');
else if (/sessionRepo\.setSession\s*\(/.test(strip(service))) failures.push('C2: SessionService.login still calls sessionRepo.setSession');

const t1 = read('saiku-core/saiku-service/src/test/java/org/saiku/repository/ScopedRepoTest.java');
if (t1 === null || !t1.includes('requestWithoutSession_returnsNull')) failures.push('C3: ScopedRepoTest is missing');
const t2 = read('saiku-core/saiku-web/src/test/java/org/saiku/web/service/SessionServiceSessionPersistenceTest.java');
if (t2 === null || !t2.includes('clearSessions_requestWithoutSession_doesNotTouchOtherSessions')) {
  failures.push('C3: the clearSessions cross-session test is missing');
}

if (failures.length > 0) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log('acceptance/2344: ok');
