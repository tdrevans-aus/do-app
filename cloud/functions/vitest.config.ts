import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["test/**/*.test.ts"],
    globalSetup: ["test/helpers/frontProxy.ts"],
    testTimeout: 90_000,
    hookTimeout: 60_000,
    fileParallelism: false,
    env: {
      GCLOUD_PROJECT: "demo-do-cloud",
      PUBLIC_BASE_URL: "http://127.0.0.1:5002",
      PLAY_CLIENT: "fake",
      PUSH_CLIENT: "record",
    },
  },
});
