import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const deployDir = dirname(fileURLToPath(import.meta.url));

function read(file) {
  const path = join(deployDir, file);
  if (!existsSync(path)) {
    throw new Error(`missing deploy config: ${file}`);
  }
  return readFileSync(path, "utf8");
}

function expectIncludes(content, snippet, label) {
  if (!content.includes(snippet)) {
    throw new Error(`${label} missing: ${snippet}`);
  }
}

const envExample = read(".env.example");
const compose = read("docker-compose.rds.yml");

expectIncludes(
  envExample,
  "ARMADA_PROTOCOL_BASE_URL=http://65.2.122.109:8080",
  ".env.example"
);
expectIncludes(envExample, "ARMADA_PROTOCOL_API_KEY=", ".env.example");
expectIncludes(
  compose,
  "ARMADA_PROTOCOL_BASE_URL: ${ARMADA_PROTOCOL_BASE_URL:-http://65.2.122.109:8080}",
  "docker-compose.rds.yml"
);
expectIncludes(
  compose,
  "ARMADA_PROTOCOL_API_KEY: ${ARMADA_PROTOCOL_API_KEY:-}",
  "docker-compose.rds.yml"
);

console.log("armada deploy config verification passed");
