# Operator hardening knobs

Configuration reference for the guardrails an operator is expected to tune:
the SMTP relay SSRF gate on the admin mail wizard, and the daily LLM cost
budget on the AI ask surface. Both were added under
[#1918](https://github.com/spiculedata/saiku/issues/1918).

For the PII annotation contract — which is schema metadata rather than
deployment configuration — see
[`schema-annotations.md`](schema-annotations.md#saikusemanticpii--the-pii-contract).

---

## SMTP relay host (`saiku.mail.smtp.*`)

The admin mail wizard (`POST /saiku/api/admin/mail-config`) is admin-only, but
on a multi-tenant deployment "admin" is a tenant administrator, and the wizard's
"send a test email" button dials whatever host the wizard was given. SMTP has no
URL, so there is no `https`-only rule to lean on, and the response distinguishes
connection-refused from timeout from a protocol banner — which is enough to
blind-map which internal host:port pairs are listening (your control-plane
database, a cloud metadata endpoint, a neighbouring tenant's service).

Every host a wizard save proposes is therefore put through the same
resolve-and-range gate that alert webhooks have used since
[#1098](https://github.com/spiculedata/saiku/issues/1098), plus an SMTP port
allowlist. The check runs **on save** and again **immediately before the
test-send dial**, because `/test` is the endpoint that actually opens the
socket — a config written by an older build, or seeded straight into the
encrypted store, would otherwise be a ready-made probe.

Refused:

- hosts that are not a bare hostname or IP literal (a scheme, path, `user@`,
  or a smuggled `:port` in the host field);
- obfuscated numeric IPv4 (`2130706433`, `0x7f000001`, `0177.0.0.1`, `127.1`);
- internal names — `localhost`, `*.localhost`, `*.local`,
  `metadata.google.internal`, and any dotless short name;
- hosts that resolve to loopback / link-local / site-local / any-local /
  multicast / CGNAT (`100.64/10`) / IPv6 unique-local — and a host with even
  ONE internal address in its answer is refused, which is what closes the
  split-horizon / DNS-rebinding case;
- hosts that do not resolve at all;
- ports outside the allowlist.

| Property | Default | Meaning |
|---|---|---|
| `saiku.mail.smtp.allowedPorts` | `25,465,587,2525` | Comma-separated SMTP ports. A malformed entry is ignored rather than fatal — a typo must not brick mail configuration. |
| `saiku.mail.smtp.allowedHosts` | *(empty)* | Comma-separated hosts cleared **before** resolution. A leading-dot entry is a suffix match, so `.corp.example.com` covers every relay in the estate. Waives the range check only — syntax and port rules still apply. |
| `saiku.mail.smtp.allowPrivateRange` | `false` | `true` waives *both* the internal-name heuristic and the address-range check, for deployments whose mail really is internal. Syntax, obfuscated-numeric and port rules still apply, so it is a scoped "mail is internal here" switch, not a blank cheque. |

**The recommended shape is an ops-managed relay.** Set `SAIKU_MAIL_SMTP_HOST` /
`SAIKU_MAIL_FROM` in the environment: `MailConfigResolver` then reports the
deployment as ops-managed and the in-app wizard goes read-only (a save returns
`409`), so the tenant admin never chooses the host at all. The escape hatches
above exist for on-prem deployments where the relay is legitimately an RFC-1918
address on a dotless internal name — refusing to configure mail there would be a
regression, not a hardening.

Client-facing refusals are a single uniform message
(`smtp host/port is not an allowed mail relay`) and never echo the host, the
resolution result or which check fired. That distinction is the oracle a scanner
wants; the detail goes to the server log, where the operator configuring mail
can read it.

---

## AI ask cost budget (`saiku.ai.budget.*`)

The AI ask endpoints used to be capped by call frequency only: 30 requests per
minute per `(user, IP)`. Frequency is not cost. A chained ask is one request and
up to `maxSteps` provider round-trips, each re-sending the whole cube schema and
the accumulated tool transcript, and it holds a Jetty request thread for the
duration. Thirty of those a minute is a five-figure month and a thread-pool
stall, and no counter ever reached a ceiling because nothing was counting
tokens.

Three independent ceilings now sit on top of the per-minute limiter:

| Ceiling | Default | Property | Disable with |
|---|---|---|---|
| Provider calls per principal per UTC day | 500 | `saiku.ai.budget.maxCallsPerUserPerDay` | `0` |
| Tokens per principal per UTC day | 2,000,000 | `saiku.ai.budget.maxTokensPerUserPerDay` | `0` |
| Tokens per instance per UTC day | 20,000,000 | `saiku.ai.budget.maxTokensPerInstancePerDay` | `0` |
| Concurrent chained asks | 4 | `saiku.ai.budget.maxConcurrentChains` | `0` |

Notes on the semantics, because they matter when you tune them:

- **Tokens come from the provider's own `usage` field** on each turn, not from
  an estimate. A turn where the provider reports no usage is charged a nominal
  amount rather than nothing, so a provider that stops returning `usage` cannot
  make the surface free while still billing you.
- **Chains are counted per step, not per request.** The HTTP request is one
  unit; the extra provider round-trips are charged as extra calls, because
  counting only the request makes the most expensive operation on the surface
  look like the cheapest.
- **The gate is pre-flight, the charge is post-hoc.** A turn that starts just
  under a ceiling can overshoot it by that one turn's cost. That is the right
  trade against refusing legitimate work whose price isn't knowable in advance.
- **The day window resets at UTC midnight**, not 24h after first use. A rolling
  window lets a caller shift spend into a quieter hour and never hit the ceiling
  at the same wall-clock moment; a calendar day is also the number an operator
  can reason about ("yesterday cost this much").
- **Both rate limiting and the budget key on the authenticated principal**,
  never on `(user, IP)`. Including the IP meant a caller who changed address —
  a new NAT egress, a VPN, a per-request source NAT — got a brand-new bucket
  and could run the per-minute allowance several times over. Unauthenticated
  traffic still buckets by IP, which is the only identity it has.
- **A caller whose identity can't be derived is allowed through** (fail-open on
  a wiring gap, matching the existing rate limiter) but still consumes the
  shared instance token budget, so "unattributable" never means "free".
- **Known gap: `/ai/ask/dashboard` is charged on the call ceiling only.** It
  is a single provider turn, but `DashboardSpec` does not carry the provider's
  reported usage, so no tokens are recorded for it. The per-day call ceiling
  still bounds it; only the token ceilings under-count that one endpoint.
- **Multi-node deployments get per-node budgets.** The counters are in-memory,
  single-node and lazy, like the rate limiter they sit on. That is the right
  trade for a guardrail whose job is to bound the worst case, but it means N
  nodes allow roughly N× the ceiling. Put a shared limiter in front of the
  cluster if you need a hard global bound.

### The concurrency cap

A chain holds its request thread until the chain deadline and re-sends the schema
on every step, so unbounded concurrency is a thread-pool stall and a spend
multiplier at the same time. Beyond `saiku.ai.budget.maxConcurrentChains`
in-flight chains, `/ai/ask/chain/stream` returns `429` immediately rather than
queueing — queueing is what causes the stall. The permit is released on every
exit path, including a mid-chain client disconnect.

### Reading the refusals

`429` with a `degraded` AskResponse. The per-user refusals say which of the two
user ceilings was hit, because the user can act on it. The instance-wide refusal
does **not** — telling one tenant that other tenants are spending is nobody's
useful information, and "try later" is the only actionable answer either way.
No refusal carries a token count, so the API can't be used to read the ceiling
back out.
