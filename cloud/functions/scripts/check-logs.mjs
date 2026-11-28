// AT-9: function logs during the tests never contain a reminder title.
// Every title the tests use is listed in test-output/titles.txt (see
// test/helpers/env.ts). The check also makes sure function logs were really
// captured, so it can't pass by reading an empty file.
import { existsSync, readFileSync } from "node:fs";

export function checkLogs(logPath, titlesPath) {
  if (!existsSync(logPath)) return { ok: false, message: "AT-9: no emulator log was captured" };
  const log = readFileSync(logPath, "utf8");
  const titles = existsSync(titlesPath)
    ? readFileSync(titlesPath, "utf8").split("\n").map((t) => t.trim()).filter((t) => t.length > 0)
    : [];
  if (titles.length === 0) return { ok: false, message: "AT-9: the tests recorded no titles" };
  const markers = ["do.inbox.created", "do.inbox.completed", "do.mirror.synced"];
  const missing = markers.filter((m) => !log.includes(m));
  if (missing.length > 0) return { ok: false, message: `AT-9: function logs were not captured (missing ${missing.join(", ")})` };
  const leaked = titles.filter((t) => log.includes(t) || log.includes(t.split(" ").slice(-1)[0] ?? t));
  if (leaked.length > 0) return { ok: false, message: `AT-9: ${leaked.length} reminder title(s) appeared in the function logs` };
  return { ok: true, message: `AT-9: checked ${titles.length} titles against ${log.length} characters of function logs: none found` };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const result = checkLogs(process.argv[2], process.argv[3]);
  console.log(result.message);
  process.exit(result.ok ? 0 : 1);
}
