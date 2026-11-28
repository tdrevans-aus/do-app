import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { REGION } from "../src/core/config";
import * as functions from "../src/index";

// Every function, and the Hosting rewrites, use the one region constant (Sydney).

describe("region", () => {
  it("is australia-southeast1 for every exported function", () => {
    expect(REGION).toBe("australia-southeast1");
    const entries = Object.entries(functions);
    expect(entries.length).toBe(11);
    for (const [name, fn] of entries) {
      const endpoint = (fn as unknown as { __endpoint?: { region?: string[] } }).__endpoint;
      expect(endpoint?.region, name).toEqual([REGION]);
    }
  });

  it("matches the Hosting rewrites in firebase.json", () => {
    const config = JSON.parse(readFileSync(path.resolve(__dirname, "../../firebase.json"), "utf8"));
    const rewrites = config.hosting.rewrites as Array<{ function: { functionId: string; region: string } }>;
    expect(rewrites.length).toBeGreaterThan(0);
    for (const rewrite of rewrites) expect(rewrite.function).toEqual({ functionId: "api", region: REGION });
  });
});
