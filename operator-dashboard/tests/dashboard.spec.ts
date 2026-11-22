import { expect, test } from "@playwright/test";
import { apiStatus, apiVehicles, apiMaintenance } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (path === "/api/v1/vehicles")
      return route.fulfill({
        json: apiVehicles.filter(
          (vehicle) =>
            (!url.searchParams.has("status") ||
              vehicle.status === url.searchParams.get("status")) &&
            (!url.searchParams.has("maxBattery") ||
              vehicle.batteryPercent <=
                Number(url.searchParams.get("maxBattery"))),
        ),
      });
    if (path === "/api/v1/fleet/status")
      return route.fulfill({ json: apiStatus });
    if (path.startsWith("/api/v1/vehicles/")) {
      const vehicle = apiVehicles.find(
        (vehicle) => vehicle.id === decodeURIComponent(path.split("/").at(-1)!),
      );
      return route.fulfill(
        vehicle
          ? { json: vehicle }
          : { status: 404, json: { code: "NOT_FOUND" } },
      );
    }
    if (path === "/api/v1/maintenance")
      return route.fulfill({
        json: apiMaintenance.filter(
          (record) => record.vehicleId === url.searchParams.get("vehicleId"),
        ),
      });
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

test("combines status and battery filters on the server while keeping full-fleet indicators", async ({
  page,
}) => {
  const requests: URL[] = [];
  page.on("request", (request) => {
    if (new URL(request.url()).pathname === "/api/v1/vehicles")
      requests.push(new URL(request.url()));
  });
  await page.goto("/");
  await expect(page.getByRole("rowheader")).toHaveCount(4);
  await page.getByLabel("Vehicle status").selectOption("MAINTENANCE_REQUIRED");
  await page.getByLabel("Maximum battery (%)").fill("20");
  await page
    .getByRole("button", { name: "Apply filters", exact: true })
    .click();
  await expect(page.getByRole("rowheader")).toHaveCount(1);
  await expect(page.getByRole("rowheader").first()).toContainText(
    "API-BIKE-103",
  );
  expect(requests.at(-1)?.searchParams.get("status")).toBe(
    "MAINTENANCE_REQUIRED",
  );
  expect(requests.at(-1)?.searchParams.get("maxBattery")).toBe("20");
  await expect(
    page.getByRole("region", { name: "Fleet indicators" }).locator("strong"),
  ).toHaveText(["4", "2", "2", "1"]);
  await page.getByRole("button", { name: "Fleet", exact: true }).click();
  await expect(page.getByLabel("Vehicle status")).toHaveValue(
    "MAINTENANCE_REQUIRED",
  );
  await expect(page.getByLabel("Maximum battery (%)")).toHaveValue("20");
  await page
    .getByRole("button", { name: "Clear filters", exact: true })
    .click();
  await expect(page.getByRole("rowheader")).toHaveCount(4);
  await expect(page.getByLabel("Vehicle status")).toHaveValue("");
});

test("distinguishes no filter matches and prevents invalid battery submissions", async ({
  page,
}) => {
  await page.goto("/");
  await expect(page.getByRole("rowheader")).toHaveCount(4);
  await page.getByLabel("Maximum battery (%)").fill("101");
  await page
    .getByRole("button", { name: "Apply filters", exact: true })
    .click();
  expect(
    await page
      .getByLabel("Maximum battery (%)")
      .evaluate((input: HTMLInputElement) => input.validity.rangeOverflow),
  ).toBe(true);
  await expect(page.getByRole("rowheader")).toHaveCount(4);
  await page.getByLabel("Vehicle status").selectOption("AVAILABLE");
  await page.getByLabel("Maximum battery (%)").fill("0");
  await page
    .getByRole("button", { name: "Apply filters", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "No matching vehicles" }),
  ).toBeVisible();
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("opens read-only details and maintenance and restores keyboard focus on Escape", async ({
  page,
}) => {
  await page.goto("/");
  const open = page.getByRole("button", {
    name: "View API-BIKE-103 details",
    exact: true,
  });
  await open.focus();
  await page.keyboard.press("Enter");
  const dialog = page.getByRole("dialog", { name: "Vehicle details" });
  await expect(dialog).toBeVisible();
  await expect(
    dialog.getByRole("heading", { name: "API-BIKE-103" }),
  ).toBeVisible();
  await expect(dialog).toContainText("12%");
  await expect(dialog).toContainText("48.211");
  await expect(dialog).toContainText("BRAKE_SENSOR");
  await expect(dialog).toContainText("Not reported");
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(open).toBeFocused();
});

test("a missing vehicle has a retryable detail error; failed maintenance does not hide details", async ({
  page,
}) => {
  let missing = true;
  await page.route("**/api/v1/vehicles/veh_101", (route) =>
    missing
      ? route.fulfill({ status: 404, json: { code: "NOT_FOUND" } })
      : route.fulfill({ json: apiVehicles[0] }),
  );
  await page.route("**/api/v1/maintenance?*", (route) =>
    route.fulfill({ status: 503, json: { code: "INTERNAL_ERROR" } }),
  );
  await page.goto("/");
  await page
    .getByRole("button", { name: "View API-BIKE-101 details", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText(
    "The requested fleet resource was not found",
  );
  missing = false;
  await dialog
    .getByRole("button", { name: "Retry vehicle details", exact: true })
    .click();
  await expect(
    dialog.getByRole("heading", { name: "API-BIKE-101" }),
  ).toBeVisible();
  await expect(dialog).toContainText("Could not load maintenance records");
  await dialog
    .getByRole("button", { name: "Close details", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("detail refresh reads updated measurements without inferring location from battery time", async ({
  page,
}) => {
  let updated = false;
  await page.route("**/api/v1/vehicles/veh_101", (route) =>
    route.fulfill({
      json: { ...apiVehicles[0], batteryPercent: updated ? 74 : 92 },
    }),
  );
  await page.goto("/");
  await page
    .getByRole("button", { name: "View API-BIKE-101 details", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("92%");
  updated = true;
  await dialog
    .getByRole("button", { name: "Refresh details", exact: true })
    .click();
  await expect(dialog).toContainText("74%");
  await expect(
    dialog.getByText("Location measured", { exact: true }).locator(".."),
  ).toContainText("Not reported");
});

test("demo filtering and details work without a backend", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("Data source").selectOption("demo");
  await page.getByLabel("Vehicle status").selectOption("MAINTENANCE_REQUIRED");
  await page
    .getByRole("button", { name: "Apply filters", exact: true })
    .click();
  await expect(page.getByRole("rowheader")).toHaveCount(1);
  await page
    .getByRole("button", { name: "View EB-003 details", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText("Low battery");
  await expect(page.getByRole("dialog")).toContainText("Demo vehicle");
});

test("vehicle details fit a narrow screen and remain scrollable", async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 640 });
  await page.goto("/");
  await page
    .getByRole("button", { name: "View API-BIKE-103 details", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByRole("heading", { name: "API-BIKE-103" }),
  ).toBeVisible();
  const bounds = await dialog.boundingBox();
  expect(bounds?.width).toBeLessThanOrEqual(320);
  expect(
    await dialog.evaluate(
      (element) => element.scrollHeight > element.clientHeight,
    ),
  ).toBe(true);
  await page.screenshot({
    path: test.info().outputPath("vehicle-details-320.png"),
  });
});
