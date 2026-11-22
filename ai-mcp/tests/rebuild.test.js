import assert from "node:assert/strict";
import test from "node:test";
import { acquireWriter } from "../src/store.js";
import { rebuild, assertRebuildHistory } from "../scripts/rebuild.mjs";
import { TOPICS } from "../src/events.js";
import { fixture } from "./helpers.js";

test("offline rebuild requires complete retained topic history before clearing state", () => {
  const bounds = TOPICS.map((topic) => ({ topic, partition: 0, low: "0", high: "10" }));
  assert.doesNotThrow(() => assertRebuildHistory(bounds));
  assert.throws(() => assertRebuildHistory(bounds.map((b, i) => ({ ...b, low: i === 0 ? "2" : "0" }))), /Required history unavailable/);
  assert.throws(() => assertRebuildHistory(bounds.slice(1)), /Required history unavailable/);
});

test("offline rebuild refuses a running writer before touching any projection or proposal records", async (t) => {
  const f = await fixture(t);
  await f.store.saveProposal({ proposalId: "proposal_test", status: "APPROVED", operationalStateChanged: false });
  const before = await f.store.listProposals();
  const release = await acquireWriter(f.directory);
  try {
    await assert.rejects(rebuild({ directory: f.directory, config: { brokers: ["localhost:1"], groupId: "test" } }), /already locked/);
    assert.deepEqual(await f.store.listProposals(), before);
  } finally { await release(); }
});
