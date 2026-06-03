import adapter from "@sveltejs/adapter-static";
import { vitePreprocess } from "@sveltejs/vite-plugin-svelte";

const basePath = process.env.SAIKU_BASE_PATH ?? "";

/** @type {import('@sveltejs/kit').Config} */
const config = {
  preprocess: vitePreprocess(),
  kit: {
    adapter: adapter({
      pages: "dist",
      assets: "dist",
      fallback: "index.html",
      precompress: false,
      strict: true,
    }),
    prerender: {
      // The branding overlay is served at runtime from <saiku.home>/branding/
      // and is expected to 404 at build time; don't fail the build for it.
      // Match with/without the SAIKU_BASE_PATH=/ui prefix so both the
      // Maven-driven build and a bare `npm run build` succeed.
      handleHttpError: ({ path, referrer, message }) => {
        if (path.includes("/branding/")) return;
        throw new Error(`${message} (linked from ${referrer})`);
      },
    },
    paths: {
      base: basePath,
      relative: true,
    },
    alias: {
      $lib: "src/lib",
    },
  },
};

export default config;
