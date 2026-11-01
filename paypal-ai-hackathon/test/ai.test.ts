import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  AI_DEMO_LABEL,
  analyze,
  analyzeDemo,
  analyzeDetailed,
  describeAiBackend,
  extractJsonArray,
  normaliseInsights,
} from '../src/ai.ts';
import type { Transaction } from '../src/types.ts';

const SAMPLE: Transaction[] = [
  {
    id: 'T1',
    date: '2026-10-28T09:12:44Z',
    amount: 100,
    currency: 'USD',
    eventCode: 'T0006',
    kind: 'SALE',
    counterparty: 'a@example.com',
    source: 'live',
  },
  {
    id: 'T2',
    date: '2026-10-27T09:12:44Z',
    amount: -100,
    currency: 'USD',
    eventCode: 'T0007',
    kind: 'REFUND',
    counterparty: 'a@example.com',
    source: 'live',
  },
  {
    id: 'T3',
    date: '2026-10-26T09:12:44Z',
    amount: 50,
    currency: 'EUR',
    eventCode: 'T0006',
    kind: 'SALE',
    counterparty: 'b@example.com',
    source: 'live',
  },
];

/* -------------------------------------------------------------------------- */
/* Backend detection                                                          */
/* -------------------------------------------------------------------------- */

test('describeAiBackend reports the demo mode when nothing is configured', () => {
  const backend = describeAiBackend({});
  assert.equal(backend.mode, 'demo');
  assert.equal(backend.provider, 'local');
  assert.equal(backend.label, AI_DEMO_LABEL);
  assert.equal(AI_DEMO_LABEL, 'DEMO MODE — no AI provider configured (local deterministic analysis)');
});

test('describeAiBackend needs both OPENAI_BASE_URL and OPENAI_API_KEY', () => {
  assert.equal(describeAiBackend({ OPENAI_API_KEY: 'k' }).mode, 'demo');
  assert.equal(describeAiBackend({ OPENAI_BASE_URL: 'https://example.invalid' }).mode, 'demo');

  const backend = describeAiBackend({
    OPENAI_BASE_URL: 'https://example.invalid/v1',
    OPENAI_API_KEY: 'k',
    OPENAI_MODEL: 'my-model',
  });
  assert.equal(backend.mode, 'live');
  assert.equal(backend.provider, 'openai-compatible');
  assert.equal(backend.model, 'my-model');
});

test('describeAiBackend falls back to Gemini when only GEMINI_API_KEY is set', () => {
  const backend = describeAiBackend({ GEMINI_API_KEY: 'k' });
  assert.equal(backend.mode, 'live');
  assert.equal(backend.provider, 'google-gemini');
  assert.equal(backend.model, 'gemini-2.0-flash');
});

/* -------------------------------------------------------------------------- */
/* Demo mode                                                                  */
/* -------------------------------------------------------------------------- */

test('analyze returns the labelled deterministic analysis when no provider is configured', async () => {
  const insights = await analyze(SAMPLE, { env: {} });

  assert.ok(insights.length >= 3);
  assert.ok(insights.every((insight) => insight.origin === 'local'));
  assert.ok(insights.every((insight) => insight.title.startsWith('DEMO —')));
  assert.ok(insights.some((insight) => /refund ratio/i.test(insight.title)));
  assert.ok(insights.some((insight) => /multi-currency/i.test(insight.title)));
});

test('analyze is deterministic: the same input gives the same output', async () => {
  const first = await analyze(SAMPLE, { env: {} });
  const second = await analyze(SAMPLE, { env: {} });
  assert.deepEqual(first, second);
});

test('analyze on an empty list still returns one clearly-labelled insight', async () => {
  const insights = await analyze([], { env: {} });
  assert.equal(insights.length, 1);
  assert.equal(insights[0].origin, 'local');
  assert.ok(insights[0].title.startsWith('DEMO —'));
  assert.equal(insights[0].metric, '0 transactions');
});

test('analyzeDemo never invents a number that is not in the input', () => {
  const insights = analyzeDemo(SAMPLE);
  const volume = insights.find((insight) => /total processed volume/i.test(insight.title));
  assert.ok(volume);
  assert.ok(volume.metric.includes('3 tx'));
  assert.ok(volume.metric.includes('50.00'));
});

/* -------------------------------------------------------------------------- */
/* Provider calls (injected fetch, no network)                                */
/* -------------------------------------------------------------------------- */

test('analyze calls the OpenAI-compatible endpoint and marks the insights as AI', async () => {
  const calls: { url: string; body: unknown }[] = [];

  const fakeFetch = (async (url: string, init: RequestInit) => {
    calls.push({ url: String(url), body: JSON.parse(String(init.body)) });
    const answer = JSON.stringify([
      { title: 'Volume concentrated on one buyer', detail: 'Buyer a drives most of the volume.', severity: 'warning', metric: '66%' },
      { title: 'Two currencies in play', detail: 'USD and EUR are both used.', severity: 'info', metric: '2' },
    ]);
    return new Response(
      JSON.stringify({ choices: [{ message: { content: answer } }] }),
      { status: 200 },
    );
  }) as unknown as typeof fetch;

  const insights = await analyze(SAMPLE, {
    env: { OPENAI_BASE_URL: 'https://example.invalid/v1', OPENAI_API_KEY: 'k' },
    fetchImpl: fakeFetch,
  });

  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, 'https://example.invalid/v1/chat/completions');
  assert.equal(insights.length, 2);
  assert.ok(insights.every((insight) => insight.origin === 'ai'));
  assert.equal(insights[0].severity, 'warning');
  assert.equal(insights[0].metric, '66%');
});

test('a failing provider falls back to the local analysis and reports the error', async () => {
  const fakeFetch = (async () => new Response('{"error":"boom"}', { status: 500 })) as unknown as typeof fetch;

  const analysis = await analyzeDetailed(SAMPLE, {
    env: { OPENAI_BASE_URL: 'https://example.invalid/v1', OPENAI_API_KEY: 'k' },
    fetchImpl: fakeFetch,
  });

  assert.equal(analysis.mode, 'demo');
  assert.equal(analysis.provider, 'local');
  assert.ok(analysis.error.includes('HTTP 500'));
  assert.ok(analysis.insights.every((insight) => insight.origin === 'local'));
  assert.ok(analysis.label.includes('DEMO MODE'));
});

/* -------------------------------------------------------------------------- */
/* Provider answer parsing                                                    */
/* -------------------------------------------------------------------------- */

test('extractJsonArray finds the array inside a chatty answer', () => {
  assert.deepEqual(extractJsonArray('Here you go: [{"title":"x"}] — done'), [{ title: 'x' }]);
  assert.throws(() => extractJsonArray('no array here'), /does not contain a JSON array/);
});

test('normaliseInsights drops entries without a title and clamps the severity', () => {
  const insights = normaliseInsights([
    { title: 'kept', detail: 'd', severity: 'nonsense', metric: '1' },
    { detail: 'no title' },
  ]);
  assert.equal(insights.length, 1);
  assert.equal(insights[0].severity, 'info');
  assert.equal(insights[0].origin, 'ai');
});
