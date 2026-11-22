import { readdirSync } from "node:fs";
import { spawnSync } from "node:child_process";
for (const directory of ["src", "scripts", "tests"]) {
  for (const file of readdirSync(directory).filter((f) => /\.(mjs|js)$/.test(f))) {
    const result = spawnSync(process.execPath, ["--check", `${directory}/${file}`], { stdio: "inherit" });
    if (result.status !== 0) process.exit(result.status ?? 1);
  }
}
