import { expect, test } from "@playwright/test";

test("real PostgreSQL fleet API, Docker proxy and browser UI work together", async ({
  page,
  request,
}) => {
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "No vehicles yet" }),
  ).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Fleet indicators" }).locator("strong"),
  ).toHaveText(["0", "0", "0", "0"]);
  const prefix = `W2-${Date.now()}`;
  for (const [label, batteryPercent] of [
    [`${prefix}-BIKE`, 92],
    [`${prefix}-LOW`, 12],
  ] as const) {
    const response = await request.post("/api/v1/vehicles", {
      data: {
        label,
        type: "E_BIKE",
        latitude: 48.2102,
        longitude: 16.3598,
        batteryPercent,
      },
    });
    expect(response.status()).toBe(201);
    const vehicle = await response.json();
    expect(vehicle.id).toMatch(/^veh_/);
  }
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(page.getByRole("rowheader")).toHaveCount(2);
  await expect(
    page.getByRole("region", { name: "Fleet indicators" }).locator("strong"),
  ).toHaveText(["2", "2", "0", "0"]);
  await expect(
    page.getByRole("row").filter({ hasText: `${prefix}-LOW` }),
  ).toContainText("12%");
  await expect(
    page.getByRole("row").filter({ hasText: `${prefix}-BIKE` }),
  ).toContainText("92%");
  await expect(
    page.getByRole("row").filter({ hasText: `${prefix}-BIKE` }),
  ).not.toContainText("name unavailable");
  await page.getByRole("button", { name: "Fleet", exact: true }).click();
  await expect(page.getByRole("rowheader")).toHaveCount(2);
  await expect(page.getByRole("alert")).toHaveCount(0);
  await page.screenshot({
    path: test.info().outputPath("week-2-live-fleet.png"),
    fullPage: true,
  });
  const summary = await request.get("/api/v1/fleet/status");
  expect(summary.status()).toBe(200);
  expect((await summary.json()).totalVehicles).toBe(2);
  const stream = await request.get("/api/v1/fleet/stream");
  expect(stream.status()).toBe(200);
  expect(stream.headers()["content-type"]).toContain("text/event-stream");
  expect(await stream.text()).toMatch(/event:heartbeat\r?\nretry:15000\r?\ndata:\{\}/);
});
