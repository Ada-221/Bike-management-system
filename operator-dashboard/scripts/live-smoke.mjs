import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));
const project = `frontend-week3-${Date.now()}-${process.pid}`;
const evidence = path.join(root, "test-results", project);
mkdirSync(evidence, { recursive: true });
const docker = process.platform === "win32" ? "docker.exe" : "docker";
const args = [
  "compose",
  "--project-name",
  project,
  "--file",
  path.join(root, "compose.smoke.yaml"),
  "--file",
  path.join(root, "compose.telemetry-smoke.yaml"),
];
function compose(command, capture = false) {
  const result = spawnSync(docker, [...args, ...command], {
    cwd: root,
    encoding: "utf8",
    stdio: capture ? "pipe" : "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0)
    throw new Error(
      `Docker Compose ${command[0]} failed (${result.status}). ${result.stderr ?? ""}`,
    );
  return result.stdout?.trim();
}

let succeeded = false;
try {
  console.log(
    `Starting isolated stack ${project}. Existing stacks and data are left intact.`,
  );
  compose(["--profile", "telemetry", "build"]);
  compose(["up", "--detach", "--wait", "--wait-timeout", "240"]);
  const address = compose(["port", "operator-dashboard", "80"], true);
  if (!/^127\.0\.0\.1:\d+$/.test(address))
    throw new Error(`Unexpected dashboard address: ${address}`);
  const url = `http://${address}`;
  console.log(`Testing real fleet service through dashboard proxy at ${url}`);
  const result = spawnSync(
    process.execPath,
    [
      path.join(root, "node_modules", "@playwright", "test", "cli.js"),
      "test",
      "--config",
      "playwright.live.config.ts",
      "--output",
      path.join(evidence, "browser"),
    ],
    {
      cwd: root,
      stdio: "inherit",
      env: {
        ...process.env,
        FLEET_LIVE_DASHBOARD_URL: url,
        FLEET_SMOKE_COMPOSE_ARGS: JSON.stringify(args),
      },
    },
  );
  if (result.error) throw result.error;
  if (result.status !== 0)
    throw new Error(`Live browser test failed (${result.status}).`);
  // Load the real Vite configuration and exercise its development proxy too.
  const previousTarget = process.env.FLEET_API_TARGET;
  let development;
  try {
    process.env.FLEET_API_TARGET = url;
    development = await createServer({
      root,
      configFile: path.join(root, "vite.config.ts"),
      server: { host: "127.0.0.1", port: 0 },
    });
  } finally {
    if (previousTarget === undefined) delete process.env.FLEET_API_TARGET;
    else process.env.FLEET_API_TARGET = previousTarget;
  }
  try {
    await development.listen();
    const address = development.httpServer.address();
    assert(address && typeof address !== "string");
    const response = await fetch(
      `http://127.0.0.1:${address.port}/api/v1/fleet/status`,
    );
    assert.equal(response.status, 200);
    assert.equal((await response.json()).totalVehicles, 2);
    console.log("Vite development proxy passed against the real fleet API.");
  } finally {
    await development.close();
  }
  succeeded = true;
} catch (error) {
  console.error(error);
  process.exitCode = 1;
} finally {
  const logs = spawnSync(docker, [...args, "logs", "--no-color"], {
    cwd: root,
    encoding: "utf8",
    maxBuffer: 20 * 1024 * 1024,
  });
  writeFileSync(path.join(evidence, "compose.log"), logs.stdout ?? "");
  writeFileSync(
    path.join(evidence, "result.txt"),
    `${succeeded ? "PASS" : "FAIL"}\nProject: ${project}\nDate: ${new Date().toISOString()}\n`,
  );
  try {
    compose(["down", "--volumes", "--remove-orphans"]);
  } catch (error) {
    console.error("Test-stack cleanup failed:", error);
    process.exitCode = 1;
  }
  console.log(`Evidence saved in ${path.relative(root, evidence)}`);
}
