import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
export const contractRoot = path.resolve(here, "../../contracts/ai-mcp/v1");
export const manifest = JSON.parse(await readFile(path.join(contractRoot, "mcp-tools.json"), "utf8"));
export const toolSchemas = new Map();
export const proposalSchema = JSON.parse(await readFile(path.join(contractRoot, "schemas/ai-proposal.schema.json"), "utf8"));

for (const tool of manifest.tools) {
  toolSchemas.set(tool.name, {
    ...tool,
    input: JSON.parse(await readFile(path.join(contractRoot, tool.inputSchema), "utf8")),
    output: JSON.parse(await readFile(path.join(contractRoot, tool.outputSchema), "utf8")),
  });
}

function same(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

function matchesType(value, type) {
  if (Array.isArray(type)) return type.some((candidate) => matchesType(value, candidate));
  if (type === "null") return value === null;
  if (type === "object") return value !== null && typeof value === "object" && !Array.isArray(value);
  if (type === "array") return Array.isArray(value);
  if (type === "integer") return Number.isInteger(value);
  if (type === "number") return typeof value === "number" && Number.isFinite(value);
  return typeof value === type;
}

export function validate(value, schema, root = schema, at = "$") {
  const errors = [];
  if (schema.$ref) {
    const target = schema.$ref.split("/").slice(1).reduce((node, key) => node?.[key.replaceAll("~1", "/").replaceAll("~0", "~")], root);
    return validate(value, target, root, at);
  }
  if (schema.const !== undefined && !same(value, schema.const)) errors.push(`${at} must equal ${JSON.stringify(schema.const)}`);
  if (schema.enum && !schema.enum.some((option) => same(option, value))) errors.push(`${at} must match an allowed value`);
  if (schema.type && !matchesType(value, schema.type)) return [...errors, `${at} must be ${Array.isArray(schema.type) ? schema.type.join(" or ") : schema.type}`];
  if (typeof value === "string") {
    if (schema.minLength !== undefined && value.length < schema.minLength) errors.push(`${at} is too short`);
    if (schema.maxLength !== undefined && value.length > schema.maxLength) errors.push(`${at} is too long`);
    if (schema.pattern && !new RegExp(schema.pattern).test(value)) errors.push(`${at} has an invalid format`);
    if (schema.format === "date-time" && (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z$/.test(value) || Number.isNaN(Date.parse(value)))) errors.push(`${at} must be a UTC date-time ending in Z`);
  }
  if (typeof value === "number") {
    if (schema.minimum !== undefined && value < schema.minimum) errors.push(`${at} is below its minimum`);
    if (schema.maximum !== undefined && value > schema.maximum) errors.push(`${at} is above its maximum`);
  }
  if (Array.isArray(value)) {
    if (schema.minItems !== undefined && value.length < schema.minItems) errors.push(`${at} has too few items`);
    if (schema.maxItems !== undefined && value.length > schema.maxItems) errors.push(`${at} has too many items`);
    if (schema.uniqueItems && new Set(value.map((item) => JSON.stringify(item))).size !== value.length) errors.push(`${at} must contain unique items`);
    value.forEach((item, index) => errors.push(...validate(item, schema.items ?? {}, root, `${at}[${index}]`)));
  }
  if (value !== null && typeof value === "object" && !Array.isArray(value)) {
    for (const key of schema.required ?? []) if (!(key in value)) errors.push(`${at}.${key} is required`);
    for (const [key, child] of Object.entries(value)) {
      if (schema.properties?.[key]) errors.push(...validate(child, schema.properties[key], root, `${at}.${key}`));
      else if (schema.additionalProperties === false) errors.push(`${at}.${key} is not allowed`);
      else if (schema.additionalProperties && typeof schema.additionalProperties === "object") errors.push(...validate(child, schema.additionalProperties, root, `${at}.${key}`));
    }
  }
  if (schema.oneOf && schema.oneOf.filter((option) => validate(value, option, root, at).length === 0).length !== 1) errors.push(`${at} must match exactly one allowed shape`);
  if (schema.anyOf && !schema.anyOf.some((option) => validate(value, option, root, at).length === 0)) errors.push(`${at} does not match an allowed shape`);
  for (const option of schema.allOf ?? []) {
    if (option.if) {
      const conditionMatched = validate(value, option.if, root, at).length === 0;
      if (conditionMatched && option.then) errors.push(...validate(value, option.then, root, at));
      if (!conditionMatched && option.else) errors.push(...validate(value, option.else, root, at));
    } else errors.push(...validate(value, option, root, at));
  }
  if (schema.not && validate(value, schema.not, root, at).length === 0) errors.push(`${at} matches a disallowed shape`);
  return errors;
}

export function validateProposal(proposal) {
  const errors = validate(proposal, proposalSchema);
  for (const action of proposal.actions ?? []) {
    if (action.type === "REDISTRIBUTE" && action.sourceAreaId === action.targetAreaId) errors.push("Redistribution source and target must differ");
  }
  return errors;
}
