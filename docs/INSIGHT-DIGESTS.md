# Insight digests (saiku#1119)

An *insight digest* is the "here's what changed" half of a scheduled
`DASHBOARD_DIGEST` email: instead of only a table of current values, the mail
opens with a short list of bullets describing how each measure moved **since the
previous period**, followed by the period-over-period table behind them.

It is layered onto the existing [#943 link-based dashboard digest](./AI-QUERY-API.md)
rather than replacing it: a job payload with no `insight` block produces
byte-for-byte the email it produced before this feature.

```
+--------------------------------------------------+
| What changed since the previous period           |
|  - Total Units rose +20.0% to 1,200 (was 1,000)  |
|  - Store Sales fell -8.4% to 42,100              |
|                                                  |
| Measure      | Current value                      |
| Total Units  | 1,200                              |
| Store Sales  | 42,100                             |
|                                                  |
| Period over period                              |
| Measure      | Current | Previous | Change         |
| Total Units  | 1,200   | 1,000    | +200 (+20.0%) |
+--------------------------------------------------+
```

## Payload

```jsonc
{
  "type": "DASHBOARD_DIGEST",
  "payload": {
    "dashboard": { "path": "shared/exec.saikudash", "title": "Executive Overview" },

    "measures": [
      {
        "cube": "FoodMart/FoodMart/FoodMart/Sales",
        "measure": "Unit Sales",
        "label": "Total Units",
        "filters": [ /* optional static slicers, AiFilterSelection shape */ ],

        // NEW in #1119 — opt this measure into a period-over-period comparison.
        "period": {
          "dimension": "Time",
          "hierarchy": "Time",
          "level": "Quarter",
          "current": "qtd"          // optional, default "qtd"
        }
      }
    ],

    // NEW in #1119 — turn the insight on for this job. Absent = off (#943 behaviour).
    "insight": {
      "narrate": true,             // optional, default true — LLM narration
      "maxBullets": 3              // optional, default 3, max 10
    },

    "recipients": ["ops@example.com"]   // unchanged: gate + self-email fallback apply
  }
}
```

### The `period` block

Both sides of the comparison are expressed as ordinary typed-AI-Query
`op: "relative"` slicers, so **no MDX is hand-written** and no date arithmetic
happens in the digest:

| side | slicer | MDX the converter emits |
| --- | --- | --- |
| current | `value: "qtd"` (or `last_n_*` / `ytd` / `mtd`) | `Qtd()` / `Tail(level.Members, n)` |
| previous | `value: "previous_period"` (always) | `Tail(level.Members, 2).Item(0)` |

The previous period is always the member immediately **before** the current one
on the *same* level, so the two numbers are always like-for-like. `current` may
not be `previous_period` — a job author who does that gets a validation error at
job-creation time rather than a digest that always reports zero change.

A `period` block **replaces** any static filter the measure already declared on
the same hierarchy (the AI Query converter rejects two filters on one hierarchy,
so without the replacement the job would fail at query time).

A measure with **no** `period` block still contributes a row to the plain
current-value table; it simply has no delta.

## Narration, and the two ways it fails safe

`insight.narrate: true` (the default) asks the configured LLM provider
(`saiku.ai.ask.provider`) to narrate the deltas. The model is handed:

- the **PII-filtered agent view** of the cube schema (never the raw schema), and
- the **structured deltas the server already computed** — both period values and
  the change — as the cellset digest.

The `emit_insight` tool is forced, so the model can describe the figures but
cannot emit a query and nothing is executed. The prompt asks for a capped bullet
list citing specific measures; the response is reduced to bullets and every
bullet is HTML-escaped like the rest of the mail body.

Three things make narration fail **soft**, never losing the digest:

1. **Egress policy.** The deltas are business figures, so they only leave the box
   when the dedicated LLM-egress guard permits `AGGREGATED` values
   (`SAIKU_AI_LLM_EGRESS` / `ai.llm.egress`). An unwired guard denies — fail-closed.
2. **No provider / degraded response.** Unconfigured provider, transport error,
   refusal, wrong tool, prose without bullets, or an unloadable cube schema all
   fall back to the deterministic `TemplateDigestNarrator`.
3. **Template narration (`insight.narrate: false`).** No model call at all —
   bullets are ranked by relative change and stated in plain words.

The template also ranks measures with no prior baseline (previous = 0, where a
percentage change is undefined) by absolute change, so a brand-new metric still
surfaces instead of being dropped.

## Per-user opt-out

A user opts out with their own account preferences — no new endpoint, no new
store, and no path to anyone else's document (the username comes from the
security context, never from the request):

```bash
curl -X PUT -H 'content-type: application/json' \
     -d '{"dashboardDigestOptOut": true}' \
     https://<saiku>/rest/saiku/api/preferences
```

The key is checked **before the first query**, so an opted-out owner is not
queried, not narrated and not emailed — the run is skipped entirely. An absent,
unreadable or corrupt preferences document reads as "not opted out", which is the
state of every account that has never touched the setting.

## Test plan (issue #1119)

| Requirement | Covered by |
| --- | --- |
| Digest fires on schedule and includes only the configured measures | `InsightDigestBuilderTest`, `DashboardDigestJobHandlerTest` |
| Narration cites specific tiles + measures | `NlAskDigestNarratorTest` (prompt carries the delta table; bullets are the model's own) |
| Per-user opt-out fully suppresses the job | `DigestOptOutTest`, `DashboardDigestJobHandlerTest#anOptedOutOwnerIsNotQueriedAndNotEmailed` |

## Not in this change

- **"Starred dashboards".** There is no star/ favourite feature in the product
  yet. The digest is driven by the measures an admin names in the job payload —
  the subscription *is* the selection. Once stars land, "all starred dashboards
  for this user" becomes a job-payload generator, not a change to this path.
- **Slack / Teams delivery** (#1099). Delivery is whatever the digest job
  already does: named recipients through the gate, otherwise the server's own
  self-address. Narration is channel-agnostic — it produces bullets, not an
  email.
- **Anomaly detection (phase 3 of the issue)** — z-scores over rolling history
  are a separate feature; the delta table here is the natural input for it later.