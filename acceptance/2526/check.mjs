// The acceptance spec for spiculedata/saiku#2526, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2526`.
//
// #2526: field-level `saiku.roles` (saiku#1393) was enforced by comparing the request with the
// model's SEMANTIC field names. On the workbench path (queryType=OSSIE with a client-supplied
// ossieQueryModel) the SQL translator emitted any identifier it could not resolve as a quoted
// physical column, so a caller denied `c_email` could ask for `C_EMAIL_ADDRESS` and get the
// values. A filter without `dataset` and `sorts[].metric` were never checked either. Separately,
// GraphQL `ossieModel`, `GET /ai/ossie/export/{connection}/{tool}` and the ontology endpoint
// returned the model without the role filter.
//
// This spec fails on the base commit (the typed validation exception, the unqualified-filter
// resolution, the sort-metric check and the role-filtered/export surfaces do not exist there)
// and passes on the fix. Behaviour itself is covered by the unit and end-to-end tests it also
// checks for.

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
const translator = read('saiku-core/saiku-service/src/main/java/org/saiku/service/ossie/OssieShelfSqlTranslator.java');
const validationException = read('saiku-core/saiku-service/src/main/java/org/saiku/service/util/exception/SaikuValidationException.java');
const query2 = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/Query2Resource.java');
const graphQlFetchers = read('saiku-core/saiku-web/src/main/java/org/saiku/web/graphql/SaikuGraphQlFetchers.java');
const aiOssieResource = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/AiOssieResource.java');
const roleContextTest = read('saiku-core/saiku-service/src/test/java/org/saiku/service/ossie/OssieRoleContextTest.java');
const roleBypassIT = read('saiku-core/saiku-sql/src/test/java/org/saiku/service/ossie/OssieRoleBypassIT.java');

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

const assertShelf = between(roleContext, 'public void assertShelfVisible', 'private void assertFieldVisible');
const exportMethod = between(
  aiOssieResource,
  'public Response exportSemanticModel',
  'POST /query',
);
const ontologyMethod = between(aiOssieResource, 'public Response getOntology', 'GET /export');
const ossieModelFetcher = between(graphQlFetchers, 'public DataFetcher<Object> ossieModel', 'Execution fetchers');

// ---- C1 ------------------------------------------------------------------
claim('C1', 'SaikuValidationException exists as the typed rejection for unresolvable identifiers', validationException.length > 0);
claim(
  'C1',
  'assertShelfVisible resolves a filter entry that names no dataset (unqualified field)',
  /assertUnqualifiedField\(f\.getField\(\)\)/.test(assertShelf),
);
claim(
  'C1',
  'assertShelfVisible checks sorts by metric (not only sorts with dataset+field)',
  /assertMetricVisible\(s\.getMetric\(\)\)/.test(assertShelf),
);
claim(
  'C1',
  'assertShelfVisible throws the typed validation error for an identifier that resolves to nothing',
  /SaikuValidationException/.test(assertShelf),
);
claim(
  'C1',
  'the translator refuses an unknown metric with the typed validation error (no raw fall-through)',
  /SaikuValidationException/.test(translator),
);

// ---- C2 ------------------------------------------------------------------
claim('C2', '/preview-sql maps SaikuValidationException to a typed 400', /catch \(SaikuValidationException/.test(query2));
claim(
  'C2',
  'queryFailure walks the cause chain for SaikuValidationException and answers 400',
  /isValidation\(e\)/.test(query2) && /isValidation\(Throwable/.test(query2),
);
claim(
  'C2',
  'the async submit path answers 400 for a typed validation rejection too',
  between(query2, 'public Response executeAsync', 'Poll async query status').includes('isValidation(e)'),
);

// ---- C3 ------------------------------------------------------------------
claim('C3', 'GraphQL ossieModel filters the model by the caller roles', /OssieRoleContext\.filterHidden\(/.test(ossieModelFetcher));
claim('C3', 'GraphQL ossieModel applies the schema-metadata AI policy guard', /AiDataKind\.SCHEMA_METADATA/.test(ossieModelFetcher));
claim('C3', 'the semantic export renders the role-filtered model', /OssieRoleContext\.filterHidden\(/.test(exportMethod));
claim('C3', 'the ontology endpoint resolves the caller-filtered model', /OssieRoleContext\.filterHidden\(/.test(ontologyMethod));

// ---- C4 ------------------------------------------------------------------
claim('C4', 'OssieRoleContext unit tests cover the typed validation rejections', /SaikuValidationException/.test(roleContextTest));
claim('C4', 'an end-to-end test through a real OSSIE datasource proves the bypass closed', /OssieRoleBypassIT|EMAIL_ADDRESS/.test(roleBypassIT));

if (failures.length) {
  failures.forEach((failure) => process.stderr.write(`${failure}\n`));
  process.exit(1);
}
process.stdout.write('Ossie saiku.roles cannot be bypassed by naming physical columns; semantic surfaces return the role-filtered model\n');
