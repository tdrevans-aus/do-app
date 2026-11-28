import { escapeHtml } from "../core/text";

// The authorise page: plain, accessible HTML with no scripts. Australian English.

const STYLE = `
  :root { color-scheme: light dark; }
  body { font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; margin: 0; padding: 24px 16px;
    background: Canvas; color: CanvasText; line-height: 1.5; }
  main { max-width: 26rem; margin: 0 auto; }
  h1 { font-size: 1.5rem; margin: 0 0 0.5rem; }
  .brand { font-weight: 700; letter-spacing: 0.02em; color: #2f6f4f; margin: 0 0 1.5rem; }
  label { display: block; font-weight: 600; margin: 1.25rem 0 0.25rem; }
  input[type=text] { font-size: 1.5rem; letter-spacing: 0.2em; text-transform: uppercase; width: 100%;
    box-sizing: border-box; padding: 0.5rem 0.75rem; border: 2px solid #767676; border-radius: 8px; }
  input[type=text]:focus { outline: 3px solid #2f6f4f; outline-offset: 2px; }
  input[aria-invalid=true] { border-color: #b3261e; }
  .hint { font-size: 0.95rem; margin: 0.25rem 0 0; }
  .error { color: #b3261e; font-weight: 600; margin: 1rem 0 0; }
  .actions { display: flex; gap: 0.75rem; margin-top: 1.5rem; flex-wrap: wrap; }
  button { font-size: 1rem; padding: 0.75rem 1.25rem; border-radius: 8px; border: 2px solid #2f6f4f; cursor: pointer; }
  button.primary { background: #2f6f4f; color: #fff; }
  button.secondary { background: transparent; color: inherit; border-color: #767676; }
  button:focus { outline: 3px solid #2f6f4f; outline-offset: 2px; }
  .small { font-size: 0.9rem; margin-top: 2rem; }
`;

function shell(title: string, body: string): string {
  return `<!doctype html>
<html lang="en-AU">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${escapeHtml(title)}</title>
<style>${STYLE}</style>
</head>
<body>
<main>
<p class="brand">Do</p>
${body}
</main>
</body>
</html>`;
}

export interface CodePageInput {
  requestId: string;
  clientName: string;
  returnHost: string;
  error?: string;
}

export function codePage(input: CodePageInput): string {
  const error = input.error
    ? `<p class="error" id="code-error" role="alert">${escapeHtml(input.error)}</p>`
    : "";
  const described = input.error ? "code-hint code-error" : "code-hint";
  return shell(
    "Connect to Do",
    `<h1>Connect ${escapeHtml(input.clientName)} to Do</h1>
<p>${escapeHtml(input.clientName)} wants to add reminders to Do on your phone, mark its own reminders done and hear what happens to them. It can't change your quiet hours, turn off nagging or touch reminders it didn't make.</p>
<form method="post" action="/oauth/authorize">
<input type="hidden" name="request_id" value="${escapeHtml(input.requestId)}">
<label for="code">Enter the code shown in Do</label>
<input type="text" id="code" name="code" inputmode="text" autocomplete="one-time-code" autocapitalize="characters"
  spellcheck="false" maxlength="12" required aria-describedby="${described}"${input.error ? ' aria-invalid="true"' : ""} autofocus>
<p class="hint" id="code-hint">Open Do, go to Assistants, choose Connect another assistant and type the 8-character code.</p>
${error}
<div class="actions">
<button type="submit" class="primary" name="action" value="approve">Connect</button>
<button type="submit" class="secondary" name="action" value="cancel" formnovalidate>Cancel</button>
</div>
</form>
<p class="small">After you connect, you'll go back to ${escapeHtml(input.returnHost)}. You can disconnect it at any time in Do, under Assistants.</p>`,
  );
}

export function messagePage(title: string, message: string): string {
  return shell(title, `<h1>${escapeHtml(title)}</h1>\n<p>${escapeHtml(message)}</p>`);
}
