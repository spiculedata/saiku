import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => readFileSync(join(root, p), 'utf8');
const web = 'saiku-core/saiku-web/src';
const failures = [];

const filter = read(`${web}/main/java/org/saiku/web/security/ratelimit/LoginRateLimitFilter.java`);
if (/"Basic "/.test(filter)) failures.push('C1: filter still requires the literal prefix "Basic " (with a space)');
if (!/\.trim\(\)/.test(filter) || !/substring\((6|BASIC_CREDENTIALS_OFFSET)\)/.test(filter))
  failures.push('C1: filter must trim and skip six characters like BasicAuthenticationConverter');
if (!/isBlockedUnattributed|Unattributed/.test(filter)) failures.push('C1: no strict rule for unattributable Basic credentials');

const limiter = read(`${web}/main/java/org/saiku/web/security/ratelimit/LoginRateLimiter.java`);
const resolverPath = `${web}/main/java/org/saiku/web/security/ratelimit/ClientIpResolver.java`;
let resolver = '';
try { resolver = read(resolverPath); } catch { failures.push('C3: no shared ClientIpResolver'); }
if (resolver && !/getHeaders\(/.test(resolver)) failures.push('C2: resolver must read X-Forwarded-For with getHeaders');
if (/getHeader\("X-Forwarded-For"\)/.test(limiter + resolver)) failures.push('C2: first-line-only getHeader("X-Forwarded-For") still used');
if (!/ClientIpResolver/.test(limiter)) failures.push('C3: limiter does not use ClientIpResolver');

const audit = read(`${web}/main/java/org/saiku/web/security/audit/AuditLogger.java`);
if (!/ClientIpResolver/.test(audit)) failures.push('C3: AuditLogger does not use ClientIpResolver');
if (/trustForwardedFor/.test(audit)) failures.push('C3: AuditLogger still reads the legacy flag itself');

const success = limiter.match(/void recordSuccess[\s\S]*?\n    }/)?.[0] ?? '';
if (/userBuckets\.invalidate/.test(success)) failures.push('C4: success still clears the per-username bucket');

const test = read(`${web}/test/java/org/saiku/web/security/ratelimit/LoginRateLimiterTest.java`);
if (/Thread\.sleep/.test(test)) failures.push('C5: LoginRateLimiterTest still sleeps');
if (!/Ticker/.test(limiter)) failures.push('C5: limiter takes no injectable Ticker');

const docs = read('docs/operator-hardening.md');
if (!/immediate (proxy|peer)/.test(docs) || !/(hands|handing|gives) .*(caller|client)/s.test(docs.split('## Login lockout')[1] ?? ''))
  failures.push('C6: docs must say n counts the immediate proxy and that too high a value hands the key to the caller');
if (/clears the \(IP, username\) and username counters/.test(docs)) failures.push('C6: docs still say success clears the username counter');

if (failures.length) { failures.forEach((f) => process.stderr.write(f + '\n')); process.exit(1); }
process.stdout.write('rate limiter follow-ups hold\n');
