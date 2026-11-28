// MCP tool definitions. Deterministic order (the 2026-07-28 spec asks for it).

export const SERVER_INSTRUCTIONS =
  "Do is a reminder app that nags until a reminder is done. These tools reach the person's phone, " +
  "which decides: it applies the free limit, holds Loud or Alarm requests at Normal until the person " +
  "approves, and keeps quiet hours. You can only see and complete reminders you created. If a call " +
  "returns queued, the phone is offline: call get_events later for the result.";

export const TOOLS = [
  {
    name: "create_reminder",
    title: "Add a reminder",
    description:
      "Add a reminder to Do on the person's phone. Put what and when in text, in plain words, for example " +
      '"call Mum tomorrow 6pm"; Do reads the time from it. Give due_at instead to set an exact time. ' +
      "Returns done with the reminder, held_for_approval (made at Normal until the person allows Loud or Alarm), " +
      "queued (the phone is offline; check get_events), or an error such as limit_reached.",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string", maxLength: 500, description: 'What to be reminded about, and when, e.g. "put the bins out at 7pm".' },
        due_at: {
          type: "string",
          description: "Exact due time, ISO 8601 with an offset, e.g. 2026-12-01T18:00:00+08:00. When given, text is used as the title.",
        },
        repeat: { type: "string", description: 'How it repeats, e.g. "every day", "every Tuesday", "every month".' },
        alert_level: {
          type: "string",
          enum: ["normal", "loud", "alarm"],
          description: "normal (default), loud or alarm. Loud and alarm need the person's approval on the phone.",
        },
      },
      required: ["text"],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
  },
  {
    name: "complete_reminder",
    title: "Mark a reminder done",
    description: "Mark one of your own reminders done, by the reminder_id from create_reminder or list_reminders.",
    inputSchema: {
      type: "object",
      properties: { reminder_id: { type: "string", description: "The reminder_id of a reminder you created." } },
      required: ["reminder_id"],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  },
  {
    name: "list_reminders",
    title: "List your reminders",
    description: "List the reminders you created. Other reminders on the phone are private and never listed.",
    inputSchema: {
      type: "object",
      properties: {
        status: { type: "string", enum: ["active", "done", "all"], description: "active (default), done or all." },
        limit: { type: "integer", minimum: 1, maximum: 50, description: "At most this many, 10 by default." },
      },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  },
  {
    name: "get_events",
    title: "What happened to your reminders",
    description:
      "Events for reminders you created (created, fired, nagged, snoozed, done, deleted and more) and the results " +
      "of queued requests (type request_result). Pass next_cursor from the last call as since_cursor to get only new events.",
    inputSchema: {
      type: "object",
      properties: {
        since_cursor: { type: "string", description: "next_cursor from an earlier call. Leave out to start from the beginning." },
        limit: { type: "integer", minimum: 1, maximum: 100, description: "At most this many, 50 by default." },
      },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  },
] as const;
