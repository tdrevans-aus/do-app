// npm test: starts the Firebase Emulator Suite for the demo project, runs
// vitest inside it, keeps everything the emulators print (function logs
// included) in test-output/emulator.log, then checks that no reminder title
// used by the tests appears in those logs (AT-9).
import { spawn } from "node:child_process";
import { createWriteStream, mkdirSync, rmSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { checkLogs } from "./check-logs.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const functionsDir = path.resolve(here, "..");
const cloudDir = path.resolve(functionsDir, "..");
const output = path.join(functionsDir, "test-output");
rmSync(output, { recursive: true, force: true });
mkdirSync(output, { recursive: true });
const logPath = path.join(output, "emulator.log");
const log = createWriteStream(logPath);

const firebase = path.join(functionsDir, "node_modules", ".bin", "firebase");
const vitest = path.join(functionsDir, "node_modules", ".bin", "vitest");
const extra = process.argv.slice(2).join(" ");
const args = [
  "emulators:exec",
  "--project",
  "demo-do-cloud",
  "--only",
  "auth,firestore,functions,pubsub",
  `cd "${functionsDir}" && "${vitest}" run ${extra}`,
];

const child = spawn(firebase, args, { cwd: cloudDir, env: { ...process.env, FORCE_COLOR: "0" } });
for (const stream of [child.stdout, child.stderr]) {
  stream.on("data", (chunk) => {
    process.stdout.write(chunk);
    log.write(chunk);
  });
}
const code = await new Promise((resolve) => child.on("close", resolve));
await new Promise((resolve) => log.end(resolve));

const logResult = checkLogs(logPath, path.join(output, "titles.txt"));
console.log(logResult.message);
process.exit(code !== 0 ? code : logResult.ok ? 0 : 1);
