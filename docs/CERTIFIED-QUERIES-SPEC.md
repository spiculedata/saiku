# Certified Query Catalogue (saiku#1430)

Admin-approved saved queries the agent invokes **verbatim**.

The problem this solves is narrow and specific: a regulated buyer whose CFO will not tolerate the
agent inventing a "net revenue" calculation afresh on every ask. Saiku already validates names
against the schema and applies Mondrian roles and k-anonymity — different discipline, orthogonal to
this. What was missing was a way for an operator to say:

> When the user asks about monthly revenue, always run **this exact query**. Never re-derive it.

## What "certified" guarantees

Two properties, both load-bearing:

1. **Verbatim.** The query body is parsed once, at scan time, and executed as-is. Nothing
   downstream re-derives, optimises or helpfully extends it. The single permitted mutation is the
   same null / slash-bearing name fix-up `POST /ai/query/saved` applies, because a name containing a
   slash is rejected by Jetty's strict URI handling downstream. The query itself is never touched.
2. **Attributed.** Every response produced by a certified run carries `"source": "certified"` and
   `"certifiedId": "<id>"`, so a downstream system can audit *which* approved definition produced a
   figure — not merely that some approved definition did.

## On-disk format

`saiku-home/certified/*.json`. One file per certified query:

```json
{
  "id": "monthly-net-revenue",
  "description": "Official monthly net revenue by region and product family.",
  "matchIntent": ["monthly revenue", "monthly net revenue", "revenue by month"],
  "query": { "<ThinQuery JSON>": "the saved query, exactly as it runs" }
}
```

`query` is Saiku's existing `ThinQuery` shape — the same JSON a `.saiku` file in the JCR repository
holds. It must carry a cube (`name` + `connection`) and something to run (an `mdx` statement or a
`queryModel`).

The catalogue is **read-only from the API**: entries are admin-authored files in the server's own
home directory, not user content. Drop a file in, or remove one, and the catalogue picks it up on
the next read (mtime signature check — no restart).

### Parse errors are loud

Unknown top-level fields are rejected, so a typo (`matchInents`) surfaces instead of silently
producing an entry that can never fire. Every failure carries a stable code, surfaced by
`GET /ai/certified?errors=true` and logged at WARN:

| Code | Meaning |
| --- | --- |
| `EMPTY_CERTIFIED_QUERY` | File is empty or blank |
| `MALFORMED_JSON` | Not valid JSON, or the top level isn't an object |
| `MISSING_FIELD` | `id` absent |
| `BLANK_FIELD` | `id` present but blank |
| `TYPE_MISMATCH` | A field has the wrong JSON type (e.g. `matchIntent` as a string) |
| `INVALID_ID` | `id` doesn't match `[a-z][a-z0-9-]{0,63}` |
| `INVALID_MATCH_INTENT` | `matchIntent` missing, not an array, empty, blank entry, non-string entry, > 32 entries, or an entry > 200 chars |
| `MISSING_QUERY` | No `query` body |
| `INVALID_QUERY` | `query` is not a runnable ThinQuery (no cube, no name, no connection, or nothing to run) |
| `UNKNOWN_FIELD` | Unknown top-level key |
| `DUPLICATE_ID` | Another file already declared this id (the first by scan order wins) |
| `IO_ERROR` | The file could not be read |

A broken file never takes the catalogue down — it is discarded, reported, and the rest of the
catalogue keeps working. The failure mode is loud-but-contained: one mistyped MDX file must not mean
the CFO's certified revenue query silently stops being routed to.

## Intent matching

Routing is a **pure, deterministic function** of the question and the authored `matchIntent`
phrasings. There is no LLM in the decision:

1. The question and each phrasing are lower-cased, punctuation collapsed to spaces, and split into
   content tokens (stop words dropped, naive plural stripped so `revenue`/`revenues` match).
2. A phrasing matches only when **every** one of its content tokens appears in the question.
   Partial coverage scores zero — `monthly revenue` must not fire on a question that only says
   `monthly`.
3. Across phrasings and entries, the most specific full match wins (most content tokens), ties
   broken by catalogue order (id ascending) so the outcome is stable across restarts.

Determinism is the point. A prompt instruction ("prefer the certified answer") is a request the
model can weigh against its own judgement; a pre-LLM routing decision is a guarantee.

## REST API

All under `/rest/saiku/api/ai`.

| Endpoint | Purpose |
| --- | --- |
| `GET /certified` | Catalogue as summaries: `id`, `description`, `matchIntent`. The approved MDX is deliberately **not** listed — an embed should learn that an approved answer exists, not scrape and run the query itself. `?errors=true` adds parse failures. |
| `GET /certified/{id}` | One entry in full, including the `query` body — the "show me what would actually run" view for an operator reviewing an approval. |
| `POST /certified/{id}/run` | Execute verbatim. Returns the standard `AiQueryResponse` (records by default, `?format=matrix` supported) plus `source: "certified"` and `certifiedId`. No body, no filters, no overrides — the value of this endpoint is precisely that the query cannot be edited in flight. Runtime filters belong on `/ai/query/saved` for queries that live in the JCR repository. |
| `POST /certified/refresh` | Force a rescan; returns the entry + error counts. |

`404` for an unknown id (or when the registry isn't wired at all). The run endpoint is behind the
same `AiPolicyGuard` aggregated-result gate as every other data-returning endpoint, and runs through
the same `k-anonymity` suppression as `/ai/query`.

## Ask-layer integration

`POST /ai/ask` (and its streaming variant) prefer a certified answer over a re-derived one. The
service routes **before** the provider call, so a certified match means the model is never asked at
all — there is nothing for it to re-derive differently.

Routing fires only for a genuine data ask:

- **No cellset digest on screen.** A digest means the user is asking a follow-up about data already
  rendered ("why did June drop?"); substituting a fresh certified query there would answer a
  question nobody asked.
- **No explicit intent override** other than `query` — an operator who picked a mode in the drawer
  has said what they want.
- **No slash command.** An explicit `/skill` invocation outranks a certified match.
- **Same cube.** The certified query must be approved for the cube being asked about. Running a
  query approved for one cube against another answers a different question than the approved one.

Everything else falls through to the model with no behavioural difference.

On a certified match the response is the normal ask envelope with `response.source = "certified"`
and `response.certifiedId = "<id>"`, and **`request` left null** — there is no model-authored query,
and hydrating the canvas builder from one would invite an edit that silently de-certifies the
numbers. Streamed asks carry the same fields in their `final` event.

The certified short-circuit composes with agent spaces (#1440): a persona still scopes the cube, and
a certified answer for that cube still wins.

## Bundled FoodMart demo

A fresh launcher stages one certified query,
`saiku-launcher/src/main/resources/seed/certified/monthly-store-sales-by-country.json`, into
`saiku-home/certified/`: Finance's approved monthly store sales (and cost) by store country and
product family, matching intents `monthly revenue`, `monthly sales`, `revenue by month` and friends.
So "what was monthly revenue" on a fresh demo is answered from the approval, attributed as certified.

Seeding is idempotent (only lands when absent) — an operator's own `certified/` directory is never
overwritten.

## Not in v1

- **No admin CRUD UI.** Entries are hand-authored JSON; the catalogue is scanned, never written
  through the API. (Agent Spaces #1440 has `PUT`/`DELETE` because personas are edited in the admin
  panel; certified entries deliberately aren't.)
- **No "always" rules.** The issue's context also mentions rules like *"never join
  `dim_customer` without `user_attribute.tenant_id`"*. Those constrain *re-derivation* rather than
  substitute for it, and none of the acceptance criteria cover them. A future `rules[]` field would
  ride on the same registry.
- **No certified routing on the chained-ask path** (`/ai/ask/chain/stream`), which builds and
  iterates server-side. The direct `/ai/certified/{id}/run` endpoint covers programmatic use.
- **No UI surface.** The catalogue endpoints and the ask integration are the deliverable; the DimSum
  widget can pick them up later.

## Related

- `docs/AI-QUERY-API.md` — the typed query surface a certified query is executed through.
- `docs/AGENT-SPACES-SPEC.md` — personas that scope an ask; composes with this feature.
- `docs/SKILLS-SPEC.md` — admin-authored markdown workflows; the `/skill` route outranks a
  certified match.
- Pairs with #1424 (agent evals): evals cover ad-hoc answers, certified queries cover "always
  answer the CFO's variance question the same way". Together they are a complete accuracy story.
