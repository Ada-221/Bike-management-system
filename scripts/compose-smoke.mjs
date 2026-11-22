// Exercise real REST and telemetry producers with isolated Compose volumes.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const project = `week3-smoke-${Date.now()}-${process.pid}`;
const output = path.join(root, 'build', project);
mkdirSync(output, { recursive: true });
const override = path.join(output, 'override.yaml');
writeFileSync(override, `services:\n${['postgres', 'kafka', 'fleet-management-service', 'operator-dashboard', 'ai-mcp-schema-viewer', 'ai-mcp-service'].map(s => `  ${s}:\n    ports: !reset []${s === 'ai-mcp-service' ? '\n    environment:\n      MCP_BACKEND_EVENT_CONTRACT: fleet-week3-v1\n      MCP_AREA_ZONE_MAP_JSON: \'{"area_center":"zone_001","area_south":"zone_002"}\'' : ''}`).join('\n')}\n`);
const base = ['compose', '--project-name', project, '--env-file', '.env.example', '-f', 'compose.yaml', '-f', override];
let sequence = 0;
async function compose(args, { allowFailure = false } = {}) {
  const result = await new Promise((resolve) => {
    const child = spawn('docker', [...base, ...args], { cwd: root, windowsHide: true });
    let stdout = '', stderr = '';
    child.stdout.on('data', (data) => { stdout += data; });
    child.stderr.on('data', (data) => { stderr += data; });
    child.on('error', (error) => resolve({ status: -1, stdout, stderr: stderr + error.message }));
    child.on('close', (status) => resolve({ status, stdout, stderr }));
  });
  writeFileSync(path.join(output, `${++sequence}-${args[0]}.log`), `${result.stdout}\n${result.stderr}`);
  if (!allowFailure) assert.equal(result.status, 0, `${args.join(' ')} failed; see ${output}\n${result.stderr}`);
  return result.stdout;
}
const exercise = (mode) => compose(['exec', '-T', 'ai-mcp-service', 'node', 'scripts/kafka-smoke.mjs', mode]);
try {
  await compose(['--profile', 'telemetry', 'config', '--quiet']);
  console.log(`Building isolated ${project}; logs: ${output}`);
  await compose(['--profile', 'telemetry', 'build']);
  await compose(['up', '-d', '--wait', '--wait-timeout', '240']);
  await exercise('seed');
  const simulator = ['--profile', 'telemetry', 'run', '--rm', '-e', 'TELEMETRY_SIMULATOR_ENABLED=true',
    '-e', 'SPRING_MAIN_WEB_APPLICATION_TYPE=none'];
  await compose([...simulator, 'telemetry']);
  await exercise('rent');
  await compose([...simulator, '-e', 'TELEMETRY_SIMULATOR_SCENARIO=replay',
    '-e', 'TELEMETRY_SIMULATOR_REPLAY_AT=' + new Date().toISOString(), 'telemetry']);
  await compose([...simulator, '-e', 'TELEMETRY_SIMULATOR_SCENARIO=fault', 'telemetry']);
  await exercise('fault-return');
  await exercise('verify');
  console.log('Passed real rental, fault, and return; checking restart and broker outage.');
  await compose(['restart', 'ai-mcp-service']);
  await compose(['up', '-d', '--no-deps', '--wait', '--wait-timeout', '90', 'ai-mcp-service']);
  await exercise('verify');
  await compose(['stop', 'kafka']);
  await compose(['exec', '-T', 'ai-mcp-service', 'node', '-e', `setTimeout(async()=>{const r=await fetch('http://localhost:8083/ready');if(r.status!==503)process.exit(1);const h=await fetch('http://localhost:8083/health');if(!h.ok)process.exit(1)},15000)`]);
  await exercise('outage');
  await compose(['up', '-d', '--wait', '--wait-timeout', '90', 'kafka']);
  await compose(['up', '-d', '--no-deps', '--wait', '--wait-timeout', '90', 'ai-mcp-service']);
  await exercise('verify');
  await compose(['stop', 'fleet-management-service']);
  await exercise('verify');
  await compose(['stop', 'ai-mcp-service']);
  await compose(['run', '--rm', '--no-deps', 'ai-mcp-service', 'npm', 'run', 'rebuild']);
  await compose(['up', '-d', '--no-deps', '--wait', '--wait-timeout', '90', 'ai-mcp-service']);
  await exercise('verify');
  writeFileSync(path.join(output, 'result.txt'), 'PASS: real fleet REST and telemetry, duplicate and out-of-order replay, mid-trip fault and return, dashboard proxy, outbox recovery, backend-independent MCP, restart and rebuild, advisory approval preserved.\n');
  console.log(`PASS ${project}; evidence: ${output}`);
} catch (error) {
  writeFileSync(path.join(output, 'result.txt'), `FAIL: ${error.stack}\n`);
  process.exitCode = 1; console.error(error);
} finally {
  await compose(['logs', '--no-color'], { allowFailure: true });
  await compose(['--profile', 'telemetry', 'down', '--volumes', '--remove-orphans']);
}
