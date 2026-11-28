import { appendFileSync, mkdirSync } from "node:fs";
import { randomBytes } from "node:crypto";
import path from "node:path";

export const PROJECT = "demo-do-cloud";
export const BASE = "http://127.0.0.1:5002";
export const FUNCTIONS = `http://127.0.0.1:5001/${PROJECT}/us-central1`;
export const AUTH = "http://127.0.0.1:9099";
export const FIRESTORE = "http://127.0.0.1:8080";
export const PUBSUB = "http://127.0.0.1:8085";
export const PACKAGE = "com.evansappstudio.doapp";

const OUTPUT = path.resolve(__dirname, "../../test-output");

/**
 * A reminder title that is unique to this run and is written to
 * test-output/titles.txt. After the run, scripts/check-logs.mjs fails the
 * build if any of them appears in the emulator's function logs (AT-9).
 */
export function title(words: string): string {
  const value = `${words} ${randomBytes(4).toString("hex")}`;
  mkdirSync(OUTPUT, { recursive: true });
  appendFileSync(path.join(OUTPUT, "titles.txt"), `${value}\n`);
  return value;
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Waits until [check] returns a value, or fails after [ms]. */
export async function eventually<T>(check: () => Promise<T | null | undefined | false>, ms = 15_000): Promise<T> {
  const end = Date.now() + ms;
  for (;;) {
    const value = await check();
    if (value) return value;
    if (Date.now() > end) throw new Error("Timed out waiting");
    await sleep(200);
  }
}
