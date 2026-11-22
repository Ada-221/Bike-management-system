import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { Kafka, logLevel } from "kafkajs";
import { TOPICS } from "../src/events.js";
import { acquireWriter, createJsonStore } from "../src/store.js";
import { createProjection } from "../src/projection.js";
import { createKafkaRuntime, fetchBounds, kafkaConfig } from "../src/consumer.js";

export function assertRebuildHistory(bounds) {
  if (TOPICS.some((topic) => !bounds.some((b) => b.topic === topic)) || bounds.some((b) => b.low !== "0")) throw new Error("Required history unavailable: rebuild requires all three topics and retained events from offset 0 on every partition. Restore history before rebuilding.");
}

export async function rebuild({ directory, backendContract, config, timeoutMs = 120000 }) {
  const release = await acquireWriter(directory);
  let runtime;
  try {
    const kafka = new Kafka({ clientId: "cebp-mcp-rebuild", brokers: config.brokers, logLevel: logLevel.WARN, requestTimeout: 5000 });
    const admin = kafka.admin();
    try {
      await admin.connect();
      const bounds = await fetchBounds(admin);
      assertRebuildHistory(bounds);
    } finally { await admin.disconnect(); }
    const store = createJsonStore(directory);
    await store.resetProjection(); // proposals survive; legacy REST cache is discarded.
    const projection = await createProjection({ store, backendContract });
    runtime = createKafkaRuntime({ projection, ...config, kafka });
    void runtime.start();
    const deadline = Date.now() + timeoutMs;
    while (!projection.readiness().ready) {
      if (projection.fatal) throw projection.fatal;
      if (Date.now() > deadline) throw new Error(`Rebuild did not complete: ${projection.readiness().diagnostic}. Check missing registrations, retained history and broker connectivity.`);
      await delay(100);
    }
    return (await projection.snapshot()).vehicles;
  } finally { await runtime?.stop(); await release(); }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const vehicles = await rebuild({ directory: process.env.MCP_STATE_DIR ?? "./data", backendContract: process.env.MCP_BACKEND_EVENT_CONTRACT, config: kafkaConfig() });
    console.log(`Rebuilt ${vehicles.length} vehicles from retained Kafka history; proposals preserved.`);
  } catch (error) { console.error(`REBUILD FAILED: ${error.message}`); process.exitCode = 1; }
}
