import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// `bun run build` writes the page into the Python package, which ships it in the wheel.
// `bun run dev` serves the page on 127.0.0.1:5173 and forwards the API to a running
// `tap-studio --port 8787 --page-origin http://127.0.0.1:5173` (TAP_STUDIO_API overrides it):
// StudioService calls (Connect, under /tap.studio.v1.StudioService) and the login route.
// The proxy keeps the browser's Host and Origin, so the server's loopback and same-origin
// checks see the dev server's origin, as they would their own.
const api = process.env.TAP_STUDIO_API ?? "http://127.0.0.1:8787";

export default defineConfig({
  plugins: [react()],
  build: { outDir: "../tap_studio/static", emptyOutDir: true },
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    proxy: {
      "/tap.studio.v1.StudioService": { target: api },
      "/login": { target: api },
    },
  },
  test: { environment: "jsdom" },
});
