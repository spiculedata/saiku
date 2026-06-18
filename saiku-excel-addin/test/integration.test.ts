// @vitest-environment node
/**
 * Integration tests for the Saiku AI Query API.
 *
 * These tests run against a live Saiku launcher at http://localhost:8080.
 * Gate: only runs when SAIKU_IT=1 is set in the environment.
 *
 * Run:   SAIKU_IT=1 npm test -- integration
 * Skip:  npm test  (integration suite is skipped; all 16 unit tests pass)
 *
 * Cookie / CSRF handling
 * ----------------------
 * Node's built-in fetch (undici) does NOT manage cookies automatically and
 * `response.headers.get("set-cookie")` collapses multiple Set-Cookie values
 * into a single comma-joined string (lossy for values that contain commas).
 * We use `response.headers.getSetCookie()` (supported since Node 18 / undici)
 * which returns each Set-Cookie header as a separate array element, making it
 * safe to parse JSESSIONID and XSRF-TOKEN independently.
 *
 * Auth flow:
 *  1. POST /rest/saiku/session  (form-encoded creds)  → sets JSESSIONID + XSRF-TOKEN
 *  2. GET  /rest/saiku/session  (with cookies)         → confirms session is valid
 *  3. All subsequent requests carry:
 *       Cookie: JSESSIONID=<id>; XSRF-TOKEN=<tok>
 *       X-XSRF-TOKEN: <tok>    (required for non-GET requests by Spring Security)
 */

import { describe, it, expect } from "vitest";

const RUN = process.env.SAIKU_IT === "1";
const base = "http://localhost:8080";

// ---------------------------------------------------------------------------
// Cookie helpers
// ---------------------------------------------------------------------------

/**
 * Parse a single Set-Cookie string value and return the cookie name=value pair.
 * Example input:  "JSESSIONID=node0abc123; Path=/; HttpOnly; SameSite=Lax"
 * Example output: { name: "JSESSIONID", value: "node0abc123" }
 */
function parseCookiePair(setCookieHeader: string): { name: string; value: string } | null {
  const firstPart = setCookieHeader.split(";")[0].trim();
  const eqIdx = firstPart.indexOf("=");
  if (eqIdx === -1) return null;
  return {
    name: firstPart.slice(0, eqIdx).trim(),
    value: firstPart.slice(eqIdx + 1).trim(),
  };
}

/**
 * Extract named cookie values from an array of raw Set-Cookie header strings.
 * Uses getSetCookie() output — each element is one independent Set-Cookie value.
 */
function extractCookies(
  setCookieHeaders: string[],
  ...names: string[]
): Record<string, string> {
  const result: Record<string, string> = {};
  for (const header of setCookieHeaders) {
    const pair = parseCookiePair(header);
    if (pair && names.includes(pair.name)) {
      result[pair.name] = pair.value;
    }
  }
  return result;
}

// ---------------------------------------------------------------------------
// Login helper
// ---------------------------------------------------------------------------

interface AuthCredentials {
  /** Value for the Cookie request header: "JSESSIONID=...; XSRF-TOKEN=..." */
  cookieHeader: string;
  /** Raw XSRF token value for the X-XSRF-TOKEN request header */
  xsrf: string;
}

/**
 * Perform the two-step Saiku login:
 *  1. POST credentials → extract JSESSIONID + XSRF-TOKEN from Set-Cookie headers
 *  2. GET session      → confirm session; may refresh XSRF-TOKEN (use latest)
 *
 * Returns a { cookieHeader, xsrf } object ready to attach to subsequent requests.
 */
async function login(username = "admin", password = "admin"): Promise<AuthCredentials> {
  // Step 1: POST credentials
  const loginRes = await fetch(`${base}/rest/saiku/session`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`,
  });

  if (!loginRes.ok) {
    throw new Error(`Login failed: HTTP ${loginRes.status}`);
  }

  // Use getSetCookie() — safe multi-cookie extraction (undici / Node 18+)
  const loginCookies = extractCookies(
    loginRes.headers.getSetCookie(),
    "JSESSIONID",
    "XSRF-TOKEN"
  );

  if (!loginCookies["JSESSIONID"]) {
    throw new Error("Login did not return a JSESSIONID cookie");
  }

  // Build cookie header after step 1 (XSRF-TOKEN may not be set yet on some configs)
  let jsessionid = loginCookies["JSESSIONID"];
  let xsrf = loginCookies["XSRF-TOKEN"] ?? "";

  // Step 2: GET session — confirms session is valid; may also set/refresh XSRF-TOKEN
  const sessionCookieHeader = xsrf
    ? `JSESSIONID=${jsessionid}; XSRF-TOKEN=${xsrf}`
    : `JSESSIONID=${jsessionid}`;

  const sessionRes = await fetch(`${base}/rest/saiku/session`, {
    headers: { Cookie: sessionCookieHeader },
  });

  if (!sessionRes.ok) {
    throw new Error(`Session GET failed: HTTP ${sessionRes.status}`);
  }

  // Absorb any refreshed cookies from the follow-up GET
  const sessionCookies = extractCookies(
    sessionRes.headers.getSetCookie(),
    "JSESSIONID",
    "XSRF-TOKEN"
  );

  if (sessionCookies["JSESSIONID"]) jsessionid = sessionCookies["JSESSIONID"];
  if (sessionCookies["XSRF-TOKEN"]) xsrf = sessionCookies["XSRF-TOKEN"];

  if (!xsrf) {
    throw new Error("Could not obtain XSRF-TOKEN from login or session GET");
  }

  return {
    cookieHeader: `JSESSIONID=${jsessionid}; XSRF-TOKEN=${xsrf}`,
    xsrf,
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

(RUN ? describe : describe.skip)("integration: AI Query API", () => {
  it("lists cubes (GET /api/ai/cubes)", async () => {
    const { cookieHeader } = await login();

    const res = await fetch(`${base}/rest/saiku/api/ai/cubes`, {
      headers: { Cookie: cookieHeader },
    });

    expect(res.ok, `Expected 200 from /ai/cubes, got ${res.status}`).toBe(true);

    const cubes = (await res.json()) as unknown[];
    expect(Array.isArray(cubes)).toBe(true);
    expect(cubes.length).toBeGreaterThan(0);
  });

  it("runs a query returning 3 product families (POST /api/ai/query)", async () => {
    const { cookieHeader, xsrf } = await login();

    const body = JSON.stringify({
      cube: "unknown_foodmart/FoodMart/FoodMart/Sales",
      measures: [{ name: "Unit Sales" }],
      rows: [
        {
          dimension: "Product",
          hierarchy: "Products",
          level: "Product Family",
        },
      ],
      nonEmpty: true,
    });

    const res = await fetch(`${base}/rest/saiku/api/ai/query`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Cookie: cookieHeader,
        "X-XSRF-TOKEN": xsrf,
      },
      body,
    });

    expect(res.ok, `Expected 200 from /ai/query, got ${res.status}`).toBe(true);

    const result = (await res.json()) as { status: string; data: unknown[] };
    expect(result.status).toBe("SUCCESS");
    expect(result.data).toHaveLength(3);

    // Verify the three expected product families are present
    const families = (result.data as Array<Record<string, unknown>>).map(
      (row) => row["Product Family"]
    );
    expect(families).toContain("Drink");
    expect(families).toContain("Food");
    expect(families).toContain("Non-Consumable");
  });

  // ---------------------------------------------------------------------------
  // Role-scoped filtering placeholder
  // ---------------------------------------------------------------------------
  // This test requires:
  //   1. A Mondrian role defined in the FoodMart schema (e.g. <Role name="SalesViewer">
  //      with a <SchemaGrant> that restricts access to a subset of cubes or members).
  //   2. A Saiku user (e.g. "restricted_user") configured in saiku-beans.xml /
  //      applicationContext-saiku-spring-security.xml with that Mondrian role assigned.
  //   3. The launcher restarted with the updated schema and user config.
  //
  // Once a restricted role and user are in place, replace this todo with a real test
  // that logs in as "restricted_user", calls /ai/cubes, and asserts the cube list is
  // smaller than the admin list; and calls /ai/query for a restricted dimension member
  // and asserts the result excludes the filtered members.
  it.todo("filters results for a role-restricted user (requires Mondrian role + restricted user setup)");
});
