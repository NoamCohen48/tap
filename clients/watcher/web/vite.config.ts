import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The build is served by the Python back end (tap_watcher/static), so it is committed nowhere
// and rebuilt by `bun run build`.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../tap_watcher/static",
    emptyOutDir: true,
  },
});
