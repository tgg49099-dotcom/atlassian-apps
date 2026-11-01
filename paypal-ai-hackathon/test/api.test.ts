import { test } from 'node:test';
import assert from 'node:assert/strict';

import { handleApi, sanitizeTransaction } from '../src/api.ts';

test('GET /api/snapshot returns the demo snapshot when no credentials are configured', async () => {
  const response = await handleApi(new URL('http://localhost/api/snapshot'), 'GET', '', {});

  assert.ok(response);
  assert.equal(response.status, 200);
  assert.match(response.contentType, /application\/json/);

  const payload = JSON.parse(response.body);
  assert.equal(payload.mode, 'demo');
  assert.equal(payload.label, 'DEMO DATA — no PayPal credentials configured');
  assert.ok(Array.isArray(payload.transactions));
  assert.ok(payload.transactions.every((row: { source: string }) => row.source === 'demo'));
});

test('POST /api/analyze returns a demo analysis and never leaks an environment variable', async () => {
  const response = await handleApi(
    new URL('http://localhost/api/analyze'),
    'POST',
    JSON.stringify({ transactions: [{ id: 'X', amount: 12.5, currency: 'USD', kind: 'SALE' }] }),
    { PAYPAL_CLIENT_ID: 'super-secret-client-id' },
  );

  assert.ok(response);
  assert.equal(response.status, 200);

  const payload = JSON.parse(response.body);
  assert.equal(payload.mode, 'demo');
  assert.ok(payload.insights.length >= 1);
  assert.ok(!response.body.includes('super-secret-client-id'));
  assert.ok(!response.body.includes('PAYPAL_CLIENT_ID'));
});

test('POST /api/analyze refuses a body that is not valid JSON', async () => {
  const response = await handleApi(new URL('http://localhost/api/analyze'), 'POST', 'not json', {});
  assert.ok(response);
  assert.equal(response.status, 400);
});

test('an unknown route is not an API route', async () => {
  assert.equal(await handleApi(new URL('http://localhost/index.html'), 'GET', '', {}), null);
  assert.equal(await handleApi(new URL('http://localhost/api/nope'), 'GET', '', {}), null);
});

test('GET /api/health reports whether PayPal is configured, without revealing it', async () => {
  const response = await handleApi(new URL('http://localhost/api/health'), 'GET', '', {
    PAYPAL_CLIENT_ID: 'id',
    PAYPAL_SECRET: 'secret',
  });
  assert.ok(response);
  const payload = JSON.parse(response.body);
  assert.equal(payload.ok, true);
  assert.equal(payload.paypalConfigured, true);
  assert.ok(!response.body.includes('secret'));
});

test('sanitizeTransaction keeps only the known fields and coerces the types', () => {
  const row = sanitizeTransaction({
    id: 'A',
    date: '2026-10-28T09:12:44Z',
    amount: '12.5',
    currency: 'EUR',
    kind: 'SALE',
    evil: 'dropped',
  });

  assert.deepEqual(row, {
    id: 'A',
    date: '2026-10-28T09:12:44Z',
    amount: 12.5,
    currency: 'EUR',
    eventCode: '',
    kind: 'SALE',
    counterparty: '',
    source: 'demo',
  });
});
