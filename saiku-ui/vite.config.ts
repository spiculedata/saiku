/// <reference types="node" />
import { sveltekit } from "@sveltejs/kit/vite";
import { defineConfig } from "vite";

const API_TARGET = process.env.SAIKU_API ?? "http://localhost:8080";

export default defineConfig({
  plugins: [sveltekit()],
  server: {
    port: 5173,
    proxy: {
      "/rest": { target: API_TARGET, changeOrigin: true },
    },
  },
});
