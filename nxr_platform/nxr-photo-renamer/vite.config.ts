import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const OCR_MODEL_ID = "virtual:nxr-ocr-eng-model";
const RESOLVED_OCR_MODEL_ID = `\0${OCR_MODEL_ID}`;

export default defineConfig({
  plugins: [
    vue(),
    {
      name: "nxr-inline-ocr-model",
      resolveId(id) {
        return id === OCR_MODEL_ID ? RESOLVED_OCR_MODEL_ID : undefined;
      },
      load(id) {
        if (id !== RESOLVED_OCR_MODEL_ID) return undefined;
        const model = readFileSync(resolve(
          process.cwd(),
          "node_modules/@tesseract.js-data/eng/4.0.0_best_int/eng.traineddata.gz",
        )).toString("base64");
        return `export default ${JSON.stringify(model)}`;
      },
      transform(code, id) {
        if (!id.includes("/node_modules/tesseract.js/")) return undefined;
        return code.replaceAll("https://cdn.jsdelivr.net/", "./ocr-local-only/");
      },
    },
  ],
  base: "./",
  // jsQR is imported by a worker. Prebundle it before the first scan so Vite
  // does not reload the page and clear an imported folder during development.
  optimizeDeps: { include: ["jsqr"] },
  build: {
    // Bundle only our pinned codec binaries into the worker. It performs no
    // fetch, keeping the deployed page's connect-src 'none' policy intact.
    assetsInlineLimit: (filePath) =>
      /@jsquash\/webp\/codec\/(?:enc|dec)\/webp_(?:enc|dec)\.wasm$/.test(filePath)
        ? true : undefined,
  },
  server: { host: "127.0.0.1" },
});
