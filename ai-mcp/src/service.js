import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";
import { manifest, toolSchemas, validate, validateProposal } from "./contract.js";

const vehicleIdPattern = /^veh_[A-Za-z0-9][A-Za-z0-9_-]{2,63}$/;
const areaIdPattern = /^area_[A-Za-z0-9][A-Za-z0-9_-]{2,63}$/;

export class ServiceError extends Error {
  constructor(code, message, retryable = false) { super(message); this.code = code; this.retryable = retryable; }
}

export function parseMap(raw, variableName) {
  if (!raw) return {};
  let map;
  try { map = JSON.parse(raw); } catch { throw new Error(`${variableName} must contain a JSON object`); }
  if (!map || Array.isArray(map) || typeof map !== "object") throw new Error(`${variableName} must contain a JSON object`);
  return map;
}

export function statusForMcp(status) {
  const mapping = { AVAILABLE: "AVAILABLE", RENTED: "IN_USE", MAINTENANCE_REQUIRED: "MAINTENANCE" };
  if (!mapping[status]) throw new ServiceError("READ_MODEL_UNAVAILABLE", `Backend status '${status}' has no settled MCP mapping`, true);
  return mapping[status];
}

export function areaUuid(areaId, areaMap, zones) {
  const uuid = areaMap[areaId];
  if (!uuid || !zones.some((zone) => zone.id === uuid)) throw new ServiceError("UNKNOWN_AREA", `Area '${areaId}' has no configured mapping to a backend zone`, false);
  return uuid;
}

export function createService({ backend, areaMap = {}, vehicleMap = {}, store, clock = () => new Date() }) {
  const cursors = new Map();
  const cursorSecret = randomBytes(32);

  async function loadState() {
    let [vehicles, zones] = await Promise.all([backend.vehicles(), backend.zones()]);
    if (!Array.isArray(vehicles) || !Array.isArray(zones)) throw new ServiceError("READ_MODEL_UNAVAILABLE", "Backend returned an invalid fleet snapshot", true);
    const asOf = clock().toISOString();
    vehicles = vehicles.map((v) => ({ ...v, publicId: v.publicId ?? vehicleMap[v.id] }));
    await store.saveReadModel({ asOf, vehicles, zones });
    return { asOf, vehicles, zones };
  }

  function page(items, args, snapshotId) {
    const size = args.pageSize;
    let offset = 0;
    if (args.cursor) {
      const [encoded, signature] = args.cursor.split(".");
      if (!encoded || !signature) throw new ServiceError("CURSOR_EXPIRED", "Pagination cursor is invalid or expired");
      const expected = createHmac("sha256", cursorSecret).update(encoded).digest("base64url");
      const actual = Buffer.from(signature);
      const expectedBuffer = Buffer.from(expected);
      if (actual.length !== expectedBuffer.length || !timingSafeEqual(actual, expectedBuffer)) throw new ServiceError("CURSOR_EXPIRED", "Pagination cursor is invalid or expired");
      const state = cursors.get(args.cursor);
      if (!state || state.snapshotId !== snapshotId || state.expiresAt < clock().getTime()) throw new ServiceError("CURSOR_EXPIRED", "Pagination cursor expired or fleet data changed");
      offset = state.offset;
      cursors.delete(args.cursor);
    }
    const values = items.slice(offset, offset + size);
    const hasMore = offset + size < items.length;
    const result = { values, pagination: { hasMore } };
    if (hasMore) {
      const token = Buffer.from(JSON.stringify({ offset: offset + size, snapshotId })).toString("base64url");
      const signature = createHmac("sha256", cursorSecret).update(token).digest("base64url");
      const cursor = `${token}.${signature}`;
      cursors.set(cursor, { offset: offset + size, snapshotId, expiresAt: clock().getTime() + 15 * 60 * 1000 });
      result.pagination.nextCursor = cursor;
    }
    return result;
  }

  async function callTool(name, args) {
    const spec = toolSchemas.get(name);
    if (!spec) throw new ServiceError("INVALID_ARGUMENT", `Unknown MCP tool '${name}'`);
    const inputErrors = validate(args, spec.input);
    if (inputErrors.length) return { schemaVersion: "1.0", readModelAsOf: null, error: { code: "INVALID_ARGUMENT", message: inputErrors.slice(0, 3).join("; ").slice(0, 500), retryable: false } };
    try {
      if (name === "get_demand_by_area") throw new ServiceError("READ_MODEL_UNAVAILABLE", "Demand source is not configured while event ownership and payload remain open", true);
      const { asOf, vehicles, zones } = await loadState();
      const ageSeconds = Math.max(0, Math.floor((clock().getTime() - Date.parse(asOf)) / 1000));
      if (ageSeconds > args.maxReadModelAgeSeconds) throw new ServiceError("STALE_READ_MODEL", "Backend fleet snapshot exceeds the requested maximum age", true);
      const snapshotId = createHash("sha256").update(JSON.stringify({ vehicles, zones })).digest("hex");
      let result;
      if (name === "list_low_battery_vehicles") {
        const candidates = vehicles.filter((v) => v.batteryPercent <= args.thresholdPercent);
        const selected = candidates.map((v) => {
          const areas = Object.entries(areaMap).filter(([, zoneId]) => zoneId === v.zoneId).map(([areaId]) => areaId);
          if (areas.length !== 1 || !areaIdPattern.test(areas[0] ?? "")) throw new ServiceError("READ_MODEL_UNAVAILABLE", `Low-battery vehicle '${v.id}' has no unique configured area mapping`, true);
          if (!vehicleIdPattern.test(v.publicId ?? "")) throw new ServiceError("READ_MODEL_UNAVAILABLE", `Vehicle '${v.id}' has no configured public vehicle ID`, true);
          return { vehicleId: v.publicId, areaId: areas[0], batteryPercent: v.batteryPercent, operationalStatus: statusForMcp(v.status), batteryMeasuredAt: v.lastBatteryMeasuredAt };
        })
          .sort((a, b) => a.batteryPercent - b.batteryPercent || a.vehicleId.localeCompare(b.vehicleId));
        const selectedPage = page(selected, args, snapshotId);
        result = { schemaVersion: "1.0", readModelAsOf: asOf, freshness: { status: "FRESH", ageSeconds, maxAgeSeconds: args.maxReadModelAgeSeconds }, vehicles: selectedPage.values, pagination: selectedPage.pagination };
      } else {
        const zoneUuid = areaUuid(args.areaId, areaMap, zones);
        const selected = vehicles.filter((v) => v.zoneId === zoneUuid && v.status === "AVAILABLE" && (args.minimumBatteryPercent === undefined || v.batteryPercent >= args.minimumBatteryPercent))
          .sort((a, b) => (a.publicId ?? "").localeCompare(b.publicId ?? ""));
        if (selected.some((v) => !vehicleIdPattern.test(v.publicId ?? ""))) throw new ServiceError("READ_MODEL_UNAVAILABLE", "An available vehicle is missing its configured public vehicle ID", true);
        const selectedPage = page(selected, args, snapshotId);
        result = { schemaVersion: "1.0", readModelAsOf: asOf, freshness: { status: "FRESH", ageSeconds, maxAgeSeconds: args.maxReadModelAgeSeconds }, areaId: args.areaId, availableCount: selected.length,
          vehicles: selectedPage.values.map((v) => ({ vehicleId: v.publicId, batteryPercent: v.batteryPercent, batteryMeasuredAt: v.lastBatteryMeasuredAt, location: { latitude: v.latitude, longitude: v.longitude }, locationMeasuredAt: v.lastLocationMeasuredAt, availableSince: v.availableSince })), pagination: selectedPage.pagination };
      }
      const outputErrors = validate(result, spec.output);
      if (outputErrors.length) throw new ServiceError("INTERNAL_ERROR", `Tool output failed its contract: ${outputErrors[0]}`);
      return result;
    } catch (error) {
      const code = error.code ?? "READ_MODEL_UNAVAILABLE";
      const message = error.message ?? "Backend fleet state is unavailable";
      const result = { schemaVersion: "1.0", readModelAsOf: null, error: { code, message: message.slice(0, 500), retryable: error.retryable ?? true } };
      if (!validate(result, spec.output).length) return result;
      return { schemaVersion: "1.0", readModelAsOf: null, error: { code: "INTERNAL_ERROR", message: "Tool failed contract validation", retryable: true } };
    }
  }

  async function createProposal() {
    const { asOf, vehicles, zones } = await loadState();
    const mappedAreas = new Map(Object.entries(areaMap).map(([area, zoneUuid]) => [zoneUuid, area]));
    const candidates = vehicles.filter((v) => v.batteryPercent < 30 && v.status === "AVAILABLE" && vehicleIdPattern.test(v.publicId ?? "") && areaIdPattern.test(mappedAreas.get(v.zoneId) ?? ""))
      .sort((a, b) => a.batteryPercent - b.batteryPercent || a.id.localeCompare(b.id));
    if (!candidates.length) throw new ServiceError("NO_ACTIONS", "No mapped available vehicles under 30 percent battery; no proposal was created");
    const now = clock().toISOString();
    const chosen = candidates.slice(0, 5);
    const sourceResult = { schemaVersion: "1.0", readModelAsOf: asOf, vehicles: chosen.map((v) => ({ id: v.id, publicId: v.publicId, batteryPercent: v.batteryPercent, status: v.status, zoneId: v.zoneId })) };
    const resultSha256 = createHash("sha256").update(JSON.stringify(sourceResult)).digest("hex");
    const identitySeed = `${asOf}:${resultSha256}:mock-planner-v1`;
    const digest = (value) => createHash("sha256").update(value).digest("hex");
    const correlationId = `corr_${digest(`correlation:${identitySeed}`).slice(0, 24)}`;
    const runId = `run_${digest(`run:${identitySeed}`).slice(0, 24)}`;
    const proposalId = `proposal_${digest(`proposal:${identitySeed}`).slice(0, 24)}`;
    const actions = chosen.map((v) => ({ actionId: `action_${digest(`action:${proposalId}:${v.id}`).slice(0, 12)}`, type: "CHARGE", priority: v.batteryPercent <= 10 ? "HIGH" : "MEDIUM", vehicleId: v.publicId, currentAreaId: mappedAreas.get(v.zoneId), targetBatteryPercent: 80,
      rationale: `Available vehicle has ${v.batteryPercent}% battery in its mapped area. Charging is a recommendation for operator review.`,
      sourceEvidence: [{ toolName: "list_low_battery_vehicles", readModelAsOf: asOf, vehicleIds: [v.publicId], observation: `Backend snapshot reports ${v.batteryPercent}% battery and AVAILABLE status.` }], warnings: [] }));
    const proposal = { schemaVersion: "1.0", proposalId, runId, createdAt: now, status: "PENDING_APPROVAL", effectPolicy: "HUMAN_APPROVAL_REQUIRED", operationalStateChanged: false,
      snapshot: { snapshotId: `snapshot_${createHash("sha256").update(`${asOf}:${resultSha256}`).digest("hex").slice(0, 24)}`, readModelAsOf: asOf, sources: [{ toolName: "list_low_battery_vehicles", readModelAsOf: asOf, resultSha256 }] },
      planner: { plannerVersion: "1.0.0", mode: "DETERMINISTIC_MOCK", modelProvider: "local", modelId: "deterministic-mock-v1", temperature: 0, seed: 1 }, actions,
      audit: { correlationId, createdBy: "ai-planner", promptTemplateVersion: "1.0", outputSha256: "0".repeat(64), validation: { schema: "PASSED", knownIdGrounding: "PASSED", hardRules: "PASSED", approvalSafety: "PASSED" } }, approval: { required: true } };
    if (validateProposal(proposal).length) throw new ServiceError("INTERNAL_ERROR", `Mock planner produced an invalid proposal: ${validateProposal(proposal)[0]}`);
    const canonicalProposal = structuredClone(proposal);
    canonicalProposal.audit.outputSha256 = "";
    proposal.audit.outputSha256 = digest(JSON.stringify(canonicalProposal));
    if (validateProposal(proposal).length) throw new ServiceError("INTERNAL_ERROR", "Proposal failed schema validation");
    await store.saveProposal(proposal);
    return proposal;
  }

  async function decideProposal(proposalId, outcome, operatorId, comment) {
    const proposal = await store.getProposal(proposalId);
    if (!proposal) return null;
    if (proposal.status !== "PENDING_APPROVAL") return proposal.status === outcome ? proposal : { conflict: true };
    const decidedAt = clock().toISOString();
    const updated = { ...proposal, status: outcome, operationalStateChanged: false, approval: { required: true, decision: { outcome, operatorId, decidedAt, ...(comment ? { comment } : {}) } } };
    if (validateProposal(updated).length) throw new ServiceError("INVALID_ARGUMENT", "Decision does not conform to the proposal contract");
    await store.saveProposal(updated);
    return updated;
  }

  return { callTool, createProposal, decideProposal, getProposal: (id) => store.getProposal(id), listProposals: () => store.listProposals(), loadState };
}

export function createJsonStore(directory) {
  const file = path.resolve(directory, "state.json");
  let pending = Promise.resolve();
  async function read() {
    try { return JSON.parse(await readFile(file, "utf8")); }
    catch (error) { if (error.code === "ENOENT") return { proposals: {}, readModel: null }; throw error; }
  }
  async function update(mutator) {
    const operation = pending.then(async () => {
      const state = await read();
      mutator(state);
      await mkdir(path.dirname(file), { recursive: true });
      const temporary = `${file}.${process.pid}.tmp`;
      await writeFile(temporary, JSON.stringify(state, null, 2), "utf8");
      await rename(temporary, file);
    });
    pending = operation.catch(() => {});
    return operation;
  }
  return {
    async saveReadModel(snapshot) { await update((state) => { state.readModel = snapshot; }); },
    async saveProposal(proposal) { await update((state) => { state.proposals[proposal.proposalId] = proposal; }); },
    async getProposal(id) { await pending; return (await read()).proposals[id] ?? null; },
    async listProposals() { await pending; return Object.values((await read()).proposals).sort((a, b) => b.createdAt.localeCompare(a.createdAt)); },
  };
}

export function createBackend(baseUrl, fetchImpl = fetch) {
  async function get(pathname) {
    const response = await fetchImpl(new URL(pathname, baseUrl), { headers: { accept: "application/json" }, signal: AbortSignal.timeout(5000) });
    if (!response.ok) throw new ServiceError("READ_MODEL_UNAVAILABLE", `Backend returned HTTP ${response.status}`, true);
    return response.json();
  }
  return { vehicles: () => get("/vehicles?limit=500"), zones: () => get("/zones") };
}
