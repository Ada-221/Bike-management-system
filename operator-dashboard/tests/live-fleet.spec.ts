import { expect, test } from "@playwright/test";
import { spawnSync } from "node:child_process";

test("real PostgreSQL fleet API, Docker proxy and browser UI work together", async ({
  page,
  request,
}) => {
  test.setTimeout(120000);
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "No vehicles yet" }),
  ).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Fleet indicators" }).locator("strong"),
  ).toHaveText(["0", "0", "0", "0"]);
  const prefix = `W3-${Date.now()}`;
  let normalId = "";
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
    if (batteryPercent === 92) normalId = vehicle.id;
  }
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(page.getByRole("rowheader")).toHaveCount(2);
  await expect(
    page.getByRole("region", { name: "Fleet indicators" }).locator("strong"),
  ).toHaveText(["2", "1", "0", "1"]);
  await expect(
    page.getByRole("row").filter({ hasText: `${prefix}-LOW` }),
  ).toContainText("Needs maintenance");
  await expect(
    page.getByRole("row").filter({ hasText: `${prefix}-BIKE` }),
  ).toContainText("92%");
  await expect(
    page.getByRole("row").filter({ hasText: `${prefix}-BIKE` }),
  ).not.toContainText("name unavailable");
  await page.getByRole("button", { name: "Fleet", exact: true }).click();
  await expect(page.getByRole("rowheader")).toHaveCount(2);
  await page.getByLabel("Vehicle status").selectOption("AVAILABLE");
  await page.getByLabel("Maximum battery (%)").fill("50");
  await page
    .getByRole("button", { name: "Apply filters", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "No matching vehicles" }),
  ).toBeVisible();
  await page.getByLabel("Vehicle status").selectOption("MAINTENANCE_REQUIRED");
  await page
    .getByRole("button", { name: "Apply filters", exact: true })
    .click();
  await expect(page.getByRole("rowheader")).toHaveCount(1);
  await page
    .getByRole("button", { name: `View ${prefix}-LOW details`, exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText("Low battery");
  await expect(page.getByRole("dialog")).toContainText("12%");
  await page
    .getByRole("button", { name: "Close details", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Clear filters", exact: true })
    .click();
  await expect(page.getByRole("rowheader")).toHaveCount(2);

  // Use the team's actual Java simulator and Kafka, not a fake HTTP update.
  const composeArgs: string[] = JSON.parse(
    process.env.FLEET_SMOKE_COMPOSE_ARGS ?? "[]",
  );
  const projectIndex = composeArgs.indexOf("--project-name");
  expect(projectIndex).toBeGreaterThanOrEqual(0);
  expect(composeArgs[projectIndex + 1]).toMatch(/^frontend-week3-/);
  const simulation = spawnSync(
    process.platform === "win32" ? "docker.exe" : "docker",
    [
      ...composeArgs,
      "--profile",
      "telemetry",
      "run",
      "--rm",
      "--no-deps",
      "-e",
      "TELEMETRY_SIMULATOR_ENABLED=true",
      "-e",
      `TELEMETRY_SIMULATOR_VEHICLE_ID=${normalId}`,
      "-e",
      "SPRING_MAIN_WEB_APPLICATION_TYPE=none",
      "telemetry",
    ],
    { encoding: "utf8", timeout: 90000, windowsHide: true },
  );
  expect(simulation.status, simulation.stderr).toBe(0);
  await expect
    .poll(
      async () =>
        (await (await request.get(`/api/v1/vehicles/${normalId}`)).json())
          .batteryPercent,
      { timeout: 20000 },
    )
    .toBe(74);
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(
    page.getByRole("row").filter({ hasText: `${prefix}-BIKE` }),
  ).toContainText("74%");
  await page
    .getByRole("button", { name: `View ${prefix}-BIKE details`, exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText("48.2104");
  await expect(
    page
      .getByRole("dialog")
      .getByText("Location measured", { exact: true })
      .locator(".."),
  ).not.toContainText("Not reported");
  await page
    .getByRole("button", { name: "Close details", exact: true })
    .click();

  // A real mid-trip fault must not make active-rental counts depend on RENTED status.
  const rental = await request.post("/api/v1/rentals", {
    headers: { "Idempotency-Key": `week3-${Date.now()}` },
    data: { vehicleId: normalId, riderId: "frontend-smoke-rider" },
  });
  expect(rental.status()).toBe(201);
  const fault = await request.post(`/api/v1/vehicles/${normalId}/maintenance`, {
    data: {
      reason: "FAULT",
      faultCode: "BRAKE_TEST",
      description: "Frontend test fault during an active trip.",
    },
  });
  expect(fault.status()).toBe(201);
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await page.getByRole("button", { name: "Overview", exact: true }).click();
  await expect(
    page.getByRole("region", { name: "Fleet indicators" }).locator("strong"),
  ).toHaveText(["2", "0", "1", "2"]);
  await page
    .getByRole("button", { name: `View ${prefix}-BIKE details`, exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText("BRAKE_TEST");
  await expect(page.getByRole("dialog")).toContainText("Needs maintenance");
  await expect(page.getByRole("alert")).toHaveCount(0);
  await page.screenshot({
    path: test.info().outputPath("week-3-live-fleet.png"),
    fullPage: true,
  });
  const summary = await request.get("/api/v1/fleet/status");
  expect(summary.status()).toBe(200);
  expect((await summary.json()).totalVehicles).toBe(2);
});
