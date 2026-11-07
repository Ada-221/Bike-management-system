import { expect, test } from "@playwright/test";
import { sampleVehicles, sampleZones } from "../src/data/sampleFleet";

test("sample data obeys the fleet draft identity, battery, zone, and rentability rules", () => {
  const uuid =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  expect(new Set(sampleVehicles.map((v) => v.id)).size).toBe(
    sampleVehicles.length,
  );
  for (const vehicle of sampleVehicles) {
    expect(vehicle.id).toMatch(uuid);
    expect(["E_BIKE", "E_SCOOTER"]).toContain(vehicle.type);
    expect(["AVAILABLE", "RENTED", "MAINTENANCE_REQUIRED"]).toContain(
      vehicle.status,
    );
    expect(Number.isInteger(vehicle.batteryPercent)).toBe(true);
    expect(vehicle.batteryPercent).toBeGreaterThanOrEqual(0);
    expect(vehicle.batteryPercent).toBeLessThanOrEqual(100);
    expect(Math.abs(vehicle.latitude)).toBeLessThanOrEqual(90);
    expect(Math.abs(vehicle.longitude)).toBeLessThanOrEqual(180);
    expect(sampleZones.some((zone) => zone.id === vehicle.zoneId)).toBe(true);
    expect(vehicle.rentable).toBe(
      vehicle.status === "AVAILABLE" && vehicle.batteryPercent >= 20,
    );
  }
  for (const zone of sampleZones) {
    expect(zone.id).toMatch(uuid);
    expect(zone.radiusMeters).toBeGreaterThan(0);
  }
});

test("overview renders honest sample indicators and contract-compatible vehicle labels", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/");
  await expect(page).toHaveTitle("Fleet operations · CEBP");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Keep the fleet moving.",
  );
  await expect(page.getByText("Synthetic data", { exact: true })).toBeVisible();
  await expect(page.getByRole("note")).toContainText(
    "Backend and live updates are not connected",
  );
  const indicators = page.getByRole("region", {
    name: "Sample fleet indicators",
  });
  await expect(indicators.locator("article")).toHaveCount(4);
  await expect(indicators.locator("strong")).toHaveText([
    "04",
    "02",
    "01",
    "01",
  ]);
  await expect(page.getByRole("rowheader")).toHaveCount(4);
  await expect(
    page.getByRole("rowheader").filter({ hasText: "ES-002" }),
  ).toContainText("E-scooter");
  await expect(
    page.getByRole("row").filter({ hasText: "EB-003" }),
  ).toContainText("Needs maintenance");
  expect(errors).toEqual([]);
});

test("navigation switches sections and marks the current destination", async ({
  page,
}) => {
  await page.goto("/");
  const nav = page.getByRole("navigation", { name: "Main navigation" });
  await nav.getByRole("button", { name: "Fleet", exact: true }).click();
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Every vehicle, in view.",
  );
  await expect(
    nav.getByRole("button", { name: "Fleet", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  await expect(
    page.getByText("API-backed vehicle lists begin in Week 2.", {
      exact: false,
    }),
  ).toBeVisible();
  await nav.getByRole("button", { name: "Proposals", exact: true }).click();
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "A human makes the call.",
  );
  await expect(
    page.getByText("The proposal transport contract is pending agreement", {
      exact: false,
    }),
  ).toBeVisible();
  await nav.getByRole("button", { name: "Overview", exact: true }).click();
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Keep the fleet moving.",
  );
});

test("proposal preview cannot submit decisions or contact operational services", async ({
  page,
}) => {
  const requests: string[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (
      request.method() !== "GET" ||
      url.origin !== "http://127.0.0.1:4173" ||
      url.pathname.startsWith("/api/")
    )
      requests.push(request.url());
  });
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "Approve", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "Reject", exact: true }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Proposals", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Approve", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "Reject", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByText("AI cannot act on its own.", { exact: false }),
  ).toBeVisible();
  expect(requests).toEqual([]);
});

test("keyboard users can skip to content and activate navigation", async ({
  page,
}) => {
  await page.goto("/");
  await page.keyboard.press("Tab");
  await expect(
    page.getByRole("link", { name: "Skip to main content" }),
  ).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("main")).toBeFocused();
  await page.getByRole("button", { name: "Fleet", exact: true }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Every vehicle, in view.",
  );
});

for (const width of [1440, 390, 320]) {
  test(`layout and navigation remain usable at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 960 });
    await page.goto("/");
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
    await page.getByRole("button", { name: "Proposals", exact: true }).click();
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(
      "A human makes the call.",
    );
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
    await page.getByRole("button", { name: "Fleet", exact: true }).click();
    const tableRegion = page.getByRole("region", {
      name: "Sample fleet table",
    });
    await expect(tableRegion).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
    if (width < 700) {
      await tableRegion.evaluate((element) => {
        element.scrollLeft = element.scrollWidth;
      });
      expect(
        await tableRegion.evaluate((element) => element.scrollLeft),
      ).toBeGreaterThan(0);
    }
    await page.getByRole("button", { name: "Overview", exact: true }).click();
    await page.screenshot({
      path: test.info().outputPath(`overview-${width}.png`),
      fullPage: true,
    });
  });
}
