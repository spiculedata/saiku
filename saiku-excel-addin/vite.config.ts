/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";
import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

// Office requires the task pane over HTTPS. Use the trusted localhost cert from
// `office-addin-dev-certs install` when present; otherwise fall back to plain HTTP
// (fine for unit tests / non-Office browsing).
const certDir = join(homedir(), ".office-addin-dev-certs");
const certPath = join(certDir, "localhost.crt");
const keyPath = join(certDir, "localhost.key");
const httpsOpts =
  existsSync(certPath) && existsSync(keyPath)
    ? { cert: readFileSync(certPath), key: readFileSync(keyPath) }
    : undefined;

export default defineConfig({
  plugins: [svelte()],
  base: "/addin/",
  build: { outDir: "dist", emptyOutDir: true },
  server: {
    port: 5174,
    https: httpsOpts,
    proxy: { "/rest": { target: "http://localhost:8080", changeOrigin: true } }
  },
  test: { environment: "jsdom", include: ["test/**/*.test.ts"] }
});
