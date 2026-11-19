// Acceptance spec for spiculedata/saiku#2443: "explain this number" closed the OLAP connection
// it borrowed from the connection manager's cache. Without per-user datasource security that
// cache entry is keyed by the datasource name, so every user's queries run on it, and the ones
// running at that moment failed with "Connection is closed".
//
// The live behaviour (concurrent explains beside ordinary queries) is a timing race, so it is
// proven by the launcher IT ExplainKeepsSharedConnectionIT and the unit tests in
// CellExplainServiceTest (run by `mvn verify`); this spec asserts the contract in the source and
// requires those tests to ship. It fails on base and passes on the fix.
//
// What C3 checks, exactly: in every production .java file under saiku-core, each method (any
// access modifier, package-private included) that binds a variable to the result of
// getNativeConnection(...) or getOlapConnection(...) must not close that variable (close(),
// closeQuietly(x), or try-with-resources). Every such call in a file must fall inside a method the
// check examined, or the check fails rather than skipping it. Its limits: a connection passed on
// without being bound to a variable (for example straight into a wrapper) is not followed, nor is
// a close made through another method the connection was handed to.

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => readFileSync(join(root, p), 'utf8');
const exists = (p) => {
  try {
    return statSync(join(root, p)).isFile();
  } catch {
    return false;
  }
};

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};

// Strip // and /* */ comments so prose about the old behaviour cannot satisfy or trip a claim.
const code = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

/** The text of the method that starts at `signature`, up to its matching closing brace. */
const methodBody = (src, signature) => {
  const start = src.indexOf(signature);
  if (start < 0) return null;
  const open = src.indexOf('{', start);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}' && --depth === 0) return src.slice(open, i + 1);
  }
  return null;
};

// The calls that hand out a cached connection: OlapDiscoverService/OlapMetaExplorer
// getNativeConnection and IConnectionManager (or olap4j Database) getOlapConnection.
const BORROW = '(?:[\\w()]+\\.)*(?:getNativeConnection|getOlapConnection)\\(';
// A call site, not a declaration: the name follows '.', '=', '(' or 'return'.
const BORROW_CALL = /(?:\.|=|\(|\breturn)\s*(?:getNativeConnection|getOlapConnection)\(/g;

/** Names bound to the result of a borrowing call inside `body`. */
const borrowedNames = (body) => [...body.matchAll(new RegExp(`(\\w+)\\s*=\\s*${BORROW}`, 'g'))].map((m) => m[1]);

/** Whether `body` closes any of `names` directly, quietly, or via try-with-resources. */
const closesAny = (body, names) =>
  names.some(
    (n) =>
      new RegExp(`\\b${n}\\s*\\.\\s*close\\s*\\(`).test(body) ||
      new RegExp(`closeQuietly\\s*\\(\\s*${n}\\s*\\)`).test(body) ||
      new RegExp(`try\\s*\\([^)]*\\b${n}\\s*=\\s*${BORROW}`).test(body),
  );

// C1: captureSql closes its statement and cellset, never the borrowed connection.
const explainPath = 'saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ai/explain/CellExplainService.java';
const explain = code(read(explainPath));
const capture = methodBody(explain, 'private Sql captureSql(');
claim('C1', 'CellExplainService.captureSql not found', capture !== null);
if (capture) {
  const borrowed = borrowedNames(capture);
  claim('C1', 'captureSql no longer borrows the connection from getNativeConnection', borrowed.length > 0);
  claim('C1', 'captureSql closes the borrowed, shared connection', !closesAny(capture, borrowed));
  claim('C1', 'captureSql does not close the statement it created', /closeQuietly\s*\(\s*statement\s*\)/.test(capture));
  const cellSetVar = capture.match(/(\w+)\s*=\s*statement\s*\.\s*executeOlapQuery\(/);
  claim('C1', 'captureSql does not keep the cellset it created', cellSetVar !== null);
  if (cellSetVar) {
    claim(
      'C1',
      'captureSql does not close the cellset it created',
      new RegExp(`closeQuietly\\s*\\(\\s*${cellSetVar[1]}\\s*\\)`).test(capture),
    );
  }
}

// C2: the concurrent launcher IT ships.
const itPath = 'saiku-launcher/src/test/java/org/saiku/launcher/it/ExplainKeepsSharedConnectionIT.java';
claim('C2', 'ExplainKeepsSharedConnectionIT does not ship', exists(itPath));
if (exists(itPath)) {
  const it = read(itPath);
  claim('C2', 'the IT does not call the explain endpoint', it.includes('/rest/saiku/api/ai/explain'));
  claim('C2', 'the IT does not run ordinary queries beside the explains', it.includes('/rest/saiku/api/query/execute'));
}

// C3: no production method closes a connection it borrowed from the cache (see the header for the
// exact scope). Method headers are matched with or without an access modifier, so package-private
// methods are examined too; a borrowing call outside every examined method fails the check.
const javaFiles = (dir) =>
  readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = join(dir, e.name);
    if (e.isDirectory()) return e.name === 'test' || e.name === 'node_modules' || e.name === 'target' ? [] : javaFiles(p);
    return e.name.endsWith('.java') ? [p] : [];
  });
const NOT_A_METHOD = new Set(['if', 'for', 'while', 'switch', 'catch', 'synchronized', 'try', 'return', 'new', 'else', 'do']);
// <modifiers/annotations> <return type> <name>(<params>) [throws ...] {
const METHOD_HEADER =
  /(^|\n)[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|protected|static|final|synchronized|abstract|default)\s+)*(?:<[^>]+>\s+)?([\w.]+(?:<[^;{}()]*>)?(?:\[\])*)\s+(\w+)\s*\(([^;{}]*?)\)\s*(?:throws\s+[\w.,\s]+)?\{/g;
let examinedCalls = 0;
for (const file of javaFiles(join(root, 'saiku-core'))) {
  const src = code(readFileSync(file, 'utf8'));
  const calls = [...src.matchAll(BORROW_CALL)].map((m) => m.index);
  if (calls.length === 0) continue;
  const covered = new Set();
  for (const m of src.matchAll(METHOD_HEADER)) {
    const [, , returnType, name] = m;
    if (NOT_A_METHOD.has(returnType) || NOT_A_METHOD.has(name)) continue;
    const headerStart = m.index + m[1].length;
    const body = methodBody(src.slice(headerStart), m[0].slice(m[1].length));
    if (!body) continue;
    const bodyStart = headerStart + m[0].length - m[1].length - 1;
    const bodyEnd = bodyStart + body.length;
    for (const c of calls) if (c >= headerStart && c < bodyEnd) covered.add(c);
    const borrowed = borrowedNames(body);
    claim('C3', `${relative(root, file)}#${name} closes a connection it borrowed from the cache`, !closesAny(body, borrowed));
  }
  examinedCalls += covered.size;
  claim(
    'C3',
    `${relative(root, file)}: ${calls.length - covered.size} of ${calls.length} borrowing calls are outside every examined method`,
    covered.size === calls.length,
  );
}
claim('C3', 'no borrowing call was examined at all', examinedCalls > 0);

// C4: the unit tests ship.
const test = read('saiku-core/saiku-service/src/test/java/org/saiku/service/olap/ai/explain/CellExplainServiceTest.java');
claim(
  'C4',
  'the borrowed-connection unit test does not ship',
  test.includes('sqlCaptureClosesItsStatementAndCellsetButNotTheBorrowedConnection'),
);
claim('C4', 'the failure-path unit test does not ship', test.includes('aFailedSqlCaptureStillLeavesTheBorrowedConnectionOpen'));

if (failures.length > 0) {
  for (const failure of failures) process.stderr.write(`${failure}\n`);
  process.exit(1);
}

process.stdout.write(`explain closes only its own statement and cellset; ${examinedCalls} borrowing calls examined, none closes the connection it got\n`);
