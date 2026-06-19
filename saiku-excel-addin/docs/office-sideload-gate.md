# Office Sideload Compatibility Gate

Manual checklist for sideloading and validating the Saiku Excel add-in across Excel for Mac and Excel for Windows before any release.

Work through every item in order. Record the Excel build number and the result (pass / fail / N/A) for each item. Attach the completed checklist to the release PR.

---

## 1. Build

### Development server (HTTPS required)

Office add-ins must be served over HTTPS — Office's webview will block HTTP sources.

```bash
# Install self-signed localhost certs (one-time):
npx office-addin-dev-certs install

# Start the dev server (vite is configured to use the certs):
npm run dev
```

Alternatively, configure Vite to use HTTPS directly in `vite.config.ts`:

```ts
import { readFileSync } from "fs";
import { homedir } from "os";
import { join } from "path";

// Example manual HTTPS config (use office-addin-dev-certs path):
server: {
  https: {
    key:  readFileSync(join(homedir(), ".office-addin-dev-certs/localhost.key")),
    cert: readFileSync(join(homedir(), ".office-addin-dev-certs/localhost.crt")),
  },
  port: 5174,
}
```

Verify the dev server is reachable at `https://localhost:5174/addin/index.html` without a certificate error.

### Production build

```bash
npm run build
```

The built assets land in `dist/`. The Saiku launcher serves them at `/addin/` — confirm the launcher is running and `https://<saiku-host>/addin/index.html` loads without errors.

Update `manifest.xml` `<SourceLocation>` to the production URL before sideloading in production:

```xml
<SourceLocation DefaultValue="https://<saiku-host>/addin/index.html"/>
```

---

## 2. Sideload manifest.xml

### Excel for Windows

1. Open Excel.
2. Go to **Insert** → **My Add-ins** → **Upload My Add-in** (link at the bottom of the dialog).
3. Browse to `manifest.xml` and click **Open**.
4. The **Saiku for Excel** task pane should appear in the ribbon under **Home** → **Show Task Pane**.

> If the Upload link is missing, the IT policy may block sideloading. Enable it via
> `File → Options → Trust Center → Trust Center Settings → Trusted Add-in Catalogs`
> or via Group Policy (`AllowSideloading`).

### Excel for Mac

**Option A — Insert dialog (Excel 16.38+)**

1. Open Excel.
2. Go to **Insert** → **My Add-ins** → click the **gear icon** → **Upload My Add-in**.
3. Browse to `manifest.xml` → **Open**.

**Option B — Drop manifest into the WEF folder (all versions)**

```bash
cp manifest.xml \
  ~/Library/Containers/com.microsoft.Excel/Data/Documents/wef/
```

Then restart Excel. The add-in will appear under **Insert → My Add-ins**.

> The `wef` folder is created automatically once you have sideloaded at least one
> add-in through Excel. Create it manually if it does not exist:
> `mkdir -p ~/Library/Containers/com.microsoft.Excel/Data/Documents/wef`

---

## 3. Verification Checklist

Complete this table for **each platform** tested.

| # | Check | Mac result | Win result | Notes |
|---|-------|-----------|-----------|-------|
| 1 | **Login works inside the task-pane webview** | | | See auth risk note below |
| 2 | **Build a query** — cube selector, dimension/measure pickers load | | | |
| 3 | **Insert** writes the result Table to the active sheet | | | |
| 4 | **PivotTable** builds from the Table data (Win) / fallback triggers (Mac) | | | ExcelApi 1.8 requirement — see note |
| 5 | **Refresh** updates the Table after a data change in Saiku | | | |
| 6 | **Workbook query list persists** across close and reopen | | | Settings persist only after the workbook is saved — see note |
| 7 | **WorkOS SSO dialog** opens and redirects correctly | | | When that increment lands; leave N/A until then |

### Auth in the Office iframe — key cross-platform risk

The task pane runs in a restricted webview (WKWebView on Mac, WebView2/IE mode on Windows). Session cookie behaviour differs from a normal browser tab:

- `credentials: "include"` in `fetch` calls is required and is already set in `src/lib/http.ts`.
- `XSRF-TOKEN` must be read from the cookie jar and forwarded as `X-XSRF-TOKEN` on all non-GET requests — this is handled by `readCookie()` in `http.ts`.
- On **Mac (WKWebView)** third-party cookies may be blocked. If the add-in is served from a different origin than the Saiku launcher, the session cookie will not be sent automatically. Mitigation: serve the add-in from the same origin (the launcher's `/addin/` path) rather than a separate `localhost:5174` origin in production.
- On **Windows (WebView2)** cookie isolation is similar. Test with `credentials: "include"` confirmed working; verify with both the dev server and production origins.
- Confirm: after login, the task pane shows the username and the cube list loads without a 401 or 403.

### PivotTable — ExcelApi 1.8 requirement (item 4)

`PivotTable.add()` requires the **ExcelApi 1.8** requirement set, which maps to:

- Excel for Windows: Office 2019 or Microsoft 365 (any current build)
- Excel for Mac: **version 16.13 or later** (released January 2019)

On Mac builds older than 16.13 (or Excel 2016 for Mac), the PivotTable API is unavailable and the add-in must fall back to rendering a plain Table with a visible "PivotTable unavailable on this Excel version" notice. Verify:

- On a supported Mac build: PivotTable is created automatically after Insert.
- On an unsupported build (test by temporarily lowering the requirement set check): the fallback notice appears and the Table is still written correctly.

### Workbook query list persistence (item 6)

Query metadata is persisted via `Office.context.document.settings.set()` and `settings.saveAsync()`. Important caveats:

- Settings are scoped to the document (workbook). They are not saved until the user **saves the workbook** (Ctrl+S / Cmd+S).
- If Excel crashes before a save, unsaved query history is lost.
- Verify persistence by: building a query → Insert → **Save the workbook** → close Excel → reopen the same workbook → confirm the query appears in the query history panel.

---

## 4. Build Number Log

Record the exact Excel build used for each test run.

| Date | Platform | Excel build / version | Result summary |
|------|----------|-----------------------|----------------|
|      | Mac      |                       |                |
|      | Windows  |                       |                |

To find the build number:
- **Mac**: Excel menu → **About Microsoft Excel** → version string (e.g. `16.95 (25051...)`).
- **Windows**: **File** → **Account** → **About Excel** → build number (e.g. `2504 (Build 18730.20156)`).

---

## 5. Failure Reporting

If any checklist item fails:

1. Note the exact build number and platform.
2. Capture the browser console from the task pane:
   - **Mac**: Safari → **Develop** → select the webview process → Console.
   - **Windows**: F12 in the webview (if WebView2 DevTools are enabled) or attach Edge DevTools to the WebView2 process.
3. File an issue referencing this checklist item number, the platform, and the console output.
4. Do not mark the release as gated-pass until all CRITICAL items (1–6) are resolved.

---

## Branding & UX checks (added with the UX/branding polish)

Verify after sideloading (record Excel build numbers):

- [ ] **Branded header** renders on every screen (Login, Home, wizard) with the real Saiku logo (`/addin/assets/logo.svg`) + "for Excel".
- [ ] **Readable under Excel dark mode** — the pane stays on Saiku's light surface (white bg, slate text); no faint/invisible text. Toggle Excel to dark mode and re-check.
- [ ] **Manifest icons load** — no broken-image / missing-icon warnings; `icon-32.png` / `icon-80.png` resolve.
- [ ] **Dimensions step** shows the grouped tree (dimension → level captions), a working **search** box, **collapse/expand** per dimension, indigo checkboxes, and **never** an `(All)` level.
- [ ] **Captions, not keys** — Measures, Dimensions, Filters chips, and the Review summary show human-readable captions (e.g. "Product Family", "Year"), not raw `dimension › hierarchy › level` keys or OData property names.
- [ ] **Measures search** filters the measure list by caption when typing.
- [ ] **Indigo branding** — primary buttons (Insert / Next / ＋ New query) and the active stepper step use Saiku indigo (`#4f46e5`), not Fluent blue; completed steps show a green ✓.
- [ ] **End-to-end still works** — build a query (Sales → Unit Sales → Product Family) and **Insert** writes the table; refresh works. (Polish must not have regressed the data path.)

---

## Multi-server checks (cross-origin)

Requires a CORS-enabled Saiku (`-Dsaiku.cors.allowedOrigins=<add-in origin>`) and, for SSO, a Saiku with `saiku.oidc.*` set + a real WorkOS app whose redirect URI is `<add-in origin>/addin/auth-callback.html`.

- [ ] **First run** with an empty registry shows the **Servers** screen ("Add a server").
- [ ] **Add a Basic server** by URL → the add-in probes `/auth-capabilities`, shows a username/password form → after sign-in the cube list loads.
- [ ] **Add a WorkOS/OIDC server** → "Sign in with SSO" opens the Office dialog (WorkOS hosted login) → returns a bearer token → cube list loads.
- [ ] **Switch active server** (header switcher) → the wizard's cube list re-scopes to the new server.
- [ ] **Build + Insert** a query against each server; **Home groups saved queries by server**; **Refresh** re-runs against the correct server with its stored credential.
- [ ] **Secret split:** save the workbook, inspect its stored settings (or reopen as a different OS user) → **server URLs/labels + query defs are present, but NO passwords/tokens** (those are in the per-user roaming store). Reopening prompts to sign in to each server.
- [ ] **CORS error** — point at a Saiku that hasn't allow-listed the add-in origin → the add-in shows "Can't reach this server, or it hasn't allowed the add-in (CORS)" (distinct from an auth failure).
- [ ] **Token expiry** — an expired OIDC token re-prompts (or silently refreshes) for that server only; other servers unaffected.
