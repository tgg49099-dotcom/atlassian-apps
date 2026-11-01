/**
 * PayPal sandbox integration (read-only).
 *
 * What this module does:
 *   1. reads `PAYPAL_CLIENT_ID` / `PAYPAL_SECRET` from the environment it is
 *      given (never from a hard-coded value, never from the browser bundle);
 *   2. obtains an OAuth2 access token with the `client_credentials` grant;
 *   3. calls three READ endpoints:
 *        GET /v1/reporting/transactions   (transaction list)
 *        GET /v1/reporting/balances       (balances)
 *        GET /v1/notifications/webhooks   (connectivity probe)
 *   4. if no credentials are configured, returns a clearly-labelled demo
 *      snapshot instead — never a silent fallback.
 *
 * Every parsing function is pure and works on a plain JSON payload, so the
 * whole module is testable without any network call.
 */
import type { Balance, DataSource, PayPalSnapshot, Transaction, WebhookSummary } from './types.ts';
import { hasValue, type EnvLike } from './env.ts';

/** Exact banner shown when the app runs without PayPal credentials. */
export const DEMO_LABEL = 'DEMO DATA — no PayPal credentials configured';

/** Base URL of the PayPal sandbox. The production host is never used here. */
export const SANDBOX_BASE_URL = 'https://api-m.sandbox.paypal.com';

export interface PayPalCredentials {
  clientId: string;
  secret: string;
}

export interface FetchOptions {
  /** Injected in tests; defaults to the global `fetch`. */
  fetchImpl?: typeof fetch;
  /** Defaults to the sandbox base URL. */
  baseUrl?: string;
  /** Injected in tests to keep the output deterministic. */
  now?: () => Date;
}

/* -------------------------------------------------------------------------- */
/* Small, dependency-free helpers                                            */
/* -------------------------------------------------------------------------- */

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};
}

function str(value: unknown): string {
  if (typeof value === 'string') return value;
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  return '';
}

function num(value: unknown): number {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return 0;
}

function readArray(payload: unknown, key: string): unknown[] {
  const record = asRecord(payload);
  return Array.isArray(record[key]) ? (record[key] as unknown[]) : [];
}

function toBase64(input: string): string {
  const bytes = new TextEncoder().encode(input);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  const g = globalThis as unknown as {
    btoa?: (value: string) => string;
    Buffer?: { from(data: Uint8Array): { toString(encoding: string): string } };
  };
  if (typeof g.btoa === 'function') return g.btoa(binary);
  if (g.Buffer) return g.Buffer.from(bytes).toString('base64');
  throw new Error('no base64 implementation available');
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}

/* -------------------------------------------------------------------------- */
/* Credentials & authentication                                               */
/* -------------------------------------------------------------------------- */

/**
 * Returns the credentials when BOTH variables are present, otherwise `null`.
 * A partially configured environment is treated as "not configured" so that we
 * never attempt a call that is guaranteed to fail with a confusing message.
 */
export function resolveCredentials(env: EnvLike): PayPalCredentials | null {
  const clientId = env.PAYPAL_CLIENT_ID;
  const secret = env.PAYPAL_SECRET;
  if (!hasValue(clientId) || !hasValue(secret)) return null;
  return { clientId: clientId.trim(), secret: secret.trim() };
}

/** `Authorization` header value for the OAuth2 client-credentials grant. */
export function basicAuthHeader(clientId: string, secret: string): string {
  return `Basic ${toBase64(`${clientId}:${secret}`)}`;
}

/** Extracts the access token from an OAuth2 token response. Throws if absent. */
export function parseTokenResponse(payload: unknown): string {
  const record = asRecord(payload);
  const token = str(record.access_token);
  if (!token) {
    const keys = Object.keys(record);
    throw new Error(`PayPal OAuth response has no access_token (keys: ${keys.join(', ') || 'none'})`);
  }
  return token;
}

/* -------------------------------------------------------------------------- */
/* Parsing (pure)                                                             */
/* -------------------------------------------------------------------------- */

/**
 * PayPal event code -> transaction kind. Only the codes we are sure about are
 * mapped; the raw code is always kept on the row, so nothing is lost.
 */
export function kindForEventCode(eventCode: string): string {
  switch (eventCode) {
    case 'T0006':
      return 'SALE';
    case 'T0007':
      return 'REFUND';
    case 'T0004':
      return 'PAYOUT';
    default:
      return eventCode ? `OTHER(${eventCode})` : 'UNKNOWN';
  }
}

/** Parses a `/v1/reporting/transactions` payload into rows. */
export function parseTransactions(payload: unknown): Transaction[] {
  const rows: Transaction[] = [];
  for (const resource of readArray(payload, 'transaction_resources')) {
    const record = asRecord(resource);
    const info = asRecord(record.transaction_info);
    const other = asRecord(record.other_info);
    const payer = asRecord(record.payer_info);
    const receiver = asRecord(record.receiver_info);

    const eventCode = str(info.transaction_event_code);
    const id = str(info.transaction_id) || str(other.transaction_id);
    const date = str(info.transaction_initiation_date) || str(info.transaction_updated_date);
    const amount = num(info.total_amount !== undefined ? info.total_amount : info.net_amount);
    const currency = str(other.transaction_currency_code) || 'USD';
    const counterparty =
      str(payer.payer_email) ||
      str(receiver.receiver_email) ||
      str(payer.payer_id) ||
      str(receiver.receiver_id) ||
      '';

    rows.push({
      id,
      date,
      amount,
      currency,
      eventCode,
      kind: kindForEventCode(eventCode),
      counterparty,
      source: 'live',
    });
  }
  return rows;
}

/** Parses a `/v1/reporting/balances` payload. */
export function parseBalances(payload: unknown): Balance[] {
  const balances: Balance[] = [];
  for (const entry of readArray(payload, 'balances')) {
    const record = asRecord(entry);
    balances.push({
      currency: str(record.currency_code) || 'USD',
      available: num(record.available_balance),
      pending: num(record.pending_balance),
    });
  }
  return balances;
}

/** Parses a `/v1/notifications/webhooks` payload. */
export function parseWebhooks(payload: unknown): WebhookSummary[] {
  const webhooks: WebhookSummary[] = [];
  for (const entry of readArray(payload, 'webhooks')) {
    const record = asRecord(entry);
    const eventTypes = Array.isArray(record.event_types)
      ? (record.event_types as unknown[]).map((value) => str(value)).filter(Boolean)
      : [];
    webhooks.push({
      id: str(record.id),
      url: str(record.url),
      eventTypes,
    });
  }
  return webhooks;
}

/* -------------------------------------------------------------------------- */
/* Demo data (deterministic)                                                  */
/* -------------------------------------------------------------------------- */

interface DemoRow {
  id: string;
  date: string;
  amount: number;
  currency: string;
  eventCode: string;
  kind: string;
  counterparty: string;
}

const DEMO_ROWS: DemoRow[] = [
  { id: 'DEMO-0001', date: '2026-10-28T09:12:44Z', amount: 129.9, currency: 'USD', eventCode: '', kind: 'SALE', counterparty: 'buyer.one@example.com' },
  { id: 'DEMO-0002', date: '2026-10-28T10:03:01Z', amount: 45.5, currency: 'USD', eventCode: '', kind: 'SALE', counterparty: 'buyer.two@example.com' },
  { id: 'DEMO-0003', date: '2026-10-28T11:47:29Z', amount: 899.0, currency: 'USD', eventCode: '', kind: 'SALE', counterparty: 'buyer.three@example.com' },
  { id: 'DEMO-0004', date: '2026-10-27T08:21:10Z', amount: -129.9, currency: 'USD', eventCode: '', kind: 'REFUND', counterparty: 'buyer.one@example.com' },
  { id: 'DEMO-0005', date: '2026-10-27T14:02:55Z', amount: 62.25, currency: 'EUR', eventCode: '', kind: 'SALE', counterparty: 'buyer.four@example.com' },
  { id: 'DEMO-0006', date: '2026-10-26T16:38:02Z', amount: 1240.0, currency: 'USD', eventCode: '', kind: 'SALE', counterparty: 'buyer.five@example.com' },
  { id: 'DEMO-0007', date: '2026-10-26T17:00:31Z', amount: 18.0, currency: 'USD', eventCode: '', kind: 'SALE', counterparty: 'buyer.six@example.com' },
  { id: 'DEMO-0008', date: '2026-10-25T09:55:17Z', amount: -45.5, currency: 'USD', eventCode: '', kind: 'REFUND', counterparty: 'buyer.two@example.com' },
  { id: 'DEMO-0009', date: '2026-10-25T13:11:49Z', amount: 310.75, currency: 'USD', eventCode: '', kind: 'SALE', counterparty: 'buyer.seven@example.com' },
  { id: 'DEMO-0010', date: '2026-10-24T18:22:03Z', amount: 75.0, currency: 'EUR', eventCode: '', kind: 'SALE', counterparty: 'buyer.eight@example.com' },
  { id: 'DEMO-0011', date: '2026-10-24T19:04:38Z', amount: 2400.0, currency: 'USD', eventCode: '', kind: 'SALE', counterparty: 'buyer.five@example.com' },
  { id: 'DEMO-0012', date: '2026-10-23T07:48:26Z', amount: 99.99, currency: 'USD', eventCode: '', kind: 'SALE', counterparty: 'buyer.nine@example.com' },
];

/** Deterministic demo transactions. Always flagged as `source: 'demo'`. */
export function demoTransactions(): Transaction[] {
  return DEMO_ROWS.map((row) => ({ ...row, source: 'demo' as DataSource }));
}

/** Deterministic demo balances. */
export function demoBalances(): Balance[] {
  return [
    { currency: 'USD', available: 18240.55, pending: 1240.0 },
    { currency: 'EUR', available: 2310.4, pending: 0 },
  ];
}

/** Deterministic demo webhook list. */
export function demoWebhooks(): WebhookSummary[] {
  return [
    {
      id: 'DEMO-WEBHOOK-1',
      url: 'https://example.invalid/paypal/webhook',
      eventTypes: ['PAYMENT.CAPTURE.COMPLETED', 'PAYMENT.CAPTURE.REFUNDED'],
    },
  ];
}

/** The full demo snapshot. */
export function demoSnapshot(now: Date = new Date()): PayPalSnapshot {
  return {
    mode: 'demo',
    label: DEMO_LABEL,
    transactions: demoTransactions(),
    balances: demoBalances(),
    webhooks: demoWebhooks(),
    errors: [],
    fetchedAt: now.toISOString(),
  };
}

/* -------------------------------------------------------------------------- */
/* Live access                                                                */
/* -------------------------------------------------------------------------- */

function isoDay(date: Date): string {
  return date.toISOString().slice(0, 10);
}

/**
 * Reads the PayPal sandbox and returns a snapshot.
 *
 * - no credentials  -> the demo snapshot, clearly labelled;
 * - credentials but a failing call -> `mode: 'live'` with the verbatim error
 *   and NO fabricated rows (a broken live call must never look like data).
 */
export async function fetchPayPalSnapshot(
  env: EnvLike,
  options: FetchOptions = {},
): Promise<PayPalSnapshot> {
  const now = options.now ? options.now() : new Date();
  const credentials = resolveCredentials(env);
  if (!credentials) return demoSnapshot(now);

  const doFetch = options.fetchImpl ?? globalThis.fetch;
  const baseUrl = (options.baseUrl ?? SANDBOX_BASE_URL).replace(/\/+$/, '');
  const errors: string[] = [];

  // --- 1. OAuth2 access token (client-credentials grant) --------------------
  let accessToken = '';
  try {
    const response = await doFetch(`${baseUrl}/v1/oauth2/token`, {
      method: 'POST',
      headers: {
        Authorization: basicAuthHeader(credentials.clientId, credentials.secret),
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: 'grant_type=client_credentials',
    });
    const text = await response.text();
    if (!response.ok) {
      errors.push(`POST /v1/oauth2/token -> HTTP ${response.status} ${text.slice(0, 300)}`);
    } else {
      accessToken = parseTokenResponse(JSON.parse(text));
    }
  } catch (error) {
    errors.push(`POST /v1/oauth2/token -> ${errorMessage(error)}`);
  }

  if (!accessToken) {
    return {
      mode: 'live',
      label: 'LIVE — PayPal sandbox (authentication failed)',
      transactions: [],
      balances: [],
      webhooks: [],
      errors,
      fetchedAt: now.toISOString(),
    };
  }

  // --- 2. Read-only endpoints ------------------------------------------------
  const authHeaders = { Authorization: `Bearer ${accessToken}` };

  const readEndpoint = async (
    path: string,
    parse: (payload: unknown) => unknown,
  ): Promise<unknown> => {
    try {
      const response = await doFetch(`${baseUrl}${path}`, { method: 'GET', headers: authHeaders });
      const text = await response.text();
      if (!response.ok) {
        errors.push(`GET ${path} -> HTTP ${response.status} ${text.slice(0, 300)}`);
        return [];
      }
      return parse(JSON.parse(text));
    } catch (error) {
      errors.push(`GET ${path} -> ${errorMessage(error)}`);
      return [];
    }
  };

  const end = new Date(now.getTime());
  const start = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000);
  const transactionsPath =
    `/v1/reporting/transactions?start_date=${isoDay(start)}&end_date=${isoDay(end)}&page_size=100`;

  const transactions = (await readEndpoint(transactionsPath, parseTransactions)) as Transaction[];
  const balances = (await readEndpoint('/v1/reporting/balances', parseBalances)) as Balance[];
  const webhooks = (await readEndpoint('/v1/notifications/webhooks', parseWebhooks)) as WebhookSummary[];

  return {
    mode: 'live',
    label: 'LIVE — PayPal sandbox',
    transactions,
    balances,
    webhooks,
    errors,
    fetchedAt: now.toISOString(),
  };
}
