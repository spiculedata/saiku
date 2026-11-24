// Acceptance spec for spiculedata/saiku#2520, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2520`.
//
// The behavioural half is the session-backed-context tests added to
// ShareTokenAuthFilterTest, EmbedAuthFilterTest, OAuthBearerAuthenticationFilterTest,
// ScimAuthFilterTest and the SessionService runAs test (JUnit, run by `mvn verify`). This
// file guards the structural invariant: no guest/token identity swap may ever call
// `SecurityContextHolder.getContext().setAuthentication(...)` — with a logged-in session
// cookie that returns the object stored in the HttpSession, so the mutation rewrites the
// session's own identity (viewer acts as the link owner, and a revoked token keeps working
// on the cookie alone). The swap must build a fresh context and restore the previous
// context OBJECT afterwards.

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const web = join(root, 'saiku-core', 'saiku-web', 'src');
const main = join(web, 'main/java/org/saiku/web');
const failures = [];
const read = (p) => (existsSync(p) ? readFileSync(p, 'utf8') : null);
// Remove // and /* */ comments with a scanner, not two greedy regexes: a literal '/*' inside
// a line comment (e.g. EmbedAuthFilter's 'Tokens/* is') otherwise swallows real code, and a
// '//' inside a block comment or string literal would eat the closing '*/' or real code.
const strip = (src) => {
  let out = '';
  let block = false;
  let i = 0;
  while (i < src.length) {
    if (block) {
      if (src.startsWith('*/', i)) {
        block = false;
        i += 2;
        continue;
      }
      i++;
      continue;
    }
    const c = src[i];
    if (c === '"' || c === "'") {
      const quote = c;
      let j = i + 1;
      while (j < src.length) {
        if (src[j] === '\\') j += 2;
        else if (src[j] === quote) break;
        else j++;
      }
      out += src.slice(i, Math.min(j + 1, src.length));
      i = j + 1;
      continue;
    }
    if (src.startsWith('//', i)) {
      while (i < src.length && src[i] !== '\n') i++;
      continue;
    }
    if (src.startsWith('/*', i)) {
      block = true;
      i += 2;
      continue;
    }
    out += c;
    i++;
  }
  return out;
};

// C1: the share and embed filters must swap a fresh context, never mutate the loaded one.
// C2: the OAuth bearer filter is under the same rule (it runs on the same logged-in-cookie
// shape). ScimAuthFilter shares the identical pattern and is covered so the class of bug
// cannot quietly come back there either.
const swapFilters = [
  'security/share/ShareTokenAuthFilter.java',
  'security/embed/EmbedAuthFilter.java',
  'security/oauth/OAuthBearerAuthenticationFilter.java',
  'security/scim/ScimAuthFilter.java',
];
for (const f of swapFilters) {
  const src = read(join(main, f));
  if (src === null) {
    failures.push(`C1: ${f} is missing`);
    continue;
  }
  const code = strip(src);
  if (/SecurityContextHolder\s*\.\s*getContext\s*\(\s*\)\s*\.\s*setAuthentication\s*\(/.test(code)) {
    failures.push(`C1: ${f} still mutates the loaded (session-backed) context via getContext().setAuthentication`);
  }
  if (!/SecurityContextHolder\s*\.\s*createEmptyContext\s*\(/.test(code)) {
    failures.push(`C1: ${f} does not build a fresh context with createEmptyContext()`);
  }
  if (!/SecurityContextHolder\s*\.\s*setContext\s*\(/.test(code)) {
    failures.push(`C1: ${f} does not swap the context object with setContext(...)`);
  }
}

// C3: SessionService.runAs must swap a fresh context and restore the prior context OBJECT.
// Scoped to the runAs method — logout legitimately calls getContext().setAuthentication(null)
// on the caller's own context.
const service = read(join(main, 'service/SessionService.java'));
if (service === null) {
  failures.push('C3: SessionService.java is missing');
} else {
  const code = strip(service);
  const start = code.indexOf('T> T runAs(');
  const end = code.indexOf('public void clearSessions');
  if (start < 0 || end <= start) {
    failures.push('C3: SessionService.runAs not found');
  } else {
    const runAs = code.slice(start, end);
    if (/getContext\s*\(\s*\)\s*\.\s*setAuthentication\s*\(/.test(runAs)) {
      failures.push('C3: runAs still mutates the loaded (session-backed) context via getContext().setAuthentication');
    }
    if (!/SecurityContextHolder\s*\.\s*createEmptyContext\s*\(/.test(runAs)) {
      failures.push('C3: runAs does not build a fresh context with createEmptyContext()');
    }
    if (!/SecurityContextHolder\s*\.\s*setContext\s*\(\s*prior/.test(runAs)) {
      failures.push('C3: runAs does not restore the prior context OBJECT in finally');
    }
  }
}

// C4: revocation/expiry takes effect on the cookie alone — nothing guest-shaped persists.
// That is the same invariant as C1 (no rewritten session context), guarded by the filter
// swap checks above; here we also pin the test half.
const filterTests = [
  ['security/share/ShareTokenAuthFilterTest.java', 'sessionBacked'],
  ['security/embed/EmbedAuthFilterTest.java', 'sessionBacked'],
  ['security/oauth/OAuthBearerAuthenticationFilterTest.java', 'sessionBacked'],
  ['security/scim/ScimAuthFilterTest.java', 'sessionBacked'],
];
for (const [f, marker] of filterTests) {
  const src = read(join(web, 'test/java/org/saiku/web', f));
  if (src === null || !src.includes(marker)) {
    failures.push(`C4: ${f} has no session-backed-context test (expected a '${marker}' test)`);
  }
}
const runAsTest = read(join(web, 'test/java/org/saiku/web/service/SessionServiceRunAsTest.java'));
if (runAsTest === null || !runAsTest.includes('runAs_sessionBackedContext_isUntouched')) {
  failures.push('C4: SessionServiceRunAsTest with a session-backed context is missing');
}

if (failures.length > 0) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log('acceptance/2520: ok');
