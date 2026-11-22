import { defineConfig } from "@playwright/test";

if (!process.env.FLEET_LIVE_DASHBOARD_URL)
  throw new Error("Use npm run test:live to create the isolated test stack.");

export default defineConfig({
  testDir: "./tests",
  testMatch: "**/live-fleet.spec.ts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: "list",
  use: {
    baseURL: process.env.FLEET_LIVE_DASHBOARD_URL,
    channel: process.env.PLAYWRIGHT_CHANNEL || undefined,
    headless: true,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
});
