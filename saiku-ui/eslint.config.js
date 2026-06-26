import prettier from "eslint-config-prettier";
import path from "node:path";
import { includeIgnoreFile } from "@eslint/compat";
import js from "@eslint/js";
import svelte from "eslint-plugin-svelte";
import { defineConfig } from "eslint/config";
import globals from "globals";
import ts from "typescript-eslint";
import svelteConfig from "./svelte.config.js";

const gitignorePath = path.resolve(import.meta.dirname, ".gitignore");

export default defineConfig(
  includeIgnoreFile(gitignorePath),
  js.configs.recommended,
  ts.configs.recommended,
  svelte.configs.recommended,
  prettier,
  svelte.configs.prettier,
  {
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    rules: {
      // typescript-eslint strongly recommends disabling no-undef on TS projects;
      // we let tsc handle undefined-symbol errors.
      "no-undef": "off",
      // Codebase pre-dates eslint adoption — these rules surface stylistic
      // pre-existing patterns that aren't load-bearing for correctness.
      // Each is justified individually rather than blanket-disabled so the
      // intent stays auditable.
      //
      // svelte/no-unused-svelte-ignore: 99 instances. The codebase intentionally
      // leaves `<!-- svelte-ignore a11y_*-->` comments above blocks the
      // designer accepts as a11y trade-offs; the warning surfaces them every
      // build but they're load-bearing developer-intent comments.
      "svelte/no-unused-svelte-ignore": "off",
      // svelte/require-each-key: 77 instances. Saiku-ui's lists are mostly
      // append-only or fully-re-rendered on filter change; the missing
      // {(key)} block is a deliberate "we don't track row identity" signal
      // (filter dropdowns, derived axes, etc.). Adding keys everywhere
      // would force synthetic-id generation with no behavioural win.
      "svelte/require-each-key": "off",
      // svelte/prefer-svelte-reactivity: 40 instances. Saiku-ui uses the
      // immutable-update pattern throughout — every Map/Set/Date mutation
      // is followed by reassignment of the host $state field. SvelteSet /
      // SvelteMap / SvelteDate would add cost (extra signal bookkeeping
      // per element) for zero behaviour benefit because reactivity
      // invalidation already fires on the reassignment, not on .add().
      // Same posture saiku-cloud-dashboard landed in PR #953.
      "svelte/prefer-svelte-reactivity": "off",
      // svelte/no-navigation-without-resolve: 17 instances. Saiku-ui's
      // routes are mostly query-string-laden links to the share viewer
      // and embed-token routes that resolve() can't validate. These are
      // operator-controlled literal strings (or values from typed in-file
      // allow-lists); the rule's typo-detection win doesn't apply here.
      "svelte/no-navigation-without-resolve": "off",
      // svelte/prefer-writable-derived: similar — DimensionList's
      // $state + $effect pattern is needed for SvelteFlow-style binding
      // (the parent writes back into the value during drag).
      "svelte/prefer-writable-derived": "off",
      // @typescript-eslint/no-unused-vars: 18 instances are mostly
      // function parameters destructured for shape that aren't read in
      // the body (callback signatures). Stricter than the existing
      // tsconfig "noUnusedParameters" we already enforce; defer to tsc.
      "@typescript-eslint/no-unused-vars": ["warn",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
      // @typescript-eslint/no-explicit-any: 6 instances — all legitimate
      // (lucide-svelte's component-type generic + Svelte's a11y dataset
      // typing). We use `unknown` where we can; some seams genuinely
      // need `any`.
      "@typescript-eslint/no-explicit-any": "off",
      // preserve-caught-error: 6 instances — the codebase intentionally
      // rewraps caught errors into Saiku-shaped messages without
      // propagating the original cause. Useful in principle; not load-
      // bearing for the current API surface. Downgrade to warn so it
      // surfaces in dev without gating CI.
      "preserve-caught-error": "warn",
      // no-useless-assignment: 2 instances — both are deliberate
      // initialisations that follow a defensive default-then-overwrite
      // pattern (one in api.ts JSON parsing, one in DashboardGrid's
      // ref-mounting). Stylistic preference; not load-bearing.
      "no-useless-assignment": "off",
      // no-irregular-whitespace: 3 instances in date-filter MDX test —
      // Unicode chars are intentional in the regex literals.
      "no-irregular-whitespace": "off",
      // @typescript-eslint/no-unused-expressions: 1 instance in +layout
      // (\ is a deliberate effect-dependency keepalive).
      "@typescript-eslint/no-unused-expressions": "off",
      // svelte/no-unused-props: 1 instance — load-bearing for back-compat
      // of an inline prop signature.
      "svelte/no-unused-props": "off",
    },
  },
  {
    files: ["**/*.svelte", "**/*.svelte.ts", "**/*.svelte.js"],
    languageOptions: {
      parserOptions: {
        projectService: true,
        extraFileExtensions: [".svelte"],
        parser: ts.parser,
        svelteConfig,
      },
    },
  },
  {
    // Design-system token enforcement — saiku-cloud parity (saiku-cloud#TODO).
    // Raw Tailwind colour utilities for status tones (emerald / red / amber /
    // rose / orange) don't compose with the @theme bridge that provides
    // light + dark colour-mode swaps via the existing 169 saiku-ui tokens.
    // Use the semantic tone tokens (text-success / text-danger / text-warning
    // / text-info / text-fg / etc.) so both modes always work.
    //
    // This rule catches the string-literal cases (template strings, computed
    // class names in script blocks). The Svelte-template class-attribute case
    // is covered by `scripts/check-tokens.mjs`, wired into `pnpm lint` alongside
    // this rule (the AST shapes for attribute values differ enough between
    // eslint-plugin-svelte versions that a single rule isn't reliable).
    files: ["**/*.ts", "**/*.svelte", "**/*.svelte.ts"],
    ignores: ["src/lib/design-system/**", "scripts/css-to-tw.mjs"],
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          selector:
            "Literal[value=/\\b(?:text|bg|border|hover:bg|hover:text)-(?:emerald|red|amber|rose|orange)-[0-9]+\\b/]",
          message:
            "Use design-system tone tokens (text-success / text-danger / text-warning / text-info) instead of raw Tailwind colors. See src/lib/design-system/README.md.",
        },
      ],
    },
  },
);
