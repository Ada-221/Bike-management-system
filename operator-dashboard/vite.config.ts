import { defineConfig, loadEnv } from "vite";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "FLEET_");
  const proxy = {
    "/api/v1": {
      target: env.FLEET_API_TARGET || "http://127.0.0.1:8080",
      changeOrigin: true,
    },
  };
  return { server: { proxy }, preview: { proxy } };
});
