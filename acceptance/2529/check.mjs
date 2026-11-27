import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => {
  try {
    return readFileSync(join(root, p), 'utf8');
  } catch {
    return ''; // missing file: every assertion on it fails with its own reason below
  }
};
const web = 'saiku-core/saiku-web/src';
const failures = [];

// saiku#2529: the daily cost budget (#1918) was a `new AiCostBudget()` field of the
// request-scoped aiQueryResource — its counters were rebuilt empty on every request, so no
// ceiling could ever trip, and four provider-reaching endpoints carried no budget at all.

const beans = read('saiku-webapp/src/main/webapp/WEB-INF/saiku-beans.xml');
if (!beans) failures.push('C1: saiku-beans.xml not found at the repository root');

// C1 — one singleton budget bean, injected into aiQueryResource (as the limiter is).
if (!/<bean id="aiCostBudget" class="org\.saiku\.web\.security\.ratelimit\.AiCostBudget"\/>/.test(beans))
  failures.push('C1: saiku-beans.xml must declare a singleton aiCostBudget bean (no scope attribute)');
if (!/id="aiQueryResource"[\s\S]*?<property name="aiCostBudget" ref="aiCostBudget"\/>/.test(beans))
  failures.push('C1: aiQueryResource must be injected with the shared aiCostBudget singleton');

// C1 + C2 — wired-context proof: third ask 429s; the singleton is shared across instances.
const wiring = read(`${web}/test/java/org/saiku/web/security/ratelimit/AiCostBudgetWiringTest.java`);
if (!/dailyCallCeilingTripsAcrossRequestBoundaries/.test(wiring))
  failures.push('C1: no wired-context test that the third ask from one user hits the budget');
if (!/Daily AI ask request budget exhausted/.test(wiring))
  failures.push('C1: the wired-context test must assert the budget reason on the 429');
if (!/assertSame\([\s\S]*?aiCostBudget/.test(wiring))
  failures.push('C2: the wired-context test must prove the SAME singleton reaches every resource');
if (!/tokenCeilingIsSharedAcrossRequestBoundaries/.test(wiring))
  failures.push('C2: no wired-context test that the token ceiling binds across request boundaries');
if (!/chainConcurrencyCapIsSharedAcrossRequestBoundaries/.test(wiring))
  failures.push('C2: no wired-context test that the chain-concurrency cap is shared');

// C3 — the four previously unbudgeted endpoints gate on the shared budget.
const aiQuery = read(`${web}/main/java/org/saiku/web/rest/resources/AiQueryResource.java`);
const aiOssie = read(`${web}/main/java/org/saiku/web/rest/resources/AiOssieResource.java`);
const aiExplain = read(`${web}/main/java/org/saiku/web/rest/resources/AiExplainResource.java`);
const gate = /tryStartCall\(/;
const describeBody = aiQuery.match(/public Response describeQuery\([\s\S]*?\n    \}\n/)?.[0] ?? '';
const narrateBody = aiQuery.match(/public Response narrateDashboard\([\s\S]*?\n    \}\n/)?.[0] ?? '';
const ossieBody = aiOssie.match(/public Response ask\([\s\S]*?\n    \}\n/)?.[0] ?? '';
const explainBody = aiExplain.match(/public Response explain\([\s\S]*?\n    \}\n/)?.[0] ?? '';
if (!gate.test(describeBody)) failures.push('C3: /ai/describe-query does not gate on the budget');
if (!gate.test(narrateBody)) failures.push('C3: /ai/narrate-dashboard does not gate on the budget');
if (!gate.test(ossieBody)) failures.push('C3: /ai/ossie/ask does not gate on the budget');
if (!gate.test(explainBody)) failures.push('C3: /ai/explain does not gate on the budget');
if (!/id="aiOssieResource"[\s\S]*?<property name="aiCostBudget" ref="aiCostBudget"\/>/.test(beans))
  failures.push('C3: aiOssieResource must be injected with the shared aiCostBudget singleton');
if (!/id="aiExplainResource"[\s\S]*?<property name="aiCostBudget" ref="aiCostBudget"\/>/.test(beans))
  failures.push('C3: aiExplainResource must be injected with the shared aiCostBudget singleton');

// C4 — identity comes from the Spring Security context FIRST, so two users behind one
// address get separate buckets instead of sharing an anonymous/IP bucket.
const principal = read(`${web}/main/java/org/saiku/web/security/ratelimit/AiRequestPrincipal.java`);
if (!/SecurityContextHolder\.getContext\(\)\s*\n?\s*\.getAuthentication\(\)/.test(principal))
  failures.push('C4: AiRequestPrincipal must read the Spring Security context first');
if (!/twoUsersBehindOneAddressGetSeparateBuckets/.test(wiring))
  failures.push('C4: no wired-context test that two users behind one address get separate buckets');
if (!/aiQuery\.includes('askRateKey')|private String askRateKey\(\)[\s\S]*?AiRequestPrincipal\.currentName\(\)/.test(aiQuery))
  failures.push('C4: the per-minute limiter key must come from the Security-context principal first');
if (!/private String askBudgetPrincipal\(\)[\s\S]*?AiRequestPrincipal\.currentName\(\)/.test(aiQuery))
  failures.push('C4: the budget principal must come from the Security-context principal first');
if (!/private String askRateKey\(\)[\s\S]*?AiRequestPrincipal\.currentName\(\)/.test(aiOssie))
  failures.push('C4: the Ossie limiter key must come from the Security-context principal first');

// C5 — the operator docs describe what is enforced.
const docs = read('docs/operator-hardening.md');
if (!/aiCostBudget/.test(docs)) failures.push('C5: docs must name the singleton aiCostBudget bean');
if (!/describe-query[\s\S]{0,400}narrate-dashboard[\s\S]{0,400}ossie\/ask[\s\S]{0,400}explain|\/ai\/ossie\/ask[\s\S]{0,300}\/ai\/narrate-dashboard[\s\S]{0,300}\/ai\/describe-query[\s\S]{0,300}\/ai\/explain/s.test(docs))
  failures.push('C5: docs must list the four endpoints the budget now covers');
if (!/unpriced/.test(docs)) failures.push('C5: docs must state the unpriced-charge gaps on ossie/ask and explain');
if (!/Spring Security|Security context/.test(docs))
  failures.push('C5: docs must say the principal comes from the Spring Security context first');

if (failures.length) {
  failures.forEach((f) => process.stderr.write(f + '\n'));
  process.exit(1);
}
process.stdout.write('ai cost budget is a shared singleton on every provider-reaching endpoint\n');
