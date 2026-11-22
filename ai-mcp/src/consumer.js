import { setTimeout as delay } from "node:timers/promises";
import { Kafka, logLevel } from "kafkajs";
import { TOPICS, IntegrityError } from "./events.js";
import { checkpointKey } from "./projection.js";

export function kafkaConfig(env = process.env) {
  const brokers = (env.MCP_KAFKA_BROKERS ?? "localhost:29092").split(",").map((s) => s.trim());
  const groupId = env.MCP_KAFKA_GROUP_ID ?? "cebp-mcp-read-model-v1";
  if (brokers.some((b) => !b) || !groupId.trim()) throw new Error("MCP_KAFKA_BROKERS and MCP_KAFKA_GROUP_ID must be nonempty");
  return { brokers, groupId };
}

export async function fetchBounds(admin) {
  return (await Promise.all(TOPICS.map(async (topic) => (await admin.fetchTopicOffsets(topic)).map(({ partition, low, high }) => ({ topic, partition, low, high }))))).flat();
}

// Exported separately so persistence/commit failure windows use the exact same
// handler in focused tests and in the real Kafka adapter.
export async function consumeBatch({ batch, heartbeat, resolveOffset, isRunning, isStale }, { projection, consumer }) {
  for (const message of batch.messages) {
    if (!isRunning() || isStale()) return;
    const offset = await projection.process({ topic: batch.topic, partition: batch.partition, ...message });
    // This is intentionally after the atomic state.json replacement.
    await consumer.commitOffsets([{ topic: batch.topic, partition: batch.partition, offset }]);
    resolveOffset(message.offset);
    await heartbeat();
  }
}

export function createKafkaRuntime({ projection, brokers, groupId, kafka = new Kafka({ clientId: "cebp-mcp", brokers, logLevel: logLevel.WARN, retry: { retries: 3 }, requestTimeout: 5000 }), checkIntervalMs = 2000, report = (e) => console.error(e.message) }) {
  const abort = new AbortController();
  let task, activeConsumer;
  async function run() {
    while (!abort.signal.aborted && !projection.fatal) {
      const admin = kafka.admin();
      const consumer = kafka.consumer({ groupId, allowAutoTopicCreation: false, maxWaitTimeInMs: 1000, retry: { restartOnFailure: async () => false } });
      activeConsumer = consumer;
      let crash = null, assigned = false, consumerHealthy = false, assignmentTask = Promise.resolve();
      consumer.on(consumer.events.CRASH, ({ payload }) => { crash = payload.error; projection.disconnect(crash.message); });
      consumer.on(consumer.events.DISCONNECT, () => { assigned = false; projection.disconnect(); });
      consumer.on(consumer.events.REQUEST_TIMEOUT, () => { consumerHealthy = false; projection.disconnect("Kafka request timed out"); });
      consumer.on(consumer.events.FETCH, () => { consumerHealthy = true; });
      consumer.on(consumer.events.REBALANCING, () => { assigned = false; projection.disconnect("Consumer group is rebalancing"); });
      consumer.on(consumer.events.GROUP_JOIN, ({ payload }) => {
        assigned = false;
        projection.disconnect("Seeking durable checkpoints");
        assignmentTask = (async () => {
          const bounds = await fetchBounds(admin);
          const state = await projection.inspect();
          for (const b of bounds) {
            if (!payload.memberAssignment[b.topic]?.includes(b.partition)) throw new IntegrityError("MCP requires all topic partitions: use a dedicated group and one writer per state directory");
            const offset = state.checkpoints[checkpointKey(b.topic, b.partition)] ?? "0";
            if (BigInt(offset) < BigInt(b.low) || BigInt(offset) > BigInt(b.high)) throw new IntegrityError(`Required history unavailable for ${b.topic}[${b.partition}]: next=${offset}, broker=${b.low}..${b.high}`);
            consumer.seek({ topic: b.topic, partition: b.partition, offset });
          }
          assigned = true;
        })().catch((error) => {
          crash = error;
          if (error instanceof IntegrityError) projection.fail(error);
        });
      });
      try {
        await admin.connect();
        // Missing topics are errors; Compose's initializer creates them first.
        await fetchBounds(admin);
        await consumer.connect();
        await consumer.subscribe({ topics: TOPICS, fromBeginning: true });
        await consumer.run({ autoCommit: false, eachBatchAutoResolve: false, partitionsConsumedConcurrently: 1,
          eachBatch: async (context) => {
            await assignmentTask;
            if (crash) throw crash;
            if (!assigned) return;
            await consumeBatch(context, { projection, consumer });
          },
        });
        while (!abort.signal.aborted && !projection.fatal) {
          if (crash) throw crash;
          if (assigned && consumerHealthy) {
            const before = await projection.inspect();
            const bounds = await fetchBounds(admin);
            if (assigned && consumerHealthy && !crash && !abort.signal.aborted) await projection.confirm(bounds, { checkpointsBeforeProbe: before.checkpoints });
          }
          await delay(checkIntervalMs, undefined, { signal: abort.signal });
        }
      } catch (error) {
        projection.disconnect(error.message);
        if (error instanceof IntegrityError) projection.fail(error);
        if (!abort.signal.aborted) report(error);
      } finally {
        assigned = false;
        await Promise.allSettled([consumer.disconnect(), admin.disconnect()]);
        activeConsumer = null;
      }
      if (!abort.signal.aborted && !projection.fatal) await delay(1000, undefined, { signal: abort.signal }).catch(() => {});
    }
  }
  return {
    start() { task ??= run(); return task; },
    async stop() { abort.abort(); await activeConsumer?.stop?.(); await task; },
  };
}
