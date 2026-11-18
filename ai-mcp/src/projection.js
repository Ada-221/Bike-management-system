import { IntegrityError, normalizeEvent, TOPICS, fingerprint, timeOrder } from "./events.js";

export const checkpointKey = (topic, partition) => `${topic}:${partition}`;
export const emptyProjection = () => ({ formatVersion: 1, contentRevision: 0, vehicles: {}, processed: {}, checkpoints: {}, confirmed: null });
const fail = (message) => { throw new IntegrityError(message); };
const newerMeasurement = (a, b) => !b || timeOrder(a.measuredAt) > timeOrder(b.measuredAt) || (timeOrder(a.measuredAt) === timeOrder(b.measuredAt) && (timeOrder(a.publishedAt) > timeOrder(b.publishedAt) || (timeOrder(a.publishedAt) === timeOrder(b.publishedAt) && a.eventId > b.eventId)));

function apply(state, e) {
  const record = state.vehicles[e.vehicleId] ??= { registered: false, data: { id: e.vehicleId }, order: {}, revisionClaims: {} };
  const p = e.payload;
  const measurement = (field, measuredAt, values, publishedAt = e.publishedAt) => {
    const order = { measuredAt, publishedAt, eventId: e.eventId };
    if (newerMeasurement(order, record.order[field])) {
      record.order[field] = order;
      Object.assign(record.data, values);
    }
  };
  if (e.eventType === "VehicleBatteryUpdated") measurement("battery", e.measuredAt, { batteryPercent: p.batteryPercent, lastBatteryAt: e.measuredAt });
  else if (e.eventType === "VehicleLocationUpdated") measurement("location", e.measuredAt, { latitude: p.latitude, longitude: p.longitude, lastLocationAt: e.measuredAt });
  else {
    // A revision can appear on multiple topics, but cannot disagree on a field.
    const claim = record.revisionClaims[e.stateRevision] ??= {};
    for (const field of ["status", "zoneId"]) {
      if (p[field] === undefined) continue;
      if (Object.hasOwn(claim, field) && claim[field] !== p[field]) fail(`Conflicting ${field} at stateRevision ${e.stateRevision} for ${e.vehicleId}`);
      claim[field] = p[field];
      if (record.order[field] === undefined || e.stateRevision > record.order[field]) {
        record.order[field] = e.stateRevision;
        record.data[field] = p[field];
      }
    }
    if (e.eventType === "VehicleRegistered") {
      record.registered = true;
      // At equal measurement time, a real measurement takes precedence over the registration baseline.
      const baseline = "0000-01-01T00:00:00Z";
      measurement("battery", p.batteryMeasuredAt, { batteryPercent: p.batteryPercent, lastBatteryAt: p.batteryMeasuredAt }, baseline);
      measurement("location", p.locationMeasuredAt, { latitude: p.latitude, longitude: p.longitude, lastLocationAt: p.locationMeasuredAt }, baseline);
    }
    if (e.eventType === "RentalEnded" && p.locationMeasuredAt !== undefined) {
      measurement("location", p.locationMeasuredAt, {
        latitude: p.endLatitude, longitude: p.endLongitude, lastLocationAt: p.locationMeasuredAt,
      });
    }
  }
}

export async function createProjection({ store, clock = () => new Date(), backendContract = "" }) {
  const loaded = await store.loadProjection();
  let state = loaded ?? emptyProjection();
  if (state.formatVersion !== 1 || !Number.isSafeInteger(state.contentRevision) || !state.vehicles || !state.processed || !state.checkpoints || !("confirmed" in state)) fail("Unsupported or corrupt projection format; use offline rebuild");
  // Detect accidental disk edits/corruption rather than serving unchecked data.
  if (loaded && state.checksum !== fingerprint({ ...state, checksum: undefined })) fail("Projection checksum mismatch; restore or rebuild");
  let pending = Promise.resolve();
  let fatal = null, connected = false, initialized = false, caughtUp = false, connectionEpoch = 0, diagnostic = "Consumer has not caught up";
  function queue(operation) {
    const result = pending.then(() => { if (fatal) throw fatal; return operation(); });
    pending = result.catch(() => {});
    return result;
  }
  async function persist(next) {
    next.checksum = fingerprint({ ...next, checksum: undefined });
    try { await store.saveProjection(next); }
    catch (error) { fatal = new IntegrityError(`Projection storage failed; checkpoint was not advanced: ${error.message}. Fix storage and restart.`); throw fatal; }
    state = next;
  }
  return {
    async process(record) {
      return queue(async () => {
        const { topic, partition, offset } = record;
        try {
          if (!TOPICS.includes(topic) || !Number.isInteger(partition) || partition < 0 || !/^\d+$/.test(offset)) fail("Invalid topic/partition/offset");
          const e = normalizeEvent(record, { backendContract });
          const key = checkpointKey(topic, partition);
          const next = structuredClone(state);
          if (e) {
            const previous = next.processed[e.eventId];
            if (previous && previous !== e.digest) fail(`Conflicting reuse of eventId ${e.eventId}`);
            if (!previous) {
              const before = fingerprint(next.vehicles[e.vehicleId]?.data ?? null);
              apply(next, e); next.processed[e.eventId] = e.digest;
              if (before !== fingerprint(next.vehicles[e.vehicleId].data)) next.contentRevision++;
            }
          }
          const position = (BigInt(offset) + 1n).toString();
          if (BigInt(position) > BigInt(next.checkpoints[key] ?? "0")) next.checkpoints[key] = position;
          await persist(next);
          return next.checkpoints[key];
        } catch (error) {
          fatal ??= new IntegrityError(`${topic}[${partition}] offset ${offset}: ${error.message}`);
          throw fatal;
        }
      });
    },
    // Bounds come from successful broker requests, including idle partitions.
    async confirm(bounds, { checkpointsBeforeProbe } = {}) {
      const epoch = connectionEpoch;
      return queue(async () => {
        if (epoch !== connectionEpoch) return false;
        const required = new Set(bounds.map((b) => checkpointKey(b.topic, b.partition)));
        if (TOPICS.some((topic) => !bounds.some((b) => b.topic === topic)) || Object.keys(state.checkpoints).some((k) => !required.has(k))) fail("Missing topic/partition history; restore original Kafka topics");
        for (const b of bounds) {
          const key = checkpointKey(b.topic, b.partition), next = state.checkpoints[key] ?? "0";
          // Events persisted while the broker request was in flight can be
          // beyond its captured high watermark. Compare truncation against the
          // checkpoint observed BEFORE that request, not these newer records.
          const before = checkpointsBeforeProbe?.[key] ?? (checkpointsBeforeProbe ? "0" : next);
          if (BigInt(b.low) > BigInt(next) || BigInt(before) > BigInt(b.high)) fail(`Required history unavailable at ${key}: durable next=${next}, broker range=${b.low}..${b.high}; restore retained history`);
        }
        connected = true;
        caughtUp = bounds.every((b) => BigInt(state.checkpoints[checkpointKey(b.topic, b.partition)] ?? "0") >= BigInt(b.high));
        if (!caughtUp) { diagnostic = "Consumer is catching up"; return false; }
        const incomplete = Object.entries(state.vehicles).filter(([, v]) => !v.registered).map(([id]) => id);
        if (incomplete.length) { diagnostic = `Missing VehicleRegistered for ${incomplete.slice(0, 5).join(", ")}; complete fleet snapshot unavailable`; return false; }
        const next = structuredClone(state);
        next.confirmed = { asOf: clock().toISOString(), contentRevision: next.contentRevision, vehicles: Object.values(next.vehicles).map((v) => v.data).sort((a, b) => a.id.localeCompare(b.id)) };
        await persist(next);
        initialized = true; diagnostic = "Caught up";
        return true;
      }).catch((error) => { fatal ??= error; throw error; });
    },
    disconnect(reason = "Kafka disconnected") { connectionEpoch++; connected = false; caughtUp = false; diagnostic = reason; },
    fail(error) { fatal ??= error; connected = false; },
    async snapshot() {
      await pending;
      if (fatal || !initialized || !state.confirmed || Object.values(state.vehicles).some((v) => !v.registered)) {
        const error = new Error(fatal?.message ?? diagnostic); error.code = "READ_MODEL_UNAVAILABLE"; error.retryable = !fatal; throw error;
      }
      return structuredClone(state.confirmed);
    },
    readiness() { return { ready: connected && initialized && caughtUp && !fatal && !Object.values(state.vehicles).some((v) => !v.registered), diagnostic: fatal?.message ?? diagnostic }; },
    async inspect() { await pending; return structuredClone(state); },
    get fatal() { return fatal; },
  };
}
