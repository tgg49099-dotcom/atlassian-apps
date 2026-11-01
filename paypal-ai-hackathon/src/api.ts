/**
 * The JSON API, implemented as a pure function of (url, method, body).
 *
 * The same handler is used by the Vite dev middleware (vite.config.ts) and
 * by the production server (src/server.ts), so the app behaves identically
 * in `npm run dev` and `npm start`.
 *
 * The API never returns an environment variable and never receives a secret
 * from the browser: credentials are read server-side only.
 */
import { analyzeDetailed } from './ai.ts';
import { readEnv, type EnvLike } from './env.ts';
import { fetchPayPalSnapshot } from './paypal.ts';
import type { Transaction } from './types.ts';

export interface ApiResponse {
  status: number;
  body: string;
  contentType: string;
}

const JSON_TYPE = 'application/json; charset=utf-8';
const MAX_BODY_BYTES = 1_000_000;

/** Sanitises a client-supplied transaction: only known fields survive. */
export function sanitizeTransaction(input: unknown): Transaction {
  const record = (typeof input === 'object' && input !== null ? input : {}) as Record<string, unknown>;
  const text = (value: unknown): string => (typeof value === 'string' ? value : typeof value === 'number' ? String(value) : '');
  const amount = typeof record.amount === 'number' && Number.isFinite(record.amount) ? record.amount : Number(text(record.amount)) || 0;
  const source = record.source === 'live' ? 'live' : 'demo';
  return {
    id: text(record.id),
    date: text(record.date),
    amount,
    currency: text(record.currency) || 'USD',
    eventCode: text(record.eventCode),
    kind: text(record.kind),
    counterparty: text(record.counterparty),
    source,
  };
}

function sanitizeTransactions(input: unknown): Transaction[] {
  if (!Array.isArray(input)) return [];
  return input.slice(0, 1000).map(sanitizeTransaction);
}

function json(payload: unknown, status = 200): ApiResponse {
  return { status, body: JSON.stringify(payload), contentType: JSON_TYPE };
}

/**
 * Returns `null` when the URL is not an API route (the caller then serves the
 * static files instead).
 */
export async function handleApi(
  url: URL,
  method: string,
  body: string,
  env: EnvLike = readEnv(),
): Promise<ApiResponse | null> {
  const path = url.pathname;

  if (path === '/api/health' && method === 'GET') {
    return json({ ok: true, paypalConfigured: Boolean(env.PAYPAL_CLIENT_ID && env.PAYPAL_SECRET) });
  }

  if (path === '/api/snapshot' && method === 'GET') {
    try {
      const snapshot = await fetchPayPalSnapshot(env);
      return json(snapshot);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      return json({ error: `Could not read PayPal: ${message}` }, 500);
    }
  }

  if (path === '/api/analyze' && method === 'POST') {
    if (body.length > MAX_BODY_BYTES) {
      return json({ error: 'request body too large' }, 413);
    }
    let parsed: unknown = {};
    try {
      parsed = body ? JSON.parse(body) : {};
    } catch {
      return json({ error: 'the request body is not valid JSON' }, 400);
    }
    const transactions = sanitizeTransactions((parsed as { transactions?: unknown }).transactions);
    const analysis = await analyzeDetailed(transactions, { env });
    return json(analysis);
  }

  return null;
}
