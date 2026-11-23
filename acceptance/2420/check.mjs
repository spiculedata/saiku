// The acceptance spec for spiculedata/saiku#2420, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2420`.
//
// The raw-drillthrough cap's fail-closed contract is asserted in the source (the seam and its
// two call sites); the behavioural half is DrillthroughRowCapIT (JUnit, run by the launcher's
// `-DskipITs=false` gate), which this spec requires to ship.

import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => readFileSync(join(root, p), 'utf8');
const has = (p) => existsSync(join(root, p));

const builder = read(
  'saiku-core/saiku-service/src/main/java/org/saiku/service/olap/DrillthroughMdxBuilder.java',
);
const thin = read('saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ThinQueryService.java');
const olap = read('saiku-core/saiku-service/src/main/java/org/saiku/service/olap/OlapQueryService.java');
const helper = read('saiku-core/saiku-olap-util/src/main/java/mondrian/olap4j/SaikuMondrianHelper.java');
const mapper = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/util/GenericExceptionMapper.java');
const capTest = read('saiku-core/saiku-service/src/test/java/org/saiku/service/olap/DrillthroughMdxBuilderCapRawTest.java');
const itPath = 'saiku-launcher/src/test/java/org/saiku/launcher/it/DrillthroughRowCapIT.java';
const rowCapIt = has(itPath) ? read(itPath) : '';

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};

// C1 — fail closed at the seam. The clamp must refuse, never pass through unchanged or
// prepend a second MAXROWS (the old double-MAXROWS parse error).
const capRaw = builder.slice(builder.indexOf('public static String capRawDrillthrough(String mdx'));
claim('C1', 'capRawDrillthrough never raises SaikuQueryRejectedException', builder.includes('SaikuQueryRejectedException'));
claim('C1', 'an unterminated block comment is not reported by the trivia scan', /unterminated/.test(capRaw));
claim('C1', 'a commented statement with no locatable keyword is not refused', capRaw.includes('start.sawComment'));
claim('C1', 'a non-positive cap bound is not refused (MAXROWS 0 is UNLIMITED to Mondrian)', capRaw.includes('non-positive cap clause bound means UNLIMITED'));
claim('C1', 'the double-MAXROWS prepend fallback is still the failure mode', !/return "DRILLTHROUGH MAXROWS " \+ cap \+ " " \+ rest;/.test(capRaw));

// C2 — Mondrian's real parser gates execution of the capped statement.
claim('C2', 'SaikuMondrianHelper does not expose the parsed drillthrough bound', helper.includes('drillThroughMaxRowCount'));
claim('C2', 'the builder does not re-parse the capped statement with Mondrian', builder.includes('verifyCapWithParser'));
claim('C2', 'ThinQueryService raw drillthrough does not pass the connection to the cap', /DrillthroughMdxBuilder\.capRawDrillthrough\(con, /.test(thin));
claim('C2', 'OlapQueryService raw drillthrough does not pass the connection to the cap', /DrillthroughMdxBuilder\.capRawDrillthrough\(con, /.test(olap));
claim('C2', 'the parser gate does not require the parsed bound inside [1, maxRows()]', /parsed < 1 \|\| parsed > ceiling/.test(builder));

// C3 — the behavioural IT ships and asserts the issue's contract.
claim('C3', 'DrillthroughRowCapIT does not ship', rowCapIt !== '');
claim('C3', 'the IT does not raise the ceiling to 5 (saiku.olap.max.rows=5)', rowCapIt.includes('"5"'));
claim('C3', 'the IT does not assert height <= 5', /height\(.*\) <= 5|<= 5/.test(rowCapIt));
claim('C3', 'the IT does not cover the four leading-comment forms', rowCapIt.includes('"/*a*/DRILLTHROUGH "') && rowCapIt.includes('"--x\\nDRILLTHROUGH "') && rowCapIt.includes('"//x\\nDRILLTHROUGH "'));
claim('C3', 'the IT does not cover POST /api/query/execute', rowCapIt.includes('/rest/saiku/api/query/execute'));
claim('C3', 'the IT does not cover POST /api/notebooks/run', rowCapIt.includes('/rest/saiku/api/notebooks/run'));

// C4 — the old fail-open assertion became a fail-closed one.
claim('C4', 'the unterminated-comment test still asserts the fail-open pass-through', capTest.includes('capRaw_unterminatedBlockComment_isFailClosed'));

// C5 — typed rejection reaches the wire as a 400 with a fixed message; a commented MAXROWS
// clause carrying a bound is capped instead of 500ing on a double-MAXROWS parse error.
claim('C5', 'GenericExceptionMapper does not map the rejection to a 400', /SaikuQueryRejectedException/.test(mapper) && /BAD_REQUEST/.test(mapper));
claim('C5', 'the IT does not assert the typed 400 for MAXROWS 0', rowCapIt.includes('zeroMaxrows_returnsTypedRejection_not500'));
claim('C5', 'the IT does not assert the capped (never-500) commented MAXROWS clause', rowCapIt.includes('commentedMaxrowsClauseWithBound_isCapped_not500'));

// C6 — the property/seed tests ship.
claim('C6', 'the capRaw property test does not ship', read('saiku-proptest/src/test/java/org/saiku/proptest/DrillthroughMdxBuilderPropertyTest.java').includes('capRawNeverEmitsAnUnboundedDrillthrough'));

if (failures.length > 0) {
  for (const failure of failures) process.stderr.write(`${failure}\n`);
  process.exit(1);
}

process.stdout.write(
  'raw DRILLTHROUGH cap fails closed: unprovable statements are typed-rejected, the emitted cap is verified by Mondrian\'s real parser, the behavioural row-cap IT ships\n',
);
