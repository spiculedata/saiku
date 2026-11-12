// The acceptance spec for spiculedata/saiku#2324, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2324`.
//
// #2324: GET /ai/ossie/values/search and the per-field sampleValues in the AI schema
// ran an unscoped `SELECT DISTINCT` against the warehouse, bypassing the saiku.roles
// field allow/deny and dataset row_predicates that OssieRoleContext enforces for the
// query path. This spec fails on the base commit (none of the structures below exist)
// and passes on the fix; behaviour is covered by the unit tests it also checks for.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (path) => {
  try {
    return readFileSync(join(root, path), 'utf8');
  } catch {
    return '';
  }
};

const roleContext = read('saiku-core/saiku-service/src/main/java/org/saiku/service/ossie/OssieRoleContext.java');
const projector = read('saiku-core/saiku-service/src/main/java/org/saiku/service/ossie/ai/OssieAiSchemaProjector.java');
const resource = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/AiOssieResource.java');
const projectorTest = read(
  'saiku-core/saiku-service/src/test/java/org/saiku/service/ossie/ai/OssieAiSchemaProjectorRowScopeTest.java',
);
const roleTest = read('saiku-core/saiku-service/src/test/java/org/saiku/service/ossie/OssieRoleContextTest.java');
const fuzzTest = read('saiku-core/saiku-web/src/test/java/org/saiku/web/rest/resources/SearchValuesSqlFuzzTest.java');

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};
const between = (src, from, to) => {
  const start = src.indexOf(from);
  if (start < 0) return '';
  const end = src.indexOf(to, start + from.length);
  return src.slice(start, end < 0 ? undefined : end);
};

const searchValues = between(resource, 'public Response searchValues', 'private List<String> runValuesSearch');
const askMethod = between(resource, 'OssieModelDto semantic = ossieDiscoverService.getModel(connection);', 'askService.ask(');

// ---- C1 ------------------------------------------------------------------
claim('C1', 'searchValues filters the model by the caller roles', /OssieRoleContext\.filterHidden\(/.test(searchValues));

// ---- C2 ------------------------------------------------------------------
claim(
  'C2',
  'OssieRoleContext exposes combinedRowPredicateFor',
  /public String combinedRowPredicateFor\(String datasetName\)/.test(roleContext),
);
claim('C2', 'searchValues resolves the row predicate for the dataset', /combinedRowPredicateFor\(/.test(searchValues));
claim(
  'C2',
  'buildValuesSearchSql takes a row predicate',
  /static String buildValuesSearchSql\(String col, String src, String q, int cap, String rowPredicate\)/.test(resource),
);
claim('C2', 'the projector sample query is scoped by the row predicate', /combinedRowPredicateFor\(/.test(projector));

// ---- C3 ------------------------------------------------------------------
claim(
  'C3',
  'the projector accepts caller roles',
  /project\(\s*String connectionName,\s*OssieModelDto semantic,\s*Connection jdbc,\s*Set<String> callerRoles\)/.test(projector),
);
claim('C3', 'the sample cache key carries the row predicate', /cacheKey[^;]*rowPredicate|rowPredicate[^;]*cacheKey/.test(projector));

// ---- C4 ------------------------------------------------------------------
claim('C4', 'ask filters the semantic model by caller roles', /OssieRoleContext\.filterHidden\(/.test(askMethod));
claim('C4', 'ask projects with the caller roles', /SessionRoles\.currentRoles\(\)/.test(askMethod) && /projector\.project\([^;]*callerRoles/.test(askMethod));

// ---- C5 ------------------------------------------------------------------
claim('C5', 'row-scoped sample + cache isolation tests exist', /sampleCache|cacheIsolation|differentRowScope/i.test(projectorTest));
claim('C5', 'combined predicate tests exist', /combinedRowPredicate/.test(roleTest));
claim('C5', 'scoped values-search SQL test exists', /rowPredicate|RowPredicate/.test(fuzzTest));

if (failures.length) {
  failures.forEach((failure) => process.stderr.write(`${failure}\n`));
  process.exit(1);
}
process.stdout.write('Ossie values-search and schema samples honour saiku.roles allow/deny and row predicates\n');
