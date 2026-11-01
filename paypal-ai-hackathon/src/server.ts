/**
 * Production server: zero dependency, Node's own http module.
 *
 *   npm run build   -> writes dist/
 *   npm start       -> serves dist/ and the JSON API on http://127.0.0.1:4173
 *
 * The PayPal secret and the AI key are read from the server environment only.
 * They are never sent to the browser: the front-end talks to /api/snapshot and
 * /api/analyze.
 */
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { handleApi } from './api.ts';

const here = fileURLToPath(new URL('.', import.meta.url));
const distDir = resolve(join(here, '..', 'dist'));
const port = Number(process.env.PORT ?? 4173);
const host = process.env.HOST ?? '127.0.0.1';

const CONTENT_TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.map': 'application/json; charset=utf-8',
};

async function serveFile(filePath: string): Promise<{ body: Buffer; type: string } | null> {
  const root = resolve(distDir);
  const target = resolve(normalize(filePath));
  // Path traversal guard: the resolved file must stay inside dist/.
  if (target !== root && !target.startsWith(root + sep)) return null;
  try {
    const info = await stat(target);
    if (!info.isFile()) return null;
    const body = await readFile(target);
    return { body, type: CONTENT_TYPES[extname(target).toLowerCase()] ?? 'application/octet-stream' };
  } catch {
    return null;
  }
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);

  if (url.pathname.startsWith('/api/')) {
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const chunk of req) {
      size += (chunk as Buffer).length;
      if (size > 1_000_000) {
        res.statusCode = 413;
        res.end('{"error":"request body too large"}');
        return;
      }
      chunks.push(chunk as Buffer);
    }
    const body = Buffer.concat(chunks).toString('utf8');
    const result = await handleApi(url, req.method ?? 'GET', body);
    if (!result) {
      res.statusCode = 404;
      res.setHeader('Content-Type', 'application/json; charset=utf-8');
      res.end('{"error":"unknown API route"}');
      return;
    }
    res.statusCode = result.status;
    res.setHeader('Content-Type', result.contentType);
    res.setHeader('Cache-Control', 'no-store');
    res.end(result.body);
    return;
  }

  const file = await serveFile(join(distDir, decodeURIComponent(url.pathname)));
  if (file) {
    res.statusCode = 200;
    res.setHeader('Content-Type', file.type);
    res.end(file.body);
    return;
  }

  // Single-page-app fallback.
  const index = await serveFile(join(distDir, 'index.html'));
  if (index) {
    res.statusCode = 200;
    res.setHeader('Content-Type', index.type);
    res.end(index.body);
    return;
  }

  res.statusCode = 404;
  res.setHeader('Content-Type', 'text/plain; charset=utf-8');
  res.end('dist/ not found — run `npm run build` first.');
});

server.listen(port, host, () => {
  console.log(`paypal-ai-hackathon -> http://${host}:${port}  (serving ${distDir})`);
});
