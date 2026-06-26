/// <reference types="node" />
/// <reference types="vitest/config" />
import { sveltekit } from "@sveltejs/kit/vite";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";
import { fileURLToPath } from "node:url";

const API_TARGET = process.env.SAIKU_API ?? "http://localhost:8080";

// Vitest config lives under `test` via module augmentation; we suppress the
// vite.UserConfig shape check so tsc doesn't require the vitest types here.
export default defineConfig({
  plugins: [tailwindcss(), sveltekit()],
  server: {
    port: 5173,
    proxy: {
      "/rest": { target: API_TARGET, changeOrigin: true },
    },
  },
  resolve: {
    alias: {
      // Mirrored here for vitest so `$lib/...` imports resolve outside the
      // SvelteKit dev/build pipeline.
      $lib: fileURLToPath(new URL("./src/lib", import.meta.url)),
    },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
