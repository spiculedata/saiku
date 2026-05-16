# Schema annotations for the AI Query API

The AI Query API (`/saiku/api/ai/*`) reads `saiku.semantic.*` annotations from
your Mondrian schema and uses them to shrink the LLM's guess space — turning
"figure out which measure is revenue" into a direct lookup. This document is
the reference for what annotations exist, where to put them, and how the agent
uses each one.

See [issue #818](https://github.com/spiculedata/saiku/issues/818) for the
motivation and design.

## Quick example

```xml
<Measure name='Store Sales' column='store_sales' aggregator='sum' formatString='#,###.00'>
    <Annotations>
        <Annotation name='saiku.semantic.description'>Net retail revenue in USD across all transactions.</Annotation>
        <Annotation name='saiku.semantic.synonyms'>revenue, turnover, top-line, sales</Annotation>
        <Annotation name='saiku.semantic.unit'>USD</Annotation>
        <Annotation name='saiku.semantic.currency'>USD</Annotation>
        <Annotation name='saiku.semantic.aggregation_kind'>sum</Annotation>
    </Annotations>
</Measure>

<Level attribute='Quarter'>
    <Annotations>
        <Annotation name='saiku.semantic.description'>Calendar quarter; aggregates 3 months.</Annotation>
        <Annotation name='saiku.semantic.synonyms'>quarterly, qtr, q</Annotation>
        <Annotation name='saiku.semantic.cardinality'>low</Annotation>
        <Annotation name='saiku.semantic.grain'>quarter</Annotation>
    </Annotations>
</Level>
```

After this, an agent posting `{"measures": [{"name": "revenue"}]}` resolves
straight to `[Measures].[Store Sales]` — no round-trip, no LLM-guess pass.

## Reference

All annotation keys live under the `saiku.semantic.*` namespace. Mondrian
ignores any annotation it doesn't recognise, so adding these is forward-safe.

### On `<Measure>`

| Key | Type | Allowed values | What the agent does |
|---|---|---|---|
| `saiku.semantic.description` | string | free text | Surfaces on the measure in `/ai/schema`. Helps the LLM ground "what does this measure count?". |
| `saiku.semantic.synonyms` | CSV string | free text | Each entry registers as an input alias on the cube's `measureAliases` map. Agent posting `name: "<synonym>"` resolves to the canonical measure. |
| `saiku.semantic.unit` | string | free text — `USD`, `hours`, `count`, `percent`, … | Tells the agent the dimensional unit. Stops "is this dollars or units?" guessing. |
| `saiku.semantic.currency` | string | ISO 4217 — `USD`, `EUR`, `GBP`, … | Optional. Set when `unit` is monetary. Lets clients render with the correct symbol / convert. |
| `saiku.semantic.aggregation_kind` | enum | `sum` &#124; `count` &#124; `distinct-count` &#124; `non-additive` | Tells the agent how the measure aggregates. `distinct-count` measures cannot be aggregated further — useful for stopping the agent from emitting `Sum([Customer Count])`. Unknown values are silently dropped + logged at `WARN`. |

### On `<Level>`

| Key | Type | Allowed values | What the agent does |
|---|---|---|---|
| `saiku.semantic.description` | string | free text | Surfaces on the level in `/ai/schema`. Critical for opaque level names like "State" (which 2-letter postal code?). |
| `saiku.semantic.synonyms` | CSV string | free text | Each entry registers as an input alias on the hierarchy's `levelAliases` map. Agent posting `level: "<synonym>"` resolves to the canonical level. |
| `saiku.semantic.cardinality` | enum | `low` &#124; `medium` &#124; `high` | How many distinct members live at this level. Drives the agent's crossjoin-explosion prediction — `high`-cardinality levels should be pre-filtered. Unknown values are silently dropped. |
| `saiku.semantic.grain` | enum | `year` &#124; `quarter` &#124; `month` &#124; `week` &#124; `day` &#124; `hour` &#124; `minute` | Tags a time level with its semantic grain. Lets the agent map user utterances ("quarterly", "by month") directly to the right level. Non-time levels just omit this. |
| `saiku.semantic.required_filters` | CSV string | `Hier1/Level1, Hier2/Level2` | Declares that any query touching this level must include a `filters[]` entry on each listed hierarchy/level with non-empty `members[]`. The converter returns a `VALIDATION_ERROR` 400 with the full required-filter set as `available[]` when violated. **Opt-in** — cubes without this annotation are unaffected. |

## Authoring guidance

### XML for permanent metadata, overlay for runtime curation

Two layers feed the typed `AiSchema` fields. They merge with **overlay wins on
conflict**:

1. **Mondrian schema XML** (`<Annotation name="saiku.semantic.X">...</Annotation>`)
   — the source of truth. Edit here when the metadata is permanent and
   coupled to the cube.
2. **Phase-3 `.generated.json` overlay** — for runtime curation by operators
   or schema-gen tooling. The overlay's `annotations` block uses the same
   slash-paths as `renames`:

   ```json
   {
     "renames": {},
     "annotations": {
       "measures.Store Sales": {
         "saiku.semantic.description": "Net retail revenue in USD."
       },
       "dimensions.Time.hierarchies.Time.levels.Quarter": {
         "saiku.semantic.cardinality": "low"
       }
     }
   }
   ```

When both XML and overlay set the same field, **the overlay wins.**

### CSV format for list-valued fields

`synonyms` and `required_filters` are comma-separated strings in XML
(Mondrian annotations are single string values). Whitespace around commas is
trimmed. Synonyms with embedded commas are not supported in XML — use the
overlay JSON form for those.

### Synonym collisions

If two measures (or two levels in the same hierarchy) declare the same
synonym, the first registration wins and a `WARN` is logged. Lint your
schemas to catch collisions before deployment.

### Annotation caching

Annotations are read at cube initialisation time. After editing the schema
XML, refresh the connection (Admin → Refresh datasource, or restart) to pick
up the changes.

## How the agent sees these fields

A typical `/ai/schema/<cubeId>` response now carries the typed fields:

```json
{
  "measures": {
    "store sales": {
      "name": "Store Sales",
      "uniqueName": "[Measures].[Store Sales]",
      "description": "Net retail revenue in USD across all transactions.",
      "synonyms": ["revenue", "turnover", "top-line", "sales"],
      "unit": "USD",
      "currency": "USD",
      "aggregationKind": "sum",
      "visible": true
    }
  },
  "dimensions": {
    "time": {
      "hierarchies": {
        "time": {
          "levels": {
            "quarter": {
              "name": "Quarter",
              "uniqueName": "[Time].[Time].[Quarter]",
              "description": "Calendar quarter; aggregates 3 months.",
              "synonyms": ["quarterly", "qtr", "q"],
              "cardinality": "low",
              "grain": "quarter",
              "requiredFilters": [],
              "sampleMembers": [ ... ]
            }
          }
        }
      }
    }
  },
  "measureAliases": {
    "revenue": "store sales",
    "turnover": "store sales",
    "top-line": "store sales",
    "sales": "store sales"
  }
}
```

`measureAliases` is the alias map the converter uses for input resolution.
The agent can post any of the synonyms in `measures[].name` and it'll
resolve to `Store Sales`.

## Linting checklist

Before shipping a schema with semantic annotations, verify:

- [ ] Every customer-facing measure has at minimum `description` + `unit`.
- [ ] Every monetary `unit` measure also has a `currency`.
- [ ] Every `<Level>` in a time hierarchy has a `grain`.
- [ ] No two measures (or two levels in the same hierarchy) share a synonym.
- [ ] All `aggregation_kind`, `cardinality`, and `grain` values are from the
      allowed enum lists above — typos get silently dropped (with a `WARN`
      in launcher logs).
- [ ] `required_filters` entries use the `Hierarchy/Level` short-name form
      and reference levels that actually exist in the cube.

## FoodMart reference

The bundled FoodMart `Sales` cube ships fully annotated as a working
example. See:
- `saiku-launcher/src/main/resources/seed/FoodMart4.xml` — the cube XML
- `/ai/schema/unknown_foodmart/FoodMart/FoodMart/Sales` — the surfaced typed
  fields once the launcher is running
