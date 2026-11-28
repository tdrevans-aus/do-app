import { settings } from "../core/config";
import { AgentFailure } from "../core/errors";
import { consume } from "../core/limiter";

// Per assistant and per device: a minute window and a day window (UTC). The
// same limits apply to free and paid users.

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;

export async function checkRateLimit(deviceUid: string, agentId: string, now: number): Promise<void> {
  const blocked = await consume(
    [
      { key: `a_${agentId}`, limit: settings.agentPerMinute(), sizeMs: MINUTE },
      { key: `a_${agentId}`, limit: settings.agentPerDay(), sizeMs: DAY },
      { key: `d_${deviceUid}`, limit: settings.devicePerMinute(), sizeMs: MINUTE },
      { key: `d_${deviceUid}`, limit: settings.devicePerDay(), sizeMs: DAY },
    ],
    now,
  );
  if (blocked > 0) throw new AgentFailure("rate_limited", `Too many requests. Try again in ${blocked} seconds.`, blocked);
}
