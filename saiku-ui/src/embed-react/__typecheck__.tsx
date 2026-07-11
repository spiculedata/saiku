/*
 * TypeScript sanity check for the @concepttocloud/saiku-embed-react
 * public surface (saiku#1432). Not shipped, not staged into the npm
 * package — the stage script only copies index.js / index.d.ts /
 * README.md. This file exists so a CI type-check catches regressions
 * on the wrapper's typed prop set.
 *
 * Run with:
 *   npx tsc --noEmit --project saiku-ui/src/embed-react/tsconfig.json
 */
import * as React from "react";

// Consumer would normally write:
//   import { SaikuEmbed, mintEmbedToken } from "@concepttocloud/saiku-embed-react";
// Here we import from the local .d.ts + .js pair because the package
// isn't installed in the workspace.
import {
  SaikuEmbed,
  mintEmbedToken,
  type SaikuEmbedProps,
  type SaikuEmbedKind,
  type SaikuEmbedRender,
  type MintEmbedTokenOptions,
  type MintEmbedTokenResult,
} from "./index.js";

// ---- SaikuEmbedProps: full happy path ----
const fullProps: SaikuEmbedProps = {
  server: "https://saiku.example.com",
  path: "homes/admin/Sales.saiku",
  kind: "query",
  token: "tx-abc",
  render: "chart",
  mode: "bar",
  height: "480px",
  style: { border: "1px solid #ccc" },
  className: "my-embed",
  id: "sales-embed",
  "data-testid": "embed-sales",
};

// ---- Type narrowing: kind literal unions ----
const kinds: SaikuEmbedKind[] = ["query", "dashboard", "ai"];
const renders: SaikuEmbedRender[] = ["table", "matrix", "chart"];

// ---- Component usage in JSX ----
function ExampleTree() {
  return (
    <>
      {/* Minimal — only required prop is `path`. */}
      <SaikuEmbed path="homes/admin/Sales.saiku" />

      {/* Full — every optional prop populated. */}
      <SaikuEmbed {...fullProps} />

      {/* AI kind example. */}
      <SaikuEmbed kind="ai" path="foodmart/FoodMart/FoodMart/Sales" token="tx-ai" height="200px" />

      {/* Raw custom element — augmented JSX namespace makes this typed too. */}
      <saiku-embed server="https://saiku.example.com" path="a.saiku" render="chart" mode="line" />
    </>
  );
}

// ---- mintEmbedToken options + result ----
async function mint(): Promise<MintEmbedTokenResult> {
  const opts: MintEmbedTokenOptions = {
    server: "https://saiku.example.com",
    authorization: "Basic YWRtaW46YWRtaW4=",
    resourceKind: "query",
    resourcePath: "homes/admin/Trend.saiku",
    ttlHours: 24,
    label: "unit test",
  };
  const result = await mintEmbedToken(opts);
  return result;
}

// Silence "declared but unused" warnings.
export { fullProps, kinds, renders, ExampleTree, mint };
