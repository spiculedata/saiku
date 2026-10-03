# Semantic model diff & content validator (saiku#1434)

Reviewable semantic-layer edits. `saiku model diff` compares two semantic models —
Mondrian XML or Apache Ossie YAML — and lists every saved query, dashboard and App
Builder app that the change would break. The same report is available over REST and,
on a pull request, as a comment.

The point is to make a model change reviewable at the same standard as a code change:
when a measure is renamed, the reviewer sees *which dashboards still point at the old
name* before merging, not after.

## What it reports

```markdown
## FoodMart / Sales
- Renamed measure: Unit Sales → Units Sold
- Renamed level: Product Family → Product Line
- Removed measure: Store Cost

## Broken references (2 files affected)

- homes/admin/exec-dashboard.saikudash — Family Chart — [Product].[Product Family] (level no longer exists in the dimension)
- homes/admin/FoodMartTrend.saiku — (file) — [Measures].[Unit Sales] (measure no longer exists in the cube)
```

Changes are grouped per cube, in the order a reviewer wants them: removals and renames
(the contract-breaking ones) before additions. Renames are detected, not guessed — see
[Renames](#renames) below.

## CLI

```bash
# Two files on disk, scanning a Saiku home for what they would break.
saiku model diff --before FoodMart4.xml --after FoodMart4.proposed.xml \
  --repository ./saiku-home/repository/data

# Straight off a pull request: baseline from the base branch, proposal from the head.
saiku model diff --from-git origin/development --from-git HEAD \
  --before saiku-launcher/src/main/resources/seed/FoodMart4.xml \
  --after   saiku-launcher/src/main/resources/seed/FoodMart4.xml \
  --repository ./saiku-home/repository/data

# Machine-readable, for another tool to consume.
saiku model diff --before a.xml --after b.xml --json
```

`--before` and `--after` are paths — on disk, or (with `--from-git`) inside a git ref.
`--from-git` may be given once (both sides from the same ref) or twice: the first for
the baseline, the second for the proposal. Omitting `--after` compares a file against
itself, which is the quick way to ask "does my current model still resolve every
dashboard I have?".

### Exit codes

| Code | Meaning |
| ---- | ------- |
| 0 | No broken references. |
| 1 | Broken references found — the gate signal. |
| 2 | Input unreadable, or not a model we can parse. |
| 3 | The two sides are different model formats (Mondrian XML vs Ossie YAML). |

`--no-fail-on-broken` always exits 0, for when the report is generated for a human to
read rather than to gate a build.

## REST

```
POST /rest/saiku/api/admin/model/diff
```

Admin role required. Each side is either inline content or a path:

```json
{
  "before": { "content": "<Schema name='FoodMart'>…" },
  "after":  { "path": "datasources/FoodMart4.proposed.xml" },
  "repository": "homes/admin"
}
```

- Default response: the report as JSON — `format`, `beforeName`, `afterName`, `changes[]`,
  `brokenReferences[]`, `filesScanned`, `affectedFiles`, `clean`, `breaking`.
- `?format=markdown` returns the `text/markdown` block above, byte-identical to the CLI's.
- Rejections are `400` with a stable reason code as the first token: `UNKNOWN_FORMAT`,
  `MALFORMED`, `CROSS_FORMAT`.

Every path in the request is resolved **inside** the configured repository root
(`<saiku-home>/repository/data`). A relative `..`, an absolute path, or a symlink
pointing out is refused — otherwise the endpoint would be an arbitrary-file-read and
directory-enumeration primitive for any admin. There is no opt-out.

`clean` is the merge signal: true when nothing in the repository is left pointing at a
member the after-model no longer resolves. A rename is *always* reported as breaking
(its old name is gone), but a rename whose references the same commit updated is clean.

## What gets scanned

`.saiku` (saved queries), `.saikudash` (dashboards) and `.saikuapp` (App Builder apps)
under the repository root, in both binding styles:

- **MDX** — a saved query's `mdx`, or any string value that looks like an MDX
  statement. Bracketed members are extracted with a narrow regex rather than a real MDX
  parse: this runs over a whole repository on every pull request, and it over-collects
  rather than under-collects, which is the safe direction for a safety net.
- **Structured** — the query2 body a tile carries (`measures: [{name}]`,
  `rows: [{dimension, level}]`, `filters[].member`) and `kpi.measure`.

Member lookup is case-insensitive (Mondrian resolves member names that way) and accepts
a member's **caption** as well as its name — FoodMart4 captions `Store Sales Growth` as
`MoM Growth`, and a tile authored in the UI binds that caption.

## Renames

Renaming a measure or a level is the most common reviewable model edit, and from the
reference walker's point of view it is indistinguishable from a remove plus an add.
Without pairing, every intentional rename produces a wall of "broken reference" noise —
the opposite of the signal this feature exists to provide.

Two elements are paired as a rename when they are the same kind in the same cube (and
the same container, for levels) **and** their attribute signature — everything except
the name: column, aggregator, format, expression — matches with a Jaccard similarity of
at least `0.6` (`ModelDiffEngine.RENAME_SIMILARITY_THRESHOLD`). A cube rename is matched
on its member set instead, so renaming a cube *and* adding a measure to it still reads
as one rename plus one addition.

This is deliberately conservative. A rename we miss degrades to "removed + added", which
is still correct output; a rename we invent would hide a real removal.

## False positives

The acceptance criterion in saiku#1434 is a **< 5% false-positive rate** on the FoodMart
demo, measured against intentional-rename tests. The scanner is quiet by construction:

- case-insensitive matching, and captions resolve as aliases;
- a file whose cube is not in the model is reported **once** as an unknown cube, and its
  members are then not checked — repositories routinely hold several schemas, and
  flagging every member of a cube belonging to another datasource is noise, not a finding;
- a level whose dimension is missing reports the dimension, not the level, for the same
  reason;
- a file that is not valid JSON is skipped, not reported (broken file syntax is a
  different validator's job).

The rate is measured automatically in `ModelDiffServiceTest` over 60 randomised rename
sets: every reported reference is re-checked against the after-model, and the test fails
if the fraction that still resolves reaches 5%. It is also verified end-to-end against
the shipped fixtures — the bundled `FoodMart4.xml` seed plus the demo dashboard, saved
query and app — which produce **zero** findings on a no-op diff.

## Bad input

A diff over input we cannot parse is an **error**, never an empty diff. Silently
reporting "no changes" for a truncated schema would be worse than useless, because it
is green. So:

- malformed XML, malformed YAML, an Ossie document with no `semantic_model`, an entry
  with no name, an empty payload, a file that does not exist — all rejected with a reason;
- a model whose only changes are not readable is never reported as clean;
- Mondrian parsing has DOCTYPE declarations, external entities and external DTD access
  disabled — model payloads arrive from REST bodies, so an XXE-capable parser here would
  be a file-read primitive;
- a cross-format diff (Mondrian XML against Ossie YAML) is refused outright.

`ModelDiffFuzzTest` covers this with truncated schemas (every prefix of a valid schema),
random byte soup, empty and missing repositories, and unparseable repository files.

## CI

`.github/workflows/model-diff.yml` runs on any pull request that touches a `*.xml`,
`*.yaml` or `*.yml`, diffs each changed model against the base branch, and posts a
sticky PR comment with the report (updating it in place on each push).

It is deliberately **not** a required check: it needs the base branch's model, so it
cannot run on a push, and its finding is information for a reviewer rather than a merge
blocker. The workflow builds only `saiku-service` — the validator is self-contained
there, so the job skips the SvelteKit bundle — and invokes
`org.saiku.service.schema.diff.ModelDiffTool` directly. That is the same class the
`saiku model diff` subcommand calls, which is what makes the CI comment and a local run
identical.

## Where the code lives

| Piece | Location |
| ----- | -------- |
| Parsers (`MondrianXmlModelParser`, `OssieYamlModelParser`) | `saiku-service/…/service/schema/diff/` |
| Diff engine (rename pairing, change list) | `ModelDiffEngine` |
| Reference walker | `BrokenReferenceScanner` |
| Markdown / report | `ModelDiffReport` |
| Shared runner (CLI + CI) | `ModelDiffTool` |
| REST endpoint | `saiku-web/…/rest/resources/model/ModelDiffResource` (wired in `saiku-beans.xml`) |
| CLI | `saiku-launcher/…/launcher/ModelCommand` (`saiku model diff`) |
| CI | `.github/workflows/model-diff.yml` |

The engine is format-agnostic: both parsers produce the same `ModelSnapshot`, and the
element kinds are mapped rather than shared (Ossie metrics are measures, datasets are
dimensions, fields are levels) so one report speaks for both serialisations.

## Related

- [#1428 Model IDE](https://github.com/spiculedata/saiku/issues/1428) — the diff is what
  its "branch preview" surfaces before merge.
- [#1424 agent evals](https://github.com/spiculedata/saiku/issues/1424) — a model diff
  plus a green eval run is the "safe to merge" gate.
- [`AI-QUERY-API.md`](AI-QUERY-API.md) — the typed query surface whose member names this
  validator checks.
