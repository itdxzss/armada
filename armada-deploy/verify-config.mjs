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
expectIncludes(envExample, "APP_TITLE=第一套环境", ".env.example");
expectIncludes(
  envExample,
  "PROTOCOL_ANDROID_LIFECYCLE_COMMANDS_TOPIC=protocol.android.lifecycle.commands.v1",
  ".env.example"
);
expectIncludes(
  envExample,
  "PROTOCOL_ANDROID_MESSAGE_COMMANDS_TOPIC=protocol.android.message.commands.v1",
  ".env.example"
);
expectIncludes(
  envExample,
  "PROTOCOL_ANDROID_GROUP_JOIN_COMMANDS_TOPIC=protocol.android.group-join.commands.v1",
  ".env.example"
);
expectIncludes(
  envExample,
  "ARMADA_PROTOCOL_RESTART_MASTER_READY_URL=http://65.2.122.109:8080/readyz",
  ".env.example"
);
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
expectIncludes(
  compose,
  "APP_TITLE: ${APP_TITLE:-Wheel SaaS}",
  "docker-compose.rds.yml"
);
expectIncludes(
  compose,
  "PROTOCOL_ANDROID_LIFECYCLE_COMMANDS_TOPIC: ${PROTOCOL_ANDROID_LIFECYCLE_COMMANDS_TOPIC:-protocol.android.lifecycle.commands.v1}",
  "docker-compose.rds.yml"
);
expectIncludes(
  compose,
  "PROTOCOL_ANDROID_MESSAGE_COMMANDS_TOPIC: ${PROTOCOL_ANDROID_MESSAGE_COMMANDS_TOPIC:-protocol.android.message.commands.v1}",
  "docker-compose.rds.yml"
);
expectIncludes(
  compose,
  "PROTOCOL_ANDROID_GROUP_JOIN_COMMANDS_TOPIC: ${PROTOCOL_ANDROID_GROUP_JOIN_COMMANDS_TOPIC:-protocol.android.group-join.commands.v1}",
  "docker-compose.rds.yml"
);
expectIncludes(
  compose,
  "ARMADA_PROTOCOL_RESTART_MASTER_READY_URL: ${ARMADA_PROTOCOL_RESTART_MASTER_READY_URL:-http://65.2.122.109:8080/readyz}",
  "docker-compose.rds.yml"
);

console.log("armada deploy config verification passed");
