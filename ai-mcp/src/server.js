import { createServer } from "node:http";
import { createBackend, createJsonStore, createService, parseMap } from "./service.js";
import { manifest, toolSchemas } from "./contract.js";

function json(response, status, body) {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  response.end(JSON.stringify(body));
}

async function readBody(request) {
  let body = "";
  for await (const chunk of request) {
    body += chunk;
    if (body.length > 1_000_000) throw new Error("Request body exceeds 1 MB");
  }
  return JSON.parse(body || "{}");
}

function errorResult(id, code, message) {
  return { jsonrpc: "2.0", id: id ?? null, error: { code, message } };
}

export function createHttpServer(service) {
  return createServer(async (request, response) => {
  const url = new URL(request.url, `http://${request.headers.host ?? "localhost"}`);
  if (request.method === "GET" && ["/health", "/healthz"].includes(url.pathname)) return json(response, 200, { status: "UP", service: "cebp-mcp-server" });
  if (request.method === "GET" && url.pathname === "/ready") {
    try { await service.loadState(); return json(response, 200, { status: "READY" }); }
    catch { return json(response, 503, { status: "NOT_READY", dependency: "fleet-management-service" }); }
  }
  if (request.method === "GET" && url.pathname === "/api/v1/proposals") return json(response, 200, await service.listProposals());
  if (request.method === "GET" && url.pathname.startsWith("/api/v1/proposals/")) {
    const proposal = await service.getProposal(decodeURIComponent(url.pathname.split("/").at(-1)));
    return proposal ? json(response, 200, proposal) : json(response, 404, { code: "NOT_FOUND", message: "Proposal not found" });
  }
  if (request.method === "POST" && url.pathname === "/api/v1/planner/runs") {
    try { const proposal = await service.createProposal(); return json(response, 202, { status: "COMPLETED", proposal }); }
    catch (error) { return json(response, error.code === "NO_ACTIONS" ? 200 : 503, { status: "FAILED", error: { code: error.code ?? "READ_MODEL_UNAVAILABLE", message: error.message } }); }
  }
  const decision = url.pathname.match(/^\/api\/v1\/proposals\/([^/]+)\/(approve|reject)$/);
  if (request.method === "POST" && decision) {
    try {
      const body = await readBody(request);
      const updated = await service.decideProposal(decodeURIComponent(decision[1]), decision[2] === "approve" ? "APPROVED" : "REJECTED", body.operatorId, body.comment);
      if (!updated) return json(response, 404, { code: "NOT_FOUND", message: "Proposal not found" });
      if (updated.conflict) return json(response, 409, { code: "PROPOSAL_ALREADY_DECIDED", message: "Proposal already has the opposite decision" });
      return json(response, 200, updated);
    } catch (error) { return json(response, 400, { code: error.code ?? "VALIDATION_ERROR", message: error.message }); }
  }
  if (request.method !== "POST" || url.pathname !== "/mcp") return json(response, 404, { code: "NOT_FOUND", message: "Route not found" });
  const protocolHeader = request.headers["mcp-protocol-version"];
  if (protocolHeader && protocolHeader !== manifest.protocolVersion) return json(response, 400, errorResult(null, -32600, "Unsupported MCP protocol version"));
  let body;
  try { body = await readBody(request); }
  catch (error) { return json(response, 400, errorResult(null, -32700, error.message || "Invalid JSON body")); }
  if (body.jsonrpc !== "2.0" || !body.method) return json(response, 400, errorResult(body.id, -32600, "Invalid JSON-RPC request"));
  if (body._meta?.["io.modelcontextprotocol/protocolVersion"] !== manifest.protocolVersion) return json(response, 400, errorResult(body.id, -32600, "Protocol version must match the v1 profile"));
  if (body.method === "tools/list") {
    const tools = manifest.tools.map((tool) => ({ name: tool.name, description: tool.description, inputSchema: toolSchemas.get(tool.name).input, annotations: tool.annotations }));
    return json(response, 200, { jsonrpc: "2.0", id: body.id, result: { tools } });
  }
  if (body.method === "tools/call") {
    const name = body.params?.name;
    if (!toolSchemas.has(name)) return json(response, 200, errorResult(body.id, -32602, `Unknown MCP tool '${name}'`));
    const value = await service.callTool(name, body.params?.arguments ?? {});
    const result = { resultType: "complete", structuredContent: value, content: [{ type: "text", text: JSON.stringify(value) }], isError: Boolean(value.error), _meta: { "io.modelcontextprotocol/serverInfo": { name: "cebp-mcp-server", version: "1.0.0" } } };
    return json(response, 200, { jsonrpc: "2.0", id: body.id, result });
  }
  return json(response, 200, errorResult(body.id, -32601, `Method '${body.method}' is not supported`));
  });
}

if (process.argv[1] && new URL(import.meta.url).pathname.toLowerCase() === new URL(`file://${process.argv[1].replaceAll("\\", "/")}`).pathname.toLowerCase()) {
  const port = Number(process.env.PORT ?? 8083);
  const backendUrl = process.env.BACKEND_API_URL ?? "http://127.0.0.1:8080/api/v1/";
  const areaMap = parseMap(process.env.MCP_AREA_ZONE_MAP_JSON, "MCP_AREA_ZONE_MAP_JSON");
  const vehicleMap = parseMap(process.env.MCP_VEHICLE_ID_MAP_JSON, "MCP_VEHICLE_ID_MAP_JSON");
  const stateDir = process.env.MCP_STATE_DIR ?? "./data";
  const service = createService({ backend: createBackend(backendUrl), areaMap, vehicleMap, store: createJsonStore(stateDir) });
  createHttpServer(service).listen(port, "0.0.0.0", () => process.stdout.write(`cebp-mcp-server listening on 0.0.0.0:${port}\n`));
}
