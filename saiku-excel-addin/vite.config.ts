/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";

export default defineConfig({
  plugins: [svelte()],
  base: "/addin/",
  build: { outDir: "dist", emptyOutDir: true },
  server: {
    port: 5174,
    proxy: { "/rest": { target: "http://localhost:8080", changeOrigin: true } }
  },
  test: { environment: "jsdom", include: ["test/**/*.test.ts"] }
});
