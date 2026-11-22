/// <reference types="vitest" />

import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
  },
  test: {
    maxWorkers: 4,
    environment: "jsdom",
    globals: true,
    setupFiles: "./src/test/setup.ts",
  },
});
