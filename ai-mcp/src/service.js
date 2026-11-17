import { createHash, randomBytes } from "node:crypto";
export { createJsonStore } from "./store.js";
import { toolSchemas, validate, validateProposal } from "./contract.js";

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

export function validateAreaMap(areaMap) {
  if (!areaMap || Array.isArray(areaMap) || typeof areaMap !== "object") throw new Error("Area mappings must be an object");
  const zones = new Set();
  for (const [area, zone] of Object.entries(areaMap)) {
    if (!areaIdPattern.test(area) || typeof zone !== "string" || !/^zone_[A-Za-z0-9][A-Za-z0-9_-]{2,63}$/.test(zone)) throw new Error("Area mappings must map area_ identifiers to zone_ identifiers");
    if (zones.has(zone)) throw new Error("Area mappings must not map multiple areas to the same zone");
    zones.add(zone);
  }
}
export function areaZone(areaId, areaMap) {
  if (!Object.hasOwn(areaMap, areaId)) throw new ServiceError("UNKNOWN_AREA", "Area has no configured mapping to a backend zone");
  return areaMap[areaId];
}
export function createService({ snapshotProvider, areaMap = {}, store, clock = () => new Date() }) {
  validateAreaMap(areaMap);
  areaMap = { ...areaMap };
  const cursors = new Map();

  async function loadState() {
    const snapshot = await snapshotProvider.snapshot();
    if (!snapshot || !Array.isArray(snapshot.vehicles) || !Number.isFinite(Date.parse(snapshot.asOf))) throw new ServiceError("READ_MODEL_UNAVAILABLE", "Projection snapshot is invalid", true);
    const { vehicles } = snapshot;
    if (vehicles.some((v) => !v || !vehicleIdPattern.test(v.id ?? "")) || new Set(vehicles.map((v) => v.id)).size !== vehicles.length) throw new ServiceError("READ_MODEL_UNAVAILABLE", "Projection vehicle IDs are invalid or duplicated", true);
    return snapshot;
  }

  function page(items, args, snapshotId) {
    const size = args.pageSize;
    let offset = 0;
    if (args.cursor) {
      const state = cursors.get(args.cursor);
      if (!state || state.snapshotId !== snapshotId || state.expiresAt < clock().getTime()) throw new ServiceError("CURSOR_EXPIRED", "Pagination cursor expired or fleet data changed");
      offset = state.offset;
      cursors.delete(args.cursor);
    }
    const values = items.slice(offset, offset + size);
    const hasMore = offset + size < items.length;
    const result = { values, pagination: { hasMore } };
    if (hasMore) {
      for (const [token, state] of cursors) if (state.expiresAt < clock().getTime()) cursors.delete(token);
      const cursor = randomBytes(32).toString("base64url");
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
      const { asOf, vehicles, contentRevision } = await loadState();
      const ageSeconds = Math.max(0, Math.floor((clock().getTime() - Date.parse(asOf)) / 1000));
      if (clock().getTime() - Date.parse(asOf) > args.maxReadModelAgeSeconds * 1000) throw new ServiceError("STALE_READ_MODEL", "Kafka read model exceeds the requested maximum age", true);
      const snapshotId = createHash("sha256").update(JSON.stringify({ name, args: Object.fromEntries(Object.entries(args).filter(([key]) => key !== "cursor").sort(([a], [b]) => a.localeCompare(b))), vehicles, contentRevision, areaMap })).digest("hex");
      let result;
      if (name === "list_low_battery_vehicles") {
        const candidates = vehicles.filter((v) => v.batteryPercent <= args.thresholdPercent);
        const selected = candidates.map((v) => {
          const areas = Object.entries(areaMap).filter(([, zoneId]) => zoneId === v.zoneId).map(([areaId]) => areaId);
          if (areas.length !== 1 || !areaIdPattern.test(areas[0] ?? "")) throw new ServiceError("READ_MODEL_UNAVAILABLE", `Low-battery vehicle '${v.id}' has no unique configured area mapping`, true);
          if (!vehicleIdPattern.test(v.id ?? "")) throw new ServiceError("READ_MODEL_UNAVAILABLE", `Vehicle '${v.id}' has no configured public vehicle ID`, true);
          return { vehicleId: v.id, areaId: areas[0], batteryPercent: v.batteryPercent, operationalStatus: statusForMcp(v.status), batteryMeasuredAt: v.lastBatteryAt };
        })
          .sort((a, b) => a.batteryPercent - b.batteryPercent || a.vehicleId.localeCompare(b.vehicleId));
        const selectedPage = page(selected, args, snapshotId);
        result = { schemaVersion: "1.0", readModelAsOf: asOf, freshness: { status: "FRESH", ageSeconds, maxAgeSeconds: args.maxReadModelAgeSeconds }, vehicles: selectedPage.values, pagination: selectedPage.pagination };
      } else {
        const zoneId = areaZone(args.areaId, areaMap);
        const selected = vehicles.filter((v) => v.zoneId === zoneId && v.status === "AVAILABLE" && (args.minimumBatteryPercent === undefined || v.batteryPercent >= args.minimumBatteryPercent))
          .sort((a, b) => a.id.localeCompare(b.id));
        if (selected.some((v) => !vehicleIdPattern.test(v.id ?? ""))) throw new ServiceError("READ_MODEL_UNAVAILABLE", "An available vehicle is missing its configured public vehicle ID", true);
        const selectedPage = page(selected, args, snapshotId);
        result = { schemaVersion: "1.0", readModelAsOf: asOf, freshness: { status: "FRESH", ageSeconds, maxAgeSeconds: args.maxReadModelAgeSeconds }, areaId: args.areaId, availableCount: selected.length,
          vehicles: selectedPage.values.map((v) => ({ vehicleId: v.id, batteryPercent: v.batteryPercent, batteryMeasuredAt: v.lastBatteryAt, location: { latitude: v.latitude, longitude: v.longitude }, locationMeasuredAt: v.lastLocationAt, availableSince: null })), pagination: selectedPage.pagination };
      }
      const outputErrors = validate(result, spec.output);
      if (outputErrors.length) throw new ServiceError("INTERNAL_ERROR", `Tool output failed its contract: ${outputErrors[0]}`);
      return result;
    } catch (error) {
      const code = error.code ?? "READ_MODEL_UNAVAILABLE";
      const message = error.message ?? "Kafka fleet projection is unavailable";
      const result = { schemaVersion: "1.0", readModelAsOf: null, error: { code, message: message.slice(0, 500), retryable: error.retryable ?? true } };
      if (!validate(result, spec.output).length) return result;
      return { schemaVersion: "1.0", readModelAsOf: null, error: { code: "INTERNAL_ERROR", message: "Tool failed contract validation", retryable: true } };
    }
  }

  async function createProposal() {
    const toolArgs = { schemaVersion: "1.0", thresholdPercent: 29, pageSize: 100, maxReadModelAgeSeconds: 300 };
    const results = [];
    const candidates = [];
    let cursor;
    do {
      const result = await callTool("list_low_battery_vehicles", { ...toolArgs, ...(cursor ? { cursor } : {}) });
      if (result.error) throw new ServiceError(result.error.code, result.error.message, result.error.retryable);
      results.push(result);
      candidates.push(...result.vehicles.filter((v) => v.operationalStatus === "AVAILABLE").map((v) => ({ ...v, readModelAsOf: result.readModelAsOf })));
      cursor = result.pagination.nextCursor;
    } while (cursor && candidates.length < 5);
    const asOf = results[0].readModelAsOf;
    if (!candidates.length) throw new ServiceError("NO_ACTIONS", "No available vehicles under 30 percent battery; no proposal was created");
    const now = clock().toISOString();
    const chosen = candidates.slice(0, 5);
    const sources = results.map((result) => ({ toolName: "list_low_battery_vehicles", readModelAsOf: result.readModelAsOf, resultSha256: createHash("sha256").update(JSON.stringify(result)).digest("hex") }));
    const resultSha256 = createHash("sha256").update(JSON.stringify(sources)).digest("hex");
    const identitySeed = `${asOf}:${resultSha256}:mock-planner-v1`;
    const digest = (value) => createHash("sha256").update(value).digest("hex");
    const correlationId = `corr_${digest(`correlation:${identitySeed}`).slice(0, 24)}`;
    const runId = `run_${digest(`run:${identitySeed}`).slice(0, 24)}`;
    const proposalId = `proposal_${digest(`proposal:${identitySeed}`).slice(0, 24)}`;
    const actions = chosen.map((v) => ({ actionId: `action_${digest(`action:${proposalId}:${v.vehicleId}`).slice(0, 12)}`, type: "CHARGE", priority: v.batteryPercent <= 10 ? "HIGH" : "MEDIUM", vehicleId: v.vehicleId, currentAreaId: v.areaId, targetBatteryPercent: 80,
      rationale: `Available vehicle has ${v.batteryPercent}% battery in its mapped area. Charging is a recommendation for operator review.`,
      sourceEvidence: [{ toolName: "list_low_battery_vehicles", readModelAsOf: v.readModelAsOf, vehicleIds: [v.vehicleId], observation: `MCP low-battery tool reports ${v.batteryPercent}% battery and AVAILABLE status.` }], warnings: [] }));
    const proposal = { schemaVersion: "1.0", proposalId, runId, createdAt: now, status: "PENDING_APPROVAL", effectPolicy: "HUMAN_APPROVAL_REQUIRED", operationalStateChanged: false,
      snapshot: { snapshotId: `snapshot_${createHash("sha256").update(`${asOf}:${resultSha256}`).digest("hex").slice(0, 24)}`, readModelAsOf: asOf, sources },
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

  return { callTool, createProposal, decideProposal, getProposal: (id) => store.getProposal(id), listProposals: () => store.listProposals(), loadState, readiness: () => snapshotProvider.readiness() };
}
