import { expect, test } from "@playwright/test";
import { createFleetApi, FleetApiError } from "../src/api/fleet";
import { apiStatus, apiVehicles, apiMaintenance } from "./fixtures";

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

test("serializes inclusive zero/100 battery bounds and combined maintenance filters", async () => {
  const paths: string[] = [];
  const api = createFleetApi({
    fetcher: async (input) => {
      paths.push(String(input));
      return Response.json([]);
    },
  });
  await api.listVehicles(undefined, {
    status: "MAINTENANCE_REQUIRED",
    maxBattery: 0,
  });
  await api.listVehicles(undefined, { maxBattery: 100 });
  expect(paths).toEqual([
    "/api/v1/vehicles?limit=500&status=MAINTENANCE_REQUIRED&maxBattery=0",
    "/api/v1/vehicles?limit=500&maxBattery=100",
  ]);
});

test("rejects invalid filter inputs without sending a request", async () => {
  let calls = 0;
  const api = createFleetApi({
    fetcher: async () => {
      calls++;
      return Response.json([]);
    },
  });
  for (const maxBattery of [-1, 101, 20.5, NaN])
    await expect(
      api.listVehicles(undefined, { maxBattery }),
    ).rejects.toMatchObject({ code: "INVALID_FILTER" });
  expect(calls).toBe(0);
});

test("details and open maintenance use the exact opaque vehicle ID", async () => {
  const paths: string[] = [];
  const api = createFleetApi({
    fetcher: async (input) => {
      paths.push(String(input));
      return Response.json(
        String(input).includes("/maintenance")
          ? apiMaintenance
          : apiVehicles[2],
      );
    },
  });
  expect(await api.getVehicle("veh_103")).toEqual(apiVehicles[2]);
  expect(await api.getMaintenance("veh_103")).toEqual(apiMaintenance);
  expect(paths).toEqual([
    "/api/v1/vehicles/veh_103",
    "/api/v1/maintenance?vehicleId=veh_103&limit=500",
  ]);
});

test("rejects another vehicle’s details or maintenance rather than mixing records", async () => {
  const api = createFleetApi({
    fetcher: async (input) =>
      Response.json(
        String(input).includes("/maintenance")
          ? apiMaintenance
          : apiVehicles[0],
      ),
  });
  await expect(api.getVehicle("veh_103")).rejects.toMatchObject({
    code: "INVALID_RESPONSE",
  });
  await expect(api.getMaintenance("veh_101")).rejects.toMatchObject({
    code: "INVALID_RESPONSE",
  });
});

test("encodes vehicle IDs as data in a detail path", async () => {
  let path = "";
  const id = "veh_special/part?x=1";
  const api = createFleetApi({
    fetcher: async (input) => {
      path = String(input);
      return Response.json({ ...apiVehicles[0], id });
    },
  });
  await api.getVehicle(id);
  expect(path).toBe("/api/v1/vehicles/veh_special%2Fpart%3Fx%3D1");
});
