# Saiku telemetry collector

Counts **monthly active Saiku instances** anonymously, using a Cloudflare Worker
+ D1. Zero servers, zero maintenance, comfortably inside the free tier.

Instances phone home once on startup and once a day. We keep **one row per
install** and count distinct installs seen in the last 30 days. Downloads/pulls
aren't counted — they lie badly for Docker.

## What is (and isn't) collected

Sent by each instance:

```json
{ "id": "<random uuid, per install>", "version": "4.6.0",
  "edition": "ce", "os": "linux", "arch": "amd64", "java": "21" }
```

Stored in D1:

| column | value |
|---|---|
| `id_hash` | `sha256(id)` — the raw id is never stored |
| `version`, `edition`, `os`, `arch`, `java` | coarse, sanitised strings |
| `first_seen`, `last_seen` | unix timestamps |

**Never** collected: the client IP (not read, not stored), hostnames, datasource
URLs, cube/schema names, user info, or any warehouse data. The id is a random
UUID with no link to anything identifying, and it's stored only as a hash.

The response is `{ "latest": "<version>" }` so the heartbeat doubles as an
update check — the instance can nudge the admin when a newer release exists.

## One-time setup

```bash
cd telemetry
npm install
npx wrangler login                 # opens browser; uses your Cloudflare account

npm run db:create                  # prints a database_id ...
# → paste that id into wrangler.toml  [[d1_databases]] database_id

npm run db:init                    # apply schema.sql to the remote D1
npm run deploy                     # deploy the Worker
```

That's it. The Worker is now live at
`https://saiku-telemetry.<your-subdomain>.workers.dev`.

To use `telemetry.saiku.bi` instead, uncomment the `[[routes]]` block in
`wrangler.toml` (requires the `saiku.bi` zone on the same Cloudflare account) and
redeploy.

Set `LATEST_VERSION` in `wrangler.toml` on each release (one line) so the update
check is accurate.

## Reading the number

```bash
npm run stats            # -> active_instances
npm run stats:versions   # -> count per version
```

Or hit the cached public endpoint (also feeds a badge or a "count in Saiku"
dashboard):

```
GET https://<worker-url>/v1/stats
{ "activeInstances": 128, "windowDays": 30,
  "byVersion": [ { "version": "4.6.0", "n": 96 }, ... ], "generatedAt": 1720800000 }
```

## Endpoint contract (for the Saiku client)

`POST /v1/check`  ·  `content-type: application/json`

- Request body: the JSON shown above (`id` + `version` required; rest optional).
- Response: `{ "latest": "<version>" }`, HTTP 200. On any error the client should
  ignore the failure — telemetry must never affect Saiku's behaviour.

## Client side (each Saiku instance)

The launcher's `TelemetryService` fires the heartbeat on startup and once a day,
storing a random install id in `saiku-home/instance-id`. It's **opt-out** — on by
default, disabled by any of:

| control | effect |
|---|---|
| `SAIKU_TELEMETRY=off` (env) | disable (also `false` / `0` / `no` / `disabled`) |
| `DO_NOT_TRACK=1` (env) | disable (honours the [Console DNT](https://consoledonottrack.com/) convention) |
| `-Dsaiku.telemetry.enabled=false` | disable |
| `SAIKU_TELEMETRY_ENDPOINT=<url>` / `-Dsaiku.telemetry.endpoint=<url>` | point at a different collector |

Default endpoint: `https://telemetry.saiku.bi/v1/check`. A one-line startup notice
tells the operator it's on and how to turn it off.

## Free-tier headroom

One upsert per install per day; one tiny row per install. Cloudflare free tier:
Workers 100k req/day, D1 100k writes/day + 5M reads/day + 5GB. That covers well
into tens of thousands of active installs before anything needs revisiting.
