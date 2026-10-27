import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import type { Plugin } from "vite";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

const pkg = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8")) as { version: string };

/**
 * Emits the service worker at the site root with this build's id, so every release gets a new worker
 * (and a fresh cache) while the source stays a plain script the browser loads without bundling.
 */
function serviceWorker(): Plugin {
  const source = fileURLToPath(new URL("./src/sw.js", import.meta.url));
  return {
    name: "lagoon-service-worker",
    apply: "build",
    generateBundle() {
      const build = `${pkg.version}-${Date.now().toString(36)}`;
      this.emitFile({ type: "asset", fileName: "sw.js", source: readFileSync(source, "utf8").replaceAll("__BUILD__", build) });
    },
  };
}

export default defineConfig({
  plugins: [react(), tailwindcss(), serviceWorker()],
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  build: { target: "es2022", sourcemap: false, chunkSizeWarningLimit: 900 },
  server: {
    port: 5173,
    proxy: { "/api": { target: process.env.LAGOON_HUB ?? "http://127.0.0.1:25246", changeOrigin: false } },
  },
  test: { environment: "node", include: ["src/**/*.test.ts"] },
});
