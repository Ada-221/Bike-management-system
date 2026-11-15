const manifestUrl = "/contracts/mcp-tools.json";
const schemasUrl = "/contracts/schemas/";
const proposalFile = "ai-proposal.schema.json";

const nav = document.querySelector("#schema-nav");
const message = document.querySelector("#message");
const content = document.querySelector("#content");
const loadedSchemas = new Map();
let entries = [];

function addText(parent, tag, className, value) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  element.textContent = value;
  parent.append(element);
  return element;
}

function safeFile(path) {
  const file = path.split("/").at(-1);
  if (!/^[a-z0-9-]+\.(input|output)\.schema\.json$/.test(file) && file !== proposalFile) {
    throw new Error(`Unexpected schema path in manifest: ${path}`);
  }
  return file;
}

async function fetchJson(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Could not load ${url} (${response.status})`);
  return response.json();
}

function makeEntry({ file, label, kind, toolName = "", description = "" }) {
  return { file, label, kind, toolName, description };
}

function renderNavigation() {
  nav.replaceChildren();
  for (const entry of entries) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `schema-nav-item${location.hash === `#${entry.file}` ? " active" : ""}`;
    button.dataset.file = entry.file;
    addText(button, "span", "nav-tag", entry.kind);
    button.append(document.createTextNode(entry.label));
    button.addEventListener("click", () => { location.hash = entry.file; });
    nav.append(button);
  }
  document.querySelector("#schema-count").textContent = String(entries.length).padStart(2, "0");
}

function resolveRef(ref, root) {
  if (!ref?.startsWith("#/")) return null;
  return ref.slice(2).split("/").map(part => part.replaceAll("~1", "/").replaceAll("~0", "~")).reduce((value, key) => value?.[key], root);
}

function typeName(schema) {
  if (schema.$ref) return schema.$ref.split("/").at(-1);
  if (schema.const !== undefined) return JSON.stringify(schema.const);
  if (schema.enum) return "enum";
  if (Array.isArray(schema.type)) return schema.type.join(" | ");
  return schema.type || (schema.oneOf ? "oneOf" : schema.anyOf ? "anyOf" : "schema");
}

function constraintText(schema) {
  const parts = [];
  for (const key of ["minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "minItems", "maxItems", "pattern", "format"]) {
    if (schema[key] !== undefined) parts.push(`${key}: ${schema[key]}`);
  }
  if (schema.additionalProperties === false) parts.push("no extra properties");
  return parts;
}

function fieldRow(name, schema, root, required, depth = 0, refStack = new Set()) {
  const row = document.createElement("div");
  row.className = "field";
  const top = addText(row, "div", "field-top", "");
  addText(top, "span", "field-name", name);
  addText(top, "span", required ? "required" : "optional", required ? "required" : "optional");
  addText(top, "span", "type", typeName(schema));
  if (schema.enum) addText(top, "span", "constraint", schema.enum.map(value => JSON.stringify(value)).join(" · "));
  if (schema.const !== undefined) addText(top, "span", "constraint", `fixed: ${JSON.stringify(schema.const)}`);
  for (const constraint of constraintText(schema)) addText(top, "span", "constraint", constraint);
  if (schema.description) addText(row, "p", "field-description", schema.description);

  let expanded = schema;
  if (schema.$ref && !refStack.has(schema.$ref)) {
    const target = resolveRef(schema.$ref, root);
    if (target) {
      refStack = new Set(refStack);
      refStack.add(schema.$ref);
      expanded = target;
    }
  }
  if (depth < 6 && expanded.properties) {
    const children = document.createElement("div");
    children.className = "field-children";
    const childRequired = new Set(expanded.required || []);
    for (const [key, child] of Object.entries(expanded.properties)) children.append(fieldRow(key, child, root, childRequired.has(key), depth + 1, refStack));
    row.append(children);
  } else if (expanded.type === "array" && expanded.items) {
    addText(row, "div", "array-note", `Items · ${typeName(expanded.items)}`);
    if (expanded.items.properties) {
      const children = document.createElement("div");
      children.className = "field-children";
      const childRequired = new Set(expanded.items.required || []);
      for (const [key, child] of Object.entries(expanded.items.properties)) children.append(fieldRow(key, child, root, childRequired.has(key), depth + 1, refStack));
      row.append(children);
    }
  }
  return row;
}

function renderSchema(entry, schema) {
  document.querySelectorAll(".schema-nav-item").forEach(button => button.classList.toggle("active", button.dataset.file === entry.file));
  message.hidden = true;
  content.hidden = false;
  document.querySelector("#schema-kind").textContent = entry.toolName ? `${entry.kind} · ${entry.toolName}` : entry.kind;
  document.querySelector("#schema-title").textContent = schema.title || entry.label;
  document.querySelector("#schema-file").textContent = entry.file;
  document.querySelector("#schema-description").textContent = entry.description;
  document.querySelector("#root-type").textContent = typeName(schema);
  const meta = document.querySelector("#schema-meta");
  meta.replaceChildren();
  for (const [label, value] of [["Draft", "2020-12"], ["Required fields", String(schema.required?.length ?? 0)], ["Properties", String(Object.keys(schema.properties || {}).length)]]) {
    const chip = document.createElement("span");
    chip.className = "meta-chip";
    addText(chip, "strong", "", `${label}:`);
    chip.append(document.createTextNode(` ${value}`));
    meta.append(chip);
  }
  const fields = document.querySelector("#fields");
  fields.replaceChildren();
  const properties = Object.entries(schema.properties || {});
  document.querySelector("#field-count").textContent = `${properties.length} top-level fields`;
  if (!properties.length) addText(fields, "div", "empty-fields", "This schema defines its shape through composition or definitions. Open the raw JSON below to inspect it.");
  const required = new Set(schema.required || []);
  for (const [name, field] of properties) fields.append(fieldRow(name, field, schema, required.has(name)));
  document.querySelector("#raw-json").textContent = JSON.stringify(schema, null, 2);
  const copy = document.querySelector("#copy-button");
  copy.textContent = "Copy JSON";
  copy.onclick = async () => {
    await navigator.clipboard.writeText(JSON.stringify(schema, null, 2));
    copy.textContent = "Copied";
    setTimeout(() => { copy.textContent = "Copy JSON"; }, 1400);
  };
}

async function openCurrent() {
  const file = decodeURIComponent(location.hash.slice(1));
  const entry = entries.find(candidate => candidate.file === file) || entries[0];
  if (!entry) return;
  if (!location.hash || location.hash !== `#${entry.file}`) history.replaceState(null, "", `#${entry.file}`);
  try {
    if (!loadedSchemas.has(entry.file)) loadedSchemas.set(entry.file, await fetchJson(`${schemasUrl}${encodeURIComponent(entry.file)}`));
    renderSchema(entry, loadedSchemas.get(entry.file));
  } catch (error) {
    content.hidden = true;
    message.hidden = false;
    message.textContent = `Schema could not be loaded: ${error.message}`;
  }
}

async function init() {
  try {
    const manifest = await fetchJson(manifestUrl);
    entries = manifest.tools.flatMap(tool => [
      makeEntry({ file: safeFile(tool.inputSchema), label: tool.name, kind: "INPUT", toolName: tool.name, description: tool.description }),
      makeEntry({ file: safeFile(tool.outputSchema), label: tool.name, kind: "OUTPUT", toolName: tool.name, description: tool.description }),
    ]);
    entries.push(makeEntry({ file: proposalFile, label: "ai-proposal", kind: "PROPOSAL" }));
    renderNavigation();
    await openCurrent();
  } catch (error) {
    message.hidden = false;
    message.textContent = `The AI/MCP contracts could not be loaded. ${error.message}`;
  }
}

window.addEventListener("hashchange", openCurrent);
init();
