import http from "node:http";
import { REGION } from "../../src/core/config";

// Stands in for Firebase Hosting in tests: every path on 127.0.0.1:5002 goes
// to the `api` function in the Functions emulator, as the rewrites in
// firebase.json do in production. (The Hosting emulator's own proxy honours
// HTTPS_PROXY but not NO_PROXY, so it can't be used behind a proxy.)

const TARGET = { host: "127.0.0.1", port: 5001, prefix: `/demo-do-cloud/${REGION}/api` };

export default async function setup(): Promise<() => Promise<void>> {
  const server = http.createServer((req, res) => {
    const upstream = http.request(
      {
        host: TARGET.host,
        port: TARGET.port,
        method: req.method,
        path: `${TARGET.prefix}${req.url ?? "/"}`,
        headers: { ...req.headers, host: `${TARGET.host}:${TARGET.port}` },
      },
      (up) => {
        res.writeHead(up.statusCode ?? 502, up.headers);
        up.pipe(res);
      },
    );
    upstream.on("error", () => {
      res.writeHead(502);
      res.end("front proxy error");
    });
    req.pipe(upstream);
  });
  await new Promise<void>((resolve) => server.listen(5002, "127.0.0.1", resolve));
  return () => new Promise<void>((resolve) => server.close(() => resolve()));
}
