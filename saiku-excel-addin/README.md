# saiku-excel-addin

Office.js task-pane add-in for Excel, built with Svelte 5 + Vite + TypeScript.

## Development

### Prerequisites

- Node 22 / npm 10
- Office requires **HTTPS** for sideloading. For local dev, generate trusted dev certificates:

```bash
npx office-addin-dev-certs install
```

Then add the generated cert paths to `vite.config.ts` under `server.https`.

### Start dev server

```bash
npm install
npm run dev
# Serves at https://localhost:5174/addin/
```

Sideload `manifest.xml` into Excel via **Insert → Get Add-ins → Upload My Add-in** (desktop) or the M365 admin centre.

### Type-check

```bash
npm run check
```

### Test

```bash
npm test
```

### Build

```bash
npm run build
# Output: dist/
```

## Production hosting

The built `dist/` is served by the Saiku webapp at `/addin/` (same origin). This means:

- No CORS issues — REST calls to `/rest/**` are same-origin.
- The `manifest.xml` `<SourceLocation>` should point to `https://<saiku-host>/addin/index.html`.

## WAR integration (TODO — not applied in this task)

To embed the add-in inside `saiku-webapp`:

1. **Build step in `saiku-webapp/pom.xml`:** add a `prepare-package` execution using the `exec-maven-plugin` that runs `npm run build` inside `saiku-excel-addin/` and copies `saiku-excel-addin/dist/**` into `saiku-webapp/src/main/webapp/addin/`.

   ```xml
   <execution>
     <id>build-excel-addin</id>
     <phase>prepare-package</phase>
     <goals><goal>exec</goal></goals>
     <configuration>
       <executable>npm</executable>
       <arguments>
         <argument>run</argument>
         <argument>build</argument>
       </arguments>
       <workingDirectory>${project.basedir}/../saiku-excel-addin</workingDirectory>
     </configuration>
   </execution>
   ```

2. **SPA fallback filter in `saiku-webapp`:** mirror the existing `/ui/*` fallback filter so that `/addin/*` routes all return `addin/index.html`, allowing Vite's client-side router to handle navigation.

## Icon placeholders

`manifest.xml` references `/addin/assets/icon-32.png` and `/addin/assets/icon-80.png`. These are placeholder paths — real icons (32×32 and 80×80 PNG) must be added to `src/assets/` and included in the Vite build before submission to AppSource or enterprise deployment.
