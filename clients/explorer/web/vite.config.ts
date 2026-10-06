import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  build: { outDir: "../tap_explorer/static", emptyOutDir: true },
  test: { environment: "jsdom" },
});
