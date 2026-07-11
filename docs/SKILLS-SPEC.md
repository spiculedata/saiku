# Agent Skills — file format spec (`saiku-home/skills/`)

Skills are markdown files with YAML frontmatter. The launcher scans
`saiku-home/skills/**/*.md` on boot and again lazily whenever the
directory's aggregated signature (file count + max mtime + total size)
changes, so an operator can edit a skill and the DimSum widget picks up
the change on the next request.

Skills serve two invocation paths:

- **Slash:** `POST /rest/saiku/api/ai/ask` with a question that starts
  `/<skill-name>`. The service expands the message body verbatim in
  front of any user follow-up, so the LLM sees the workflow AND the
  refinement together.
- **Natural language:** the LLM sees the full catalogue in its system
  prompt and picks a matching skill when the user's ask lines up with a
  skill description.

## Frontmatter fields

```markdown
---
name: weekly-revenue-report
description: |
  Renders the weekly revenue rollup: total revenue by product family
  for the last 7 days, compared to the prior 7 days.
cube: unknown_foodmart/FoodMart/FoodMart/Sales
---

## Steps

1. …
```

| Field         | Required | Type   | Constraints                                                       |
|---------------|----------|--------|-------------------------------------------------------------------|
| `name`        | yes      | string | kebab-case, `[a-z][a-z0-9-]{0,63}`. Used as the slash slug.       |
| `description` | yes      | string | one line or block scalar. Shown in `/ai/skills` + LLM prompt.     |
| `cube`        | no       | string | `connection/catalog/schema/cubeName` ref. Scopes the skill.       |

Unknown top-level frontmatter keys are **rejected**. A typo (`descripton`)
surfaces as a `UNKNOWN_FIELD` parse error rather than silently landing a
skill without a description.

## Body

Everything after the closing `---` fence is the skill body. Markdown is
recommended but not required — the body is treated as opaque text and
pasted into the LLM prompt as-is when a slash-command matches. Keep it
tight; the LLM has to read the whole file.

## Errors

Broken skills don't take down the catalogue: a parse error on one file
leaves the others intact. Errors surface via
`GET /rest/saiku/api/ai/skills?errors=true`, each carrying a stable
machine-readable code and a human-readable message:

| Code                 | When                                                              |
|----------------------|-------------------------------------------------------------------|
| `EMPTY_SKILL`        | File is empty or all whitespace.                                  |
| `MISSING_FRONTMATTER`| No leading `---` fence.                                           |
| `EMPTY_BODY`         | Frontmatter parsed but no markdown after the closing fence.       |
| `MALFORMED_YAML`     | YAML parser rejected the frontmatter.                             |
| `MISSING_FIELD`      | Required field (`name` / `description`) not present.              |
| `BLANK_FIELD`        | Required field present but empty / whitespace-only.               |
| `TYPE_MISMATCH`      | Field present but wrong type (e.g. `name: 42`).                   |
| `INVALID_NAME`       | `name` doesn't match `[a-z][a-z0-9-]{1,63}`.                      |
| `UNKNOWN_FIELD`      | Frontmatter contains a field not in the schema.                   |
| `DUPLICATE_NAME`     | Two skill files declared the same `name`.                         |
| `IO_ERROR`           | Could not read the file (filesystem-level, not parse-level).      |

## REST surface

- `GET  /rest/saiku/api/ai/skills`           — list catalogue (name, description, cube).
- `GET  /rest/saiku/api/ai/skills?errors=true` — include parse errors.
- `GET  /rest/saiku/api/ai/skills/{name}`    — full body of one skill.
- `POST /rest/saiku/api/ai/skills/refresh`   — force a rescan.

## Layout

```
saiku-home/
  skills/
    weekly-foodmart-rollup.md
    marketing/
      utm-conversion.md
```

Subdirectories are walked recursively. The name (from frontmatter) is the
unique key, not the path — two files in different subdirectories that
declare the same `name` produce a `DUPLICATE_NAME` error on the second
one to load.

## Auth

Skills load under the launcher's on-disk workspace scope; there is no
per-user filtering in v1. Operators who want per-role skills scope them
via workspace directories (`saiku-home/{workspace}/skills/`) and use the
existing workspace-scoping infrastructure.

## Example: bundled demo skill

`saiku-launcher/src/main/resources/seed/skills/weekly-foodmart-rollup.md`
ships alongside the FoodMart seed data. Fresh launcher installs stage it
into `saiku-home/skills/` so the DimSum widget has something in the
catalogue on first boot.
