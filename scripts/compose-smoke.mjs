// Unique project + fresh volumes. Cleanup is restricted to this invocation's project.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { validate } from '../ai-mcp/src/contract.js';

const root = fileURLToPath(new URL('../', import.meta.url));
const project = `week2-smoke-${Date.now()}-${process.pid}`;
const output = path.join(root, 'build', project);
mkdirSync(output, { recursive: true });
const override = path.join(output, 'override.yaml');
// No fixed host ports: smoke requests run within the private Compose network.
writeFileSync(override, `services:\n${['postgres', 'kafka', 'fleet-management-service', 'operator-dashboard', 'ai-mcp-schema-viewer', 'ai-mcp-service'].map(s => `  ${s}:\n    ports: !reset []${s === 'ai-mcp-service' ? '\n    environment:\n      BACKEND_API_URL: http://fleet-management-service:8080/api/v1/\n      MCP_AREA_ZONE_MAP_JSON: \'{"area_center":"zone_001","area_south":"zone_002"}\'' : ''}`).join('\n')}\n`);
const base = ['compose', '--project-name', project, '--env-file', '.env.example', '-f', 'compose.yaml', '-f', override];
let sequence = 0;
function compose(args, { allowFailure = false } = {}) {
  const result = spawnSync('docker', [...base, ...args], { cwd: root, encoding: 'utf8', maxBuffer: 30 * 1024 * 1024, timeout: 900_000 });
  writeFileSync(path.join(output, `${++sequence}-${args[0]}.log`), `${result.stdout ?? ''}\n${result.stderr ?? ''}\n${result.error ?? ''}`);
  if (!allowFailure) assert.equal(result.status, 0, `${args.join(' ')} failed; see ${output}\n${result.stderr}`);
  return result.stdout ?? '';
}
try {
  compose(['--profile', 'telemetry', 'config', '--quiet']);
  compose(['--profile', 'telemetry', 'build']);
  compose(['up', '-d', '--wait', '--wait-timeout', '240']);
  compose(['ps', '--all']);
  compose(['exec', '-T', 'ai-mcp-service', 'node', '-e', `Promise.all(['http://fleet-management-service:8080/api/v1/health','http://operator-dashboard/','http://ai-mcp-schema-viewer/','http://127.0.0.1:8083/health','http://127.0.0.1:8083/ready'].map(async u=>{const r=await fetch(u);if(!r.ok)throw Error(u+': '+r.status)})).catch(e=>{console.error(e);process.exit(1)})`]);
  compose(['cp', 'ai-mcp/scripts/backend-smoke.mjs', 'ai-mcp-service:/tmp/backend-smoke.mjs']);
  compose(['exec', '-T', 'ai-mcp-service', 'node', '/tmp/backend-smoke.mjs']);
  compose(['restart', 'ai-mcp-service']);
  compose(['up', '-d', '--wait', '--wait-timeout', '90', 'ai-mcp-service']);
  compose(['exec', '-T', 'ai-mcp-service', 'node', '/tmp/backend-smoke.mjs', 'verify']);
  // No simulator on normal startup. Explicit opt-in only for this fresh broker.
  compose(['--profile', 'telemetry', 'run', '-d', '--name', `${project}-simulator`, '-e', 'TELEMETRY_SIMULATOR_ENABLED=true', 'telemetry']);
  const records = compose(['exec', '-T', 'kafka', '/opt/kafka/bin/kafka-console-consumer.sh', '--bootstrap-server', 'kafka:9092', '--topic', 'fleet.vehicle', '--from-beginning', '--property', 'print.key=true', '--max-messages', '4', '--timeout-ms', '60000']);
  const schema = JSON.parse(readFileSync(path.join(root, 'telemetry.schema.json'), 'utf8'));
  const events = records.trim().split(/\r?\n/).map(line => {
    const tab = line.indexOf('\t');
    assert.ok(tab > 0, 'Kafka record must include its key');
    const event = JSON.parse(line.slice(tab + 1));
    assert.equal(line.slice(0, tab), event.vehicleId);
    assert.deepEqual(validate(event, schema), []);
    assert.match(event.eventId, /^evt_/);
    return event;
  });
  assert.equal(events.length, 4);
  assert.equal(new Set(events.map(e => e.eventId)).size, 4);
  assert.equal(events.filter(e => e.eventType === 'VehicleBatteryUpdated').length, 2);
  assert.equal(events.filter(e => e.eventType === 'VehicleLocationUpdated').length, 2);
  // Telemetry has no fleet consumer: even after publication, approved state is unchanged.
  compose(['exec', '-T', 'ai-mcp-service', 'node', '/tmp/backend-smoke.mjs', 'verify']);
  writeFileSync(path.join(output, 'result.txt'), 'PASS: combined readiness, backend/MCP semantics, restart persistence, keyed schema-valid telemetry; fleet unchanged.\n');
  console.log(`PASS ${project}; evidence: ${output}`);
} catch (error) {
  writeFileSync(path.join(output, 'result.txt'), `FAIL: ${error.stack}\n`);
  process.exitCode = 1;
  console.error(error);
} finally {
  compose(['logs', '--no-color'], { allowFailure: true });
  compose(['--profile', 'telemetry', 'down', '--volumes', '--remove-orphans'], { allowFailure: false });
}
