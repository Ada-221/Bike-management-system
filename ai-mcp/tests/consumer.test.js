import assert from "node:assert/strict";
import test from "node:test";
import { setTimeout as delay } from "node:timers/promises";
import { createKafkaRuntime, kafkaConfig } from "../src/consumer.js";
import { TOPICS } from "../src/events.js";
import { event, record, fixture } from "./helpers.js";

test("runtime disables auto commits, seeks durable offsets instead of group commits, checks idle partitions and retries broker faults", async (t) => {
  const f = await fixture(t);
  await f.projection.process(record(event(), "4"));
  let runs = [], seeks = [], connections = 0, checks = 0;
  let failConnect = true;
  const kafka = {
    admin() { return { connect: async () => { connections++; if (failConnect) { failConnect = false; throw new Error("transient broker fault"); } }, disconnect: async () => {}, fetchTopicOffsets: async (topic) => { checks++; return [{ partition: 0, low: "0", high: topic === "fleet.vehicle" ? "5" : "0" }]; } }; },
    consumer(options) {
      assert.equal(options.allowAutoTopicCreation, false);
      const listeners = {};
      const events = Object.fromEntries(["CRASH", "DISCONNECT", "REQUEST_TIMEOUT", "FETCH", "REBALANCING", "GROUP_JOIN"].map((e) => [e, e]));
      return { events, on: (name, callback) => { listeners[name] = callback; }, connect: async () => {}, disconnect: async () => {}, subscribe: async (args) => assert.deepEqual(args, { topics: TOPICS, fromBeginning: true }), seek: (args) => seeks.push(args),
        run: async (args) => { runs.push(args); listeners.GROUP_JOIN({ payload: { memberAssignment: Object.fromEntries(TOPICS.map((topic) => [topic, [0]])) } }); listeners.FETCH(); },
      };
    },
  };
  const runtime = createKafkaRuntime({ projection: f.projection, kafka, groupId: "test", checkIntervalMs: 10, report() {} });
  void runtime.start();
  try {
    const deadline = Date.now() + 4000;
    while (!f.projection.readiness().ready && Date.now() < deadline) await delay(10);
    assert.equal(f.projection.readiness().ready, true);
    assert.ok(connections >= 2); assert.ok(checks >= 6);
    assert.equal(runs[0].autoCommit, false); assert.equal(runs[0].eachBatchAutoResolve, false);
    assert.deepEqual(seeks, TOPICS.map((topic) => ({ topic, partition: 0, offset: topic === "fleet.vehicle" ? "5" : "0" })));
  } finally { await runtime.stop(); }
});

test("Kafka config requires a dedicated nonempty group and broker list", () => {
  assert.throws(() => kafkaConfig({ MCP_KAFKA_GROUP_ID: " " }));
  assert.throws(() => kafkaConfig({ MCP_KAFKA_BROKERS: "broker:9092," }));
  assert.deepEqual(kafkaConfig({ MCP_KAFKA_BROKERS: "one:9092,two:9092", MCP_KAFKA_GROUP_ID: "mcp" }), { brokers: ["one:9092", "two:9092"], groupId: "mcp" });
});
