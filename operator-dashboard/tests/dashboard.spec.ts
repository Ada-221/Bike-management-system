import { expect, test } from "@playwright/test";
import { apiStatus, apiVehicles } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/vehicles")
      return route.fulfill({ json: apiVehicles });
    if (path === "/api/v1/fleet/status")
      return route.fulfill({ json: apiStatus });
    return route.fulfill({ status: 404, json: { code: "NOT_FOUND" } });
  });
});

test("loads backend vehicles, summary counts, independent active rentals, and zone names", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/");
  await expect(page).toHaveTitle("Fleet operations · CEBP");
  await expect(page.getByText("Backend data", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Fleet indicators" }).locator("strong"),
  ).toHaveText(["4", "2", "2", "1"]);
  await expect(page.getByRole("rowheader")).toHaveCount(4);
  await expect(
    page.getByRole("row").filter({ hasText: "API-BIKE-101" }),
  ).toContainText("Backend campus");
  await expect(
    page.getByRole("row").filter({ hasText: "API-SCOOTER-104" }),
  ).toContainText("Unassigned");
  await expect(
    page.getByRole("row").filter({ hasText: "API-BIKE-103" }),
  ).toContainText("Needs maintenance");
  await expect(page.getByText("EB-001", { exact: true })).toHaveCount(0);
  expect(errors).toEqual([]);
});

test("navigation keeps the loaded list and keyboard support", async ({
  page,
}) => {
  await page.goto("/");
  await page.keyboard.press("Tab");
  await expect(
    page.getByRole("link", { name: "Skip to main content" }),
  ).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("main")).toBeFocused();
  const fleet = page.getByRole("button", { name: "Fleet", exact: true });
  await fleet.focus();
  await page.keyboard.press("Enter");
  await expect(fleet).toHaveAttribute("aria-current", "page");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Every vehicle, in view.",
  );
  await expect(
    page.getByRole("rowheader").filter({ hasText: "API-BIKE-101" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Proposals", exact: true }).click();
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "A human makes the call.",
  );
});

test("makes only the two documented read requests and cannot submit proposal decisions", async ({
  page,
}) => {
  const requests: { path: string; method: string }[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/api/"))
      requests.push({
        path: new URL(request.url()).pathname,
        method: request.method(),
      });
  });
  await page.goto("/");
  await expect(page.getByRole("rowheader")).toHaveCount(4);
  await page.getByRole("button", { name: "Proposals", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Approve", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "Reject", exact: true }),
  ).toBeDisabled();
  expect(requests.length).toBeGreaterThanOrEqual(2);
  expect(
    requests.every(
      (r) =>
        r.method === "GET" &&
        ["/api/v1/vehicles", "/api/v1/fleet/status"].includes(r.path),
    ),
  ).toBe(true);
});

test("shows loading without inventing zero counts or demo vehicles", async ({
  page,
}) => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/v1/**", async (route) => {
    await gate;
    await route.fulfill({
      json: new URL(route.request().url()).pathname.endsWith("/vehicles")
        ? apiVehicles
        : apiStatus,
    });
  });
  await page.goto("/");
  await expect(
    page.getByText("Loading vehicles…", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Fleet indicators" }).locator("strong"),
  ).toHaveText(["—", "—", "—", "—"]);
  await expect(page.getByRole("rowheader")).toHaveCount(0);
  release();
  await expect(page.getByRole("rowheader")).toHaveCount(4);
});

test("renders a successful empty fleet distinctly from an API error", async ({
  page,
}) => {
  await page.route("**/api/v1/vehicles?*", (route) =>
    route.fulfill({ json: [] }),
  );
  await page.route("**/api/v1/fleet/status", (route) =>
    route.fulfill({
      json: {
        ...apiStatus,
        totalVehicles: 0,
        countByStatus: { AVAILABLE: 0, RENTED: 0, MAINTENANCE_REQUIRED: 0 },
        activeRentals: 0,
        lowBatteryCount: 0,
        averageBatteryPercent: 0,
        zones: [],
      },
    }),
  );
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "No vehicles yet" }),
  ).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Fleet indicators" }).locator("strong"),
  ).toHaveText(["0", "0", "0", "0"]);
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("network failure stays visible without a demo fallback and retry recovers", async ({
  page,
}) => {
  let failing = true;
  await page.route("**/api/v1/vehicles?*", (route) =>
    failing
      ? route.abort("connectionrefused")
      : route.fulfill({ json: apiVehicles }),
  );
  await page.goto("/");
  await expect(page.getByRole("alert")).toContainText(
    "Cannot reach the fleet service",
  );
  await expect(page.getByRole("rowheader")).toHaveCount(0);
  await expect(page.getByText("Demo mode", { exact: true })).toHaveCount(0);
  failing = false;
  await page
    .getByRole("button", { name: "Retry vehicles", exact: true })
    .click();
  await expect(page.getByRole("rowheader")).toHaveCount(4);
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("summary failure does not hide the vehicle list or guess zone names", async ({
  page,
}) => {
  await page.route("**/api/v1/fleet/status", (route) =>
    route.fulfill({
      status: 503,
      json: { code: "INTERNAL_ERROR", message: "Private database diagnostic" },
    }),
  );
  await page.goto("/");
  await expect(page.getByRole("alert")).toContainText(
    "Could not load fleet summary",
  );
  await expect(page.getByRole("rowheader")).toHaveCount(4);
  await expect(
    page.getByText("zone_001 (name unavailable)").first(),
  ).toBeVisible();
  await expect(page.getByText("Private database diagnostic")).toHaveCount(0);
  await expect(
    page.getByRole("region", { name: "Fleet indicators" }).locator("strong"),
  ).toHaveText(["—", "—", "—", "—"]);
});

test("malformed successful responses show an error instead of crashing", async ({
  page,
}) => {
  await page.route("**/api/v1/vehicles?*", (route) =>
    route.fulfill({ json: [{ ...apiVehicles[0], status: ["AVAILABLE"] }] }),
  );
  await page.goto("/");
  await expect(page.getByRole("alert")).toContainText("unexpected response");
  await expect(page.getByRole("rowheader")).toHaveCount(0);
});

test("refresh retrieves new values and a failed refresh marks retained data as stale", async ({
  page,
}) => {
  let fail = false;
  let vehicles = apiVehicles;
  await page.route("**/api/v1/vehicles?*", (route) =>
    fail
      ? route.fulfill({
          status: 502,
          contentType: "text/html",
          body: "<h1>Bad gateway</h1>",
        })
      : route.fulfill({ json: vehicles }),
  );
  await page.goto("/");
  await expect(
    page.getByRole("row").filter({ hasText: "API-BIKE-101" }),
  ).toContainText("92%");
  vehicles = apiVehicles.map((v) => ({ ...v, batteryPercent: 44 }));
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(
    page.getByRole("row").filter({ hasText: "API-BIKE-101" }),
  ).toContainText("44%");
  await expect(
    page.getByRole("button", { name: "Refresh", exact: true }),
  ).toBeEnabled();
  fail = true;
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText(
    "Showing the previous snapshot",
  );
  await expect(
    page.getByRole("row").filter({ hasText: "API-BIKE-101" }),
  ).toContainText("44%");
});

test("demo mode is explicit and late API responses cannot overwrite it", async ({
  page,
}) => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/v1/vehicles?*", async (route) => {
    await gate;
    await route.fulfill({ json: apiVehicles });
  });
  await page.goto("/");
  await expect(
    page.getByText("Loading vehicles…", { exact: true }),
  ).toBeVisible();
  await page.getByLabel("Data source").selectOption("demo");
  await expect(
    page.getByRole("rowheader").filter({ hasText: "EB-001" }),
  ).toBeVisible();
  release();
  await expect(page.getByText("Demo mode", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("rowheader").filter({ hasText: "API-BIKE-101" }),
  ).toHaveCount(0);
  await page.getByLabel("Data source").selectOption("api");
  await expect(
    page.getByRole("rowheader").filter({ hasText: "API-BIKE-101" }),
  ).toBeVisible();
  await expect(
    page.getByRole("rowheader").filter({ hasText: "EB-001" }),
  ).toHaveCount(0);
});

test("fleet total is not derived from the capped list and a full page is labelled", async ({
  page,
}) => {
  const vehicles = Array.from({ length: 500 }, (_, index) => ({
    ...apiVehicles[0],
    id: `veh_${index + 1}`,
    label: `LIMIT-${index + 1}`,
  }));
  await page.route("**/api/v1/vehicles?*", (route) =>
    route.fulfill({ json: vehicles }),
  );
  await page.route("**/api/v1/fleet/status", (route) =>
    route.fulfill({
      json: {
        ...apiStatus,
        totalVehicles: 501,
        countByStatus: { AVAILABLE: 501, RENTED: 0, MAINTENANCE_REQUIRED: 0 },
        activeRentals: 0,
      },
    }),
  );
  await page.goto("/");
  await expect(
    page
      .getByRole("region", { name: "Fleet indicators" })
      .locator("strong")
      .first(),
  ).toHaveText("501");
  await expect(page.getByRole("rowheader")).toHaveCount(500);
  await expect(
    page.getByText("The API returns at most 500 vehicles per request", {
      exact: false,
    }),
  ).toBeVisible();
});

for (const width of [1440, 390, 320]) {
  test(`layout and navigation remain usable at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 960 });
    await page.goto("/");
    await expect(page.getByRole("rowheader")).toHaveCount(4);
    for (const name of ["Overview", "Proposals", "Fleet"]) {
      await page.getByRole("button", { name, exact: true }).click();
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true);
    }
    if (width < 700) {
      const table = page.getByRole("region", { name: "Fleet vehicle table" });
      await table.evaluate((element) => {
        element.scrollLeft = element.scrollWidth;
      });
      expect(
        await table.evaluate((element) => element.scrollLeft),
      ).toBeGreaterThan(0);
    }
    await page.getByRole("button", { name: "Overview", exact: true }).click();
    await page.screenshot({
      path: test.info().outputPath(`overview-${width}.png`),
      fullPage: true,
    });
  });
}
