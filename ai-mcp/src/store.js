import { mkdir, open, readFile, rename, unlink } from "node:fs/promises";
import path from "node:path";

// One process owns this directory for its entire lifetime, including rebuilds.
export async function acquireWriter(directory) {
  await mkdir(directory, { recursive: true });
  const file = path.resolve(directory, "writer.lock");
  let handle;
  try { handle = await open(file, "wx"); }
  catch (error) {
    if (error.code === "EEXIST") throw new Error(`State directory already locked: ${file}. Stop its writer; after a crash verify no writer remains before removing writer.lock.`);
    throw error;
  }
  await handle.writeFile(JSON.stringify({ pid: process.pid, startedAt: new Date().toISOString() }));
  await handle.close();
  return () => unlink(file);
}

export function createJsonStore(directory, { replace = rename } = {}) {
  const file = path.resolve(directory, "state.json");
  let pending = Promise.resolve();
  async function read() {
    try {
      const state = JSON.parse(await readFile(file, "utf8"));
      if (!state || typeof state.proposals !== "object" || !state.proposals || Array.isArray(state.proposals)) throw new Error("Invalid proposal store; restore state.json from backup");
      return state;
    } catch (error) {
      if (error.code === "ENOENT") return { proposals: {}, projection: null };
      throw error;
    }
  }
  async function update(mutator) {
    const operation = pending.then(async () => {
      const state = await read();
      mutator(state);
      await mkdir(path.dirname(file), { recursive: true });
      const temporary = `${file}.${process.pid}.tmp`;
      try {
        // Flush the new file before the atomic replacement; failed replacements
        // leave both the durable and in-memory projection at the old checkpoint.
        const handle = await open(temporary, "w");
        try { await handle.writeFile(JSON.stringify(state)); await handle.sync(); }
        finally { await handle.close(); }
        await replace(temporary, file);
      } finally { await unlink(temporary).catch((error) => { if (error.code !== "ENOENT") throw error; }); }
    });
    pending = operation.catch(() => {});
    return operation;
  }
  return {
    async loadProjection() { await pending; return (await read()).projection ?? null; },
    async saveProjection(projection) { await update((state) => { state.projection = projection; delete state.readModel; }); },
    async resetProjection() { await update((state) => { state.projection = null; delete state.readModel; }); },
    async saveProposal(proposal) { await update((state) => { state.proposals[proposal.proposalId] = proposal; }); },
    async getProposal(id) { await pending; return (await read()).proposals[id] ?? null; },
    async listProposals() { await pending; return Object.values((await read()).proposals).sort((a, b) => b.createdAt.localeCompare(a.createdAt)); },
  };
}
