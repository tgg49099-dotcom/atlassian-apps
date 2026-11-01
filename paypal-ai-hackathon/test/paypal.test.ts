import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import {
  DEMO_LABEL,
  basicAuthHeader,
  fetchPayPalSnapshot,
  kindForEventCode,
  parseBalances,
  parseTokenResponse,
  parseTransactions,
  parseWebhooks,
  resolveCredentials,
} from '../src/paypal.ts';

function fixture(name: string): unknown {
  return JSON.parse(readFileSync(new URL(`./fixtures/${name}`, import.meta.url), 'utf8'));
}

/* -------------------------------------------------------------------------- */
/* Parsing (no network involved)                                              */
/* -------------------------------------------------------------------------- */

test('parseTransactions reads the PayPal reporting payload', () => {
  const rows = parseTransactions(fixture('paypal-transactions.json'));

  assert.equal(rows.length, 3);

  assert.deepEqual(rows[0], {
    id: '0VF52815937971249',
    date: '2026-10-28T09:12:44Z',
    amount: 100,
    currency: 'USD',
    eventCode: 'T0006',
    kind: 'SALE',
    counterparty: 'buyer.one@example.com',
    source: 'live',
  });

  assert.equal(rows[1].kind, 'REFUND');
  assert.equal(rows[1].amount, -45.5);
  assert.equal(rows[1].currency, 'EUR');
  assert.equal(rows[2].kind, 'PAYOUT');
  assert.equal(rows[2].counterparty, 'payout.destination@example.com');
  assert.equal(rows[2].eventCode, 'T0004');
});

test('parseTransactions returns an empty array for a payload without transactions', () => {
  assert.deepEqual(parseTransactions({}), []);
  assert.deepEqual(parseTransactions(null), []);
  assert.deepEqual(parseTransactions({ transaction_resources: 'nope' }), []);
});

test('kindForEventCode keeps the raw code for unknown events', () => {
  assert.equal(kindForEventCode('T0006'), 'SALE');
  assert.equal(kindForEventCode('T0007'), 'REFUND');
  assert.equal(kindForEventCode('T0004'), 'PAYOUT');
  assert.equal(kindForEventCode('T9999'), 'OTHER(T9999)');
  assert.equal(kindForEventCode(''), 'UNKNOWN');
});

test('parseBalances and parseWebhooks read their payloads', () => {
  const balances = parseBalances(fixture('paypal-balances.json'));
  assert.deepEqual(balances, [
    { currency: 'USD', available: 18240.55, pending: 1240 },
    { currency: 'EUR', available: 2310.4, pending: 0 },
  ]);

  const webhooks = parseWebhooks(fixture('paypal-webhooks.json'));
  assert.equal(webhooks.length, 1);
  assert.equal(webhooks[0].url, 'https://example.invalid/paypal/webhook');
  assert.deepEqual(webhooks[0].eventTypes, ['PAYMENT.CAPTURE.COMPLETED', 'PAYMENT.CAPTURE.REFUNDED']);
});

test('parseTokenResponse extracts the token and refuses a payload without one', () => {
  assert.equal(parseTokenResponse({ access_token: 'abc123', expires_in: 32400 }), 'abc123');
  assert.throws(() => parseTokenResponse({ error: 'invalid_client' }), /no access_token/);
});

/* -------------------------------------------------------------------------- */
/* Credentials                                                                */
/* -------------------------------------------------------------------------- */

test('resolveCredentials needs BOTH variables', () => {
  assert.equal(resolveCredentials({}), null);
  assert.equal(resolveCredentials({ PAYPAL_CLIENT_ID: 'id' }), null);
  assert.equal(resolveCredentials({ PAYPAL_SECRET: 'secret' }), null);
  assert.equal(resolveCredentials({ PAYPAL_CLIENT_ID: '   ' }), null);
  assert.deepEqual(resolveCredentials({ PAYPAL_CLIENT_ID: 'id', PAYPAL_SECRET: 'secret' }), {
    clientId: 'id',
    secret: 'secret',
  });
});

test('basicAuthHeader is the base64 of "id:secret"', () => {
  const header = basicAuthHeader('client-id', 'client-secret');
  assert.ok(header.startsWith('Basic '));
  const decoded = Buffer.from(header.slice('Basic '.length), 'base64').toString('utf8');
  assert.equal(decoded, 'client-id:client-secret');
});

/* -------------------------------------------------------------------------- */
/* Demo mode (no credentials)                                                 */
/* -------------------------------------------------------------------------- */

test('fetchPayPalSnapshot falls back to the labelled demo mode without credentials', async () => {
  const snapshot = await fetchPayPalSnapshot({}, { now: () => new Date('2026-11-01T00:00:00Z') });

  assert.equal(snapshot.mode, 'demo');
  assert.equal(snapshot.label, DEMO_LABEL);
  assert.equal(snapshot.label, 'DEMO DATA — no PayPal credentials configured');
  assert.ok(snapshot.transactions.length > 0);
  assert.ok(snapshot.transactions.every((row) => row.source === 'demo'));
  assert.deepEqual(snapshot.errors, []);
  assert.equal(snapshot.fetchedAt, '2026-11-01T00:00:00.000Z');
});

/* -------------------------------------------------------------------------- */
/* Live mode, with an injected fetch (still no real network call)             */
/* -------------------------------------------------------------------------- */

test('fetchPayPalSnapshot asks for a token then reads three endpoints', async () => {
  const calls: { url: string; method: string; headers: Record<string, string>; body?: string }[] = [];

  const fakeFetch = (async (url: string, init: RequestInit) => {
    calls.push({
      url: String(url),
      method: String(init.method),
      headers: (init.headers ?? {}) as Record<string, string>,
      body: typeof init.body === 'string' ? init.body : undefined,
    });
    if (String(url).endsWith('/v1/oauth2/token')) {
      return new Response(JSON.stringify({ access_token: 'token-123', expires_in: 32400 }), { status: 200 });
    }
    if (String(url).includes('/v1/reporting/transactions')) {
      return new Response(JSON.stringify(fixture('paypal-transactions.json')), { status: 200 });
    }
    if (String(url).endsWith('/v1/reporting/balances')) {
      return new Response(JSON.stringify(fixture('paypal-balances.json')), { status: 200 });
    }
    return new Response(JSON.stringify(fixture('paypal-webhooks.json')), { status: 200 });
  }) as unknown as typeof fetch;

  const snapshot = await fetchPayPalSnapshot(
    { PAYPAL_CLIENT_ID: 'client-id', PAYPAL_SECRET: 'client-secret' },
    { fetchImpl: fakeFetch, now: () => new Date('2026-11-01T00:00:00Z') },
  );

  assert.equal(snapshot.mode, 'live');
  assert.equal(snapshot.label, 'LIVE — PayPal sandbox');
  assert.equal(snapshot.transactions.length, 3);
  assert.equal(snapshot.balances.length, 2);
  assert.equal(snapshot.webhooks.length, 1);
  assert.deepEqual(snapshot.errors, []);
  assert.ok(snapshot.transactions.every((row) => row.source === 'live'));

  assert.equal(calls.length, 4);
  assert.equal(calls[0].method, 'POST');
  assert.equal(calls[0].body, 'grant_type=client_credentials');
  assert.equal(calls[0].headers['Content-Type'], 'application/x-www-form-urlencoded');
  assert.ok(calls[0].headers.Authorization.startsWith('Basic '));
  assert.ok(calls[1].url.includes('/v1/reporting/transactions?start_date='));
  assert.equal(calls[1].headers.Authorization, 'Bearer token-123');
  assert.ok(calls[2].url.endsWith('/v1/reporting/balances'));
  assert.ok(calls[3].url.endsWith('/v1/notifications/webhooks'));
});

test('a failing PayPal call is reported and never replaced by fake rows', async () => {
  const fakeFetch = (async (url: string) => {
    if (String(url).endsWith('/v1/oauth2/token')) {
      return new Response(JSON.stringify({ access_token: 'token-123' }), { status: 200 });
    }
    return new Response('{"name":"INVALID_REQUEST"}', { status: 400 });
  }) as unknown as typeof fetch;

  const snapshot = await fetchPayPalSnapshot(
    { PAYPAL_CLIENT_ID: 'client-id', PAYPAL_SECRET: 'client-secret' },
    { fetchImpl: fakeFetch },
  );

  assert.equal(snapshot.mode, 'live');
  assert.deepEqual(snapshot.transactions, []);
  assert.equal(snapshot.errors.length, 3);
  assert.ok(snapshot.errors[0].includes('HTTP 400'));
  assert.ok(snapshot.errors[0].includes('INVALID_REQUEST'));
});

test('a failing token call is reported and never replaced by fake rows', async () => {
  const fakeFetch = (async () => new Response('{"error":"invalid_client"}', { status: 401 })) as unknown as typeof fetch;

  const snapshot = await fetchPayPalSnapshot(
    { PAYPAL_CLIENT_ID: 'client-id', PAYPAL_SECRET: 'client-secret' },
    { fetchImpl: fakeFetch },
  );

  assert.equal(snapshot.mode, 'live');
  assert.equal(snapshot.label, 'LIVE — PayPal sandbox (authentication failed)');
  assert.deepEqual(snapshot.transactions, []);
  assert.equal(snapshot.errors.length, 1);
  assert.ok(snapshot.errors[0].includes('HTTP 401'));
});
