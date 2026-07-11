# Agent Spaces — persona layer over the AI ask surface (`saiku-home/agent-spaces/`)

Agent Spaces are named, admin-authored **personas** that scope an AI ask.
A space bundles:

- A `systemPrompt` that anchors the LLM in the persona voice
  (*"You are the FoodMart Sales Analyst. Prefer weekly grain…"*).
- A `cubeAllowlist` — the server refuses to ask against any cube not on
  this list, so the persona genuinely owns its scope.
- A `skillAllowlist` — subset of [Agent Skills](./SKILLS-SPEC.md) the
  persona can reach. Empty = all skills allowed.
- A `suggestedPrompts` list surfaced by the UI as quick-start chips.
- An identity (`id`, `name`, `description`) for the catalogue.

Spaces are persisted as JSON files under `saiku-home/agent-spaces/`.
The launcher scans lazily (mtime signature check on every read; no
watcher thread) and swaps snapshots atomically.

## On-disk shape

```json
{
  "id": "foodmart-sales-analyst",
  "name": "FoodMart Sales Analyst",
  "description": "Weekly and monthly sales rollups over the FoodMart Sales cube.",
  "systemPrompt": "You are the FoodMart Sales Analyst. Prefer weekly and monthly time grain unless the user asks otherwise. Lead with the top three lines by absolute value. Flag any figure that swings by more than 20% versus the prior period. Be analytical, brief, and numbers-first.",
  "cubeAllowlist": [
    {"connectionName": "unknown_foodmart", "catalog": "FoodMart", "schema": "FoodMart", "cubeName": "Sales"}
  ],
  "skillAllowlist": ["weekly-foodmart-rollup"],
  "suggestedPrompts": [
    "How did Store Sales track last week vs the prior week?",
    "Break down Store Sales by Product Family for Q4.",
    "/weekly-foodmart-rollup"
  ]
}
```

| Field              | Required | Type              | Notes                                                                          |
|--------------------|----------|-------------------|--------------------------------------------------------------------------------|
| `id`               | yes      | string            | kebab-case, `[a-z][a-z0-9-]{0,63}`. Path segment in `/ai/spaces/{id}/ask`.     |
| `name`             | yes      | string            | Display name. Shown in the catalogue + sidebar.                                |
| `description`      | no       | string            | One-line summary. Shown in the space picker.                                   |
| `systemPrompt`     | no       | string            | Prepended to the built-in `SYSTEM_PROMPT` on every ask.                        |
| `cubeAllowlist`    | yes      | `AiCubeRef[]`     | At least one entry. Refs outside this list → `FORBIDDEN`.                      |
| `skillAllowlist`   | no       | `string[]`        | Filters both slash-command routing and the LLM catalogue. Empty = all skills. |
| `suggestedPrompts` | no       | `string[]`        | Free-form quick-start questions the UI can render.                             |

Unknown top-level keys are **rejected** — a typo (`sytemPrompt`)
surfaces as `UNKNOWN_FIELD` rather than being silently dropped.

## Enforcement — how a space actually locks scope

`POST /rest/saiku/api/ai/spaces/{id}/ask` routes through
`AiAskService.askInSpace()`:

1. **Space lookup.** Missing space → `403` with `space not found: {id}`.
2. **Cube resolution.**
   - If the body omits `cube`, the space's first `cubeAllowlist` entry
     is used as the default.
   - If the body supplies `cube`, it must match one of the allowlist
     entries on all four coordinates (connection, catalog, schema, cube
     name). Non-match → `403` with `FORBIDDEN: cube {name} is not in
     space '{id}' allowlist`.
3. **Skill catalogue filter.** The space's `skillAllowlist` gates:
   - Slash-command routing — `/weekly-rollup` in a space that doesn't
     allowlist it falls through as a raw ask.
   - The catalogue injected into the LLM system prompt — the model only
     sees skills the space allows.
4. **System prompt injection.** The space's `systemPrompt` is prepended
   to the built-in `SYSTEM_PROMPT` on the provider side. The user's
   `history` and `question` can't override it.

The upshot: no matter what the caller sends, the LLM only sees the
persona voice + the persona's cubes + the persona's skills.

## Error codes

Parse failures surface via `GET /rest/saiku/api/ai/spaces?errors=true`:

| Code               | When                                                                    |
|--------------------|-------------------------------------------------------------------------|
| `EMPTY_SPACE`      | File is empty or all whitespace.                                        |
| `MALFORMED_JSON`   | JSON parser rejected the body.                                          |
| `MISSING_FIELD`    | Required field (`id`, `name`) not present.                              |
| `BLANK_FIELD`      | Required field present but empty / whitespace-only.                     |
| `TYPE_MISMATCH`    | Field present but wrong type (`suggestedPrompts: {...}` when a `[]`).    |
| `INVALID_ID`       | `id` doesn't match `[a-z][a-z0-9-]{0,63}`.                              |
| `EMPTY_ALLOWLIST`  | `cubeAllowlist` present but empty — a space with no cubes is unusable. |
| `INVALID_CUBE_REF` | An allowlist entry is missing a coordinate (`connectionName`, etc.).    |
| `UNKNOWN_FIELD`    | Frontmatter contains a field not in the schema.                         |
| `DUPLICATE_ID`     | Two files declared the same `id`.                                       |
| `IO_ERROR`         | Could not read the file (filesystem-level).                             |

## REST surface

- `GET  /rest/saiku/api/ai/spaces` — catalogue (compact summaries — id,
  name, description, suggestedPrompts). The `systemPrompt` and
  `cubeAllowlist` are omitted so an embed can't scrape the routing.
- `GET  /rest/saiku/api/ai/spaces?errors=true` — same, plus parse
  errors.
- `GET  /rest/saiku/api/ai/spaces/{id}` — full record. Used by the
  admin UI when editing a persona.
- `POST /rest/saiku/api/ai/spaces/{id}/ask` — space-scoped ask. Body
  shape mirrors `/ai/ask` but `cube` is optional (falls back to the
  space default).
- `POST /rest/saiku/api/ai/spaces/refresh` — force a rescan.

## Bundled examples

Fresh launcher installs stage two working personas:

- **[FoodMart Sales Analyst](/saiku-launcher/src/main/resources/seed/agent-spaces/foodmart-sales-analyst.json)** — analytical, brief, numbers-first. Includes the `weekly-foodmart-rollup` skill in its allowlist so `/weekly-foodmart-rollup` is available as a slash command.
- **[FoodMart Finance Ops](/saiku-launcher/src/main/resources/seed/agent-spaces/foodmart-finance-ops.json)** — cautious, precise, margin-focused. Empty `skillAllowlist` = all skills allowed (but the LLM catalogue will only include what the launcher has registered).

Both scope to the FoodMart Sales cube — a fresh demo has personas ready
to click without any operator authoring.

## Non-goals for v1

- **Per-user / per-role scoping.** Spaces are per-launcher for v1.
  Multi-tenant workspaces can layer scoping by mapping workspace
  directories onto a per-workspace registry root — deferred.
- **Data-scope override.** The proposed shape in
  [saiku#1440](https://github.com/spiculedata/saiku/issues/1440) mentions
  pinning a role's data-scope filters onto every space-scoped query.
  Deferred to the RLS follow-up so the annotation lives in one place.
- **Admin CRUD UI.** The runtime enforcement is complete; a Svelte
  admin UI for authoring spaces via the browser is a follow-up slice.
  Operators can author the JSON directly in v1.
- **Embed integration** (`<saiku-embed kind="ai" path="space:sales-analyst">`).
  Follow-up — the runtime is ready to consume it.
