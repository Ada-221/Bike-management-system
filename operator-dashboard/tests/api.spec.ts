import { expect, test } from "@playwright/test";
import { createFleetApi, FleetApiError } from "../src/api/fleet";
import { apiStatus, apiVehicles } from "./fixtures";

test("uses documented read routes, the 500 limit, no cache, and correlation IDs", async () => {
  const requests: { path: string; init?: RequestInit }[] = [];
  const api = createFleetApi({
    fetcher: async (input, init) => {
      requests.push({ path: String(input), init });
      return Response.json(
        String(input).includes("/vehicles") ? apiVehicles : apiStatus,
      );
    },
  });
  expect(await api.listVehicles()).toEqual(apiVehicles);
  expect(await api.getStatus()).toEqual(apiStatus);
  expect(requests.map((r) => r.path)).toEqual([
    "/api/v1/vehicles?limit=500",
    "/api/v1/fleet/status",
  ]);
  for (const { init } of requests) {
    expect(init?.method).toBe("GET");
    expect(init?.cache).toBe("no-store");
    expect(new Headers(init?.headers).get("Accept")).toBe("application/json");
    expect(new Headers(init?.headers).get("X-Correlation-Id")).toMatch(
      /^[0-9a-f-]{36}$/,
    );
  }
  expect(
    new Headers(requests[0].init?.headers).get("X-Correlation-Id"),
  ).not.toBe(new Headers(requests[1].init?.headers).get("X-Correlation-Id"));
});

for (const [name, patch] of [
  ["battery out of range", { batteryPercent: 101 }],
  ["invalid coordinate", { latitude: 91 }],
  ["unrecognized status", { status: "OFFLINE" }],
  ["wrong status type", { status: ["AVAILABLE"] }],
  ["invalid measurement time", { lastBatteryAt: "yesterday" }],
] as const) {
  test(`rejects ${name} at the API boundary`, async () => {
    const api = createFleetApi({
      fetcher: async () => Response.json([{ ...apiVehicles[0], ...patch }]),
    });
    await expect(api.listVehicles()).rejects.toMatchObject({
      code: "INVALID_RESPONSE",
    });
  });
}

test("rejects duplicate vehicle IDs and malformed fleet totals", async () => {
  const duplicates = createFleetApi({
    fetcher: async () => Response.json([apiVehicles[0], apiVehicles[0]]),
  });
  await expect(duplicates.listVehicles()).rejects.toMatchObject({
    code: "INVALID_RESPONSE",
  });
  const badSummary = createFleetApi({
    fetcher: async () => Response.json({ ...apiStatus, totalVehicles: -1 }),
  });
  await expect(badSummary.getStatus()).rejects.toMatchObject({
    code: "INVALID_RESPONSE",
  });
});

test("keeps error code and correlation reference without displaying server diagnostics", async () => {
  const api = createFleetApi({
    fetcher: async () =>
      Response.json(
        {
          code: "VALIDATION_ERROR",
          message: "Private diagnostic",
          correlationId: "test-correlation",
        },
        { status: 400 },
      ),
  });
  await expect(api.listVehicles()).rejects.toMatchObject({
    code: "VALIDATION_ERROR",
    status: 400,
    correlationId: "test-correlation",
  });
  const failure = await api.listVehicles().catch((error) => error);
  expect(failure).toBeInstanceOf(FleetApiError);
  expect(failure.message).not.toContain("Private diagnostic");
});

test("non-JSON gateway failures are recoverable HTTP errors", async () => {
  const api = createFleetApi({
    fetcher: async () => new Response("<h1>Bad gateway</h1>", { status: 502 }),
  });
  await expect(api.listVehicles()).rejects.toMatchObject({
    code: "HTTP_ERROR",
    status: 502,
  });
});

test("times out a stalled request and aborts its fetch signal", async () => {
  let aborted = false;
  const api = createFleetApi({
    timeoutMs: 15,
    fetcher: async (_input, init) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener(
          "abort",
          () => {
            aborted = true;
            reject(new DOMException("Aborted", "AbortError"));
          },
          { once: true },
        );
      }),
  });
  await expect(api.listVehicles()).rejects.toMatchObject({ code: "TIMEOUT" });
  expect(aborted).toBe(true);
});

test("propagates caller cancellation rather than converting it to a connection failure", async () => {
  const controller = new AbortController();
  const api = createFleetApi({
    fetcher: async (_input, init) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener(
          "abort",
          () => reject(new DOMException("Aborted", "AbortError")),
          { once: true },
        );
      }),
  });
  const request = api.listVehicles(controller.signal);
  controller.abort();
  await expect(request).rejects.toMatchObject({ name: "AbortError" });
});
