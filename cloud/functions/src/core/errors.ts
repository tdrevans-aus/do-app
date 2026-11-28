/** Result codes an assistant can see. They map one to one from Spec 12's AgentError (see CONTRACT.md). */
export const AGENT_ERROR_CODES = [
  "limit_reached",
  "not_found",
  "invalid_argument",
  "device_offline",
  "disabled",
  "rate_limited",
  "internal",
] as const;
export type AgentErrorCode = (typeof AGENT_ERROR_CODES)[number];

/** The codes the phone may report in completeRequest. */
export const PHONE_ERROR_CODES = ["limit_reached", "not_found", "invalid_argument", "disabled"] as const;
export type PhoneErrorCode = (typeof PHONE_ERROR_CODES)[number];

export const DEFAULT_MESSAGES: Record<AgentErrorCode, string> = {
  limit_reached:
    "Limit reached: the free version of Do keeps 5 active reminders. Ask the person to mark one done or get Do Unlimited, then try again.",
  not_found: "No reminder with that id was created by this assistant, or it is already done.",
  invalid_argument: "The request was not valid.",
  device_offline: "The phone did not pick up the request within 24 hours, so it was dropped.",
  disabled: "The person has turned off assistants in Do.",
  rate_limited: "Too many requests. Wait a little and try again.",
  internal: "Do Cloud had a problem. Try again later.",
};

export class AgentFailure extends Error {
  constructor(
    readonly code: AgentErrorCode,
    message?: string,
    readonly retryAfterSeconds?: number,
  ) {
    super(message ?? DEFAULT_MESSAGES[code]);
    this.name = "AgentFailure";
  }
}
