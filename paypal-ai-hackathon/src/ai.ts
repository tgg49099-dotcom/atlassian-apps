/**
 * Provider-independent AI adapter.
 *
 * Public interface:
 *
 *     analyze(transactions: Transaction[]): Promise<Insight[]>
 *
 * Behaviour:
 *   - `OPENAI_BASE_URL` + `OPENAI_API_KEY` present -> the OpenAI-compatible
 *     chat-completions endpoint is called;
 *   - else `GEMINI_API_KEY` present -> the Gemini endpoint is called;
 *   - else -> a deterministic, clearly-labelled local analysis.
 *
 * There is no hard-coded key, no key in the repository, and no key is ever
 * sent to the browser: the browser bundle only sees `import.meta.env`, and
 * Vite does not expose non-`VITE_` variables.
 */
import type { Transaction } from './types.ts';
import { hasValue, readEnv, type EnvLike } from './env.ts';

export type InsightSeverity = 'info' | 'warning' | 'critical';

/** Where an insight comes from: an AI provider, or the local fallback. */
export type InsightOrigin = 'ai' | 'local';

export interface Insight {
  id: string;
  title: string;
  detail: string;
  severity: InsightSeverity;
  /** One number that backs the insight, already formatted. */
  metric: string;
  origin: InsightOrigin;
}

export interface AiBackendInfo {
  /** 'live' means an AI provider was actually called. */
  mode: 'live' | 'demo';
  provider: string;
  model: string;
  label: string;
}

export interface AiAnalysis extends AiBackendInfo {
  insights: Insight[];
  /** Verbatim error when the provider call failed. Empty otherwise. */
  error: string;
}

export const AI_DEMO_LABEL = 'DEMO MODE — no AI provider configured (local deterministic analysis)';

const OPENAI_DEFAULT_MODEL = 'gpt-4o-mini';
const GEMINI_DEFAULT_MODEL = 'gemini-2.0-flash';

/* -------------------------------------------------------------------------- */
/* Backend detection                                                          */
/* -------------------------------------------------------------------------- */

/** Decides which AI backend the given environment allows. Pure. */
export function describeAiBackend(env: EnvLike): AiBackendInfo {
  if (hasValue(env.OPENAI_BASE_URL) && hasValue(env.OPENAI_API_KEY)) {
    return {
      mode: 'live',
      provider: 'openai-compatible',
      model: hasValue(env.OPENAI_MODEL) ? env.OPENAI_MODEL.trim() : OPENAI_DEFAULT_MODEL,
      label: `LIVE — AI provider: OpenAI-compatible (${hasValue(env.OPENAI_MODEL) ? env.OPENAI_MODEL.trim() : OPENAI_DEFAULT_MODEL})`,
    };
  }
  if (hasValue(env.GEMINI_API_KEY)) {
    return {
      mode: 'live',
      provider: 'google-gemini',
      model: hasValue(env.GEMINI_MODEL) ? env.GEMINI_MODEL.trim() : GEMINI_DEFAULT_MODEL,
      label: `LIVE — AI provider: Google Gemini (${hasValue(env.GEMINI_MODEL) ? env.GEMINI_MODEL.trim() : GEMINI_DEFAULT_MODEL})`,
    };
  }
  return {
    mode: 'demo',
    provider: 'local',
    model: 'deterministic-rules',
    label: AI_DEMO_LABEL,
  };
}

/* -------------------------------------------------------------------------- */
/* Local deterministic analysis                                               */
/* -------------------------------------------------------------------------- */

function sum(values: number[]): number {
  return values.reduce((acc, value) => acc + value, 0);
}

function groupBy(values: string[]): Map<string, number> {
  const map = new Map<string, number>();
  for (const value of values) map.set(value, (map.get(value) ?? 0) + 1);
  return map;
}

/**
 * The deterministic fallback analysis. Same input -> same output, always.
 * Used when no AI provider is configured, and when a provider call fails.
 */
export function analyzeDemo(transactions: Transaction[], note = ''): Insight[] {
  const insights: Insight[] = [];
  const suffix = note ? ` (${note})` : '';

  if (transactions.length === 0) {
    insights.push({
      id: 'local-1',
      title: 'DEMO — nothing to analyse',
      detail: `The transaction list is empty, so no insight can be computed.${suffix}`,
      severity: 'info',
      metric: '0 transactions',
      origin: 'local',
    });
    return insights;
  }

  const amounts = transactions.map((t) => t.amount);
  const volume = sum(amounts);
  const inflows = transactions.filter((t) => t.amount > 0);
  const outflows = transactions.filter((t) => t.amount < 0);
  const inflowVolume = sum(inflows.map((t) => t.amount));
  const outflowVolume = Math.abs(sum(outflows.map((t) => t.amount)));

  insights.push({
    id: 'local-1',
    title: 'DEMO — total processed volume',
    detail:
      transactions.length === 1
        ? `1 transaction was processed, for a signed total of ${round2(volume)}.${suffix}`
        : `${transactions.length} transactions were processed, for a signed total of ${round2(volume)}.${suffix}`,
    severity: 'info',
    metric: `${transactions.length} tx / ${round2(volume)}`,
    origin: 'local',
  });

  const largest = transactions.reduce((best, current) => (current.amount > best.amount ? current : best), transactions[0]);
  insights.push({
    id: 'local-2',
    title: 'DEMO — largest single transaction',
    detail: `The biggest movement is ${largest.id} for ${round2(largest.amount)} ${largest.currency}${
      largest.counterparty ? ` with ${largest.counterparty}` : ''
    }.${suffix}`,
    severity: largest.amount > volume * 0.5 && volume > 0 ? 'warning' : 'info',
    metric: `${round2(largest.amount)} ${largest.currency}`,
    origin: 'local',
  });

  if (inflows.length > 0 && outflows.length > 0) {
    const refundRatio = outflowVolume / inflowVolume;
    insights.push({
      id: 'local-3',
      title: 'DEMO — refund ratio',
      detail: `Refunds and payouts represent ${(refundRatio * 100).toFixed(1)}% of the incoming volume (${round2(outflowVolume)} out of ${round2(inflowVolume)}).${suffix}`,
      severity: refundRatio > 0.2 ? 'warning' : 'info',
      metric: `${(refundRatio * 100).toFixed(1)}%`,
      origin: 'local',
    });
  }

  const byCurrency = groupBy(transactions.map((t) => t.currency));
  if (byCurrency.size > 1) {
    const parts = [...byCurrency.entries()].map(([currency, count]) => `${currency}: ${count}`).join(', ');
    insights.push({
      id: 'local-4',
      title: 'DEMO — multi-currency activity',
      detail: `The transaction list mixes ${byCurrency.size} currencies (${parts}).${suffix}`,
      severity: 'info',
      metric: `${byCurrency.size} currencies`,
      origin: 'local',
    });
  }

  const byCounterparty = new Map<string, number>();
  for (const transaction of inflows) {
    if (!transaction.counterparty) continue;
    byCounterparty.set(transaction.counterparty, (byCounterparty.get(transaction.counterparty) ?? 0) + transaction.amount);
  }
  if (byCounterparty.size > 0 && inflowVolume > 0) {
    let topName = '';
    let topAmount = 0;
    for (const [name, amount] of byCounterparty) {
      if (amount > topAmount) {
        topName = name;
        topAmount = amount;
      }
    }
    const share = topAmount / inflowVolume;
    insights.push({
      id: 'local-5',
      title: 'DEMO — counterparty concentration',
      detail: `${topName || 'the top counterparty'} accounts for ${(share * 100).toFixed(1)}% of the incoming volume.${suffix}`,
      severity: share > 0.5 ? 'warning' : 'info',
      metric: `${(share * 100).toFixed(1)}%`,
      origin: 'local',
    });
  }

  return insights;
}

function round2(value: number): string {
  return value.toFixed(2);
}

/* -------------------------------------------------------------------------- */
/* Provider calls                                                             */
/* -------------------------------------------------------------------------- */

const SYSTEM_PROMPT = [
  'You are an analyst for a PayPal merchant dashboard.',
  'You are given a JSON array of transactions, each with id, date, amount, currency, kind and counterparty.',
  'Return STRICTLY a JSON array of objects, and nothing else, with the keys:',
  '  title    (string, max 80 characters, specific and factual)',
  '  detail   (string, one or two sentences, factual, no invented numbers)',
  '  severity (one of "info", "warning", "critical")',
  '  metric   (string, one short number or quantity, already formatted)',
  'Return between 3 and 6 insights. Never invent a transaction that is not in the input.',
].join(' ');

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};
}

function str(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

function severityOf(value: unknown): InsightSeverity {
  const raw = str(value).toLowerCase();
  if (raw === 'critical' || raw === 'warning' || raw === 'info') return raw;
  return 'info';
}

/** Extracts the first JSON array found in a model answer. */
export function extractJsonArray(text: string): unknown[] {
  const start = text.indexOf('[');
  const end = text.lastIndexOf(']');
  if (start === -1 || end === -1 || end < start) {
    throw new Error('the provider answer does not contain a JSON array');
  }
  const parsed = JSON.parse(text.slice(start, end + 1));
  if (!Array.isArray(parsed)) throw new Error('the provider answer is not a JSON array');
  return parsed;
}

/** Normalises a raw provider answer into `Insight[]`. */
export function normaliseInsights(raw: unknown[]): Insight[] {
  const insights: Insight[] = [];
  raw.forEach((entry, index) => {
    const record = asRecord(entry);
    const title = str(record.title);
    const detail = str(record.detail);
    if (!title) return;
    insights.push({
      id: `ai-${index + 1}`,
      title,
      detail: detail || '(no detail supplied by the provider)',
      severity: severityOf(record.severity),
      metric: str(record.metric),
      origin: 'ai',
    });
  });
  return insights;
}

function openAiChatUrl(baseUrl: string): string {
  const trimmed = baseUrl.replace(/\/+$/, '');
  return /\/v\d+$/.test(trimmed) ? `${trimmed}/chat/completions` : `${trimmed}/v1/chat/completions`;
}

export interface ProviderOptions {
  fetchImpl?: typeof fetch;
  timeoutMs?: number;
}

async function callOpenAiCompatible(
  env: EnvLike,
  transactions: Transaction[],
  options: ProviderOptions,
): Promise<Insight[]> {
  const doFetch = options.fetchImpl ?? globalThis.fetch;
  const baseUrl = openAiChatUrl(env.OPENAI_BASE_URL as string);
  const model = hasValue(env.OPENAI_MODEL) ? env.OPENAI_MODEL.trim() : OPENAI_DEFAULT_MODEL;

  const response = await doFetch(baseUrl, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${env.OPENAI_API_KEY}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model,
      temperature: 0,
      response_format: { type: 'json_object' },
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        { role: 'user', content: JSON.stringify(transactions) },
      ],
    }),
    signal: AbortSignal.timeout(options.timeoutMs ?? 30_000),
  });

  const text = await response.text();
  if (!response.ok) {
    throw new Error(`OpenAI-compatible endpoint -> HTTP ${response.status} ${text.slice(0, 300)}`);
  }
  const payload = asRecord(JSON.parse(text));
  const choices = Array.isArray(payload.choices) ? payload.choices : [];
  const first = asRecord(choices[0]);
  const message = asRecord(first.message);
  const answer = str(message.content);
  if (!answer) throw new Error('the OpenAI-compatible endpoint returned no message content');

  return normaliseInsights(extractJsonArray(answer));
}

async function callGemini(
  env: EnvLike,
  transactions: Transaction[],
  options: ProviderOptions,
): Promise<Insight[]> {
  const doFetch = options.fetchImpl ?? globalThis.fetch;
  const model = hasValue(env.GEMINI_MODEL) ? env.GEMINI_MODEL.trim() : GEMINI_DEFAULT_MODEL;
  const key = env.GEMINI_API_KEY as string;

  const response = await doFetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(key)}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: SYSTEM_PROMPT }] },
        contents: [{ role: 'user', parts: [{ text: JSON.stringify(transactions) }] }],
        generationConfig: { temperature: 0, responseMimeType: 'application/json' },
      }),
      signal: AbortSignal.timeout(options.timeoutMs ?? 30_000),
    },
  );

  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Gemini endpoint -> HTTP ${response.status} ${text.slice(0, 300)}`);
  }
  const payload = asRecord(JSON.parse(text));
  const candidates = Array.isArray(payload.candidates) ? payload.candidates : [];
  const first = asRecord(candidates[0]);
  const content = asRecord(first.content);
  const parts = Array.isArray(content.parts) ? content.parts : [];
  const answer = str(asRecord(parts[0]).text);
  if (!answer) throw new Error('the Gemini endpoint returned no text content');

  return normaliseInsights(extractJsonArray(answer));
}

/* -------------------------------------------------------------------------- */
/* Public interface                                                           */
/* -------------------------------------------------------------------------- */

/**
 * The single public interface of the adapter. Returns the insights only.
 * Use `analyzeDetailed` when the caller also needs to know which backend was
 * actually used (the UI shows it as a badge).
 */
export async function analyze(
  transactions: Transaction[],
  options: ProviderOptions & { env?: EnvLike } = {},
): Promise<Insight[]> {
  const analysis = await analyzeDetailed(transactions, options);
  return analysis.insights;
}

/**
 * Same as `analyze`, but also reports which backend produced the result.
 *
 * If a provider is configured but its call fails, the deterministic local
 * analysis is returned instead and the error is reported verbatim — the
 * result is never presented as if the AI had produced it.
 */
export async function analyzeDetailed(
  transactions: Transaction[],
  options: ProviderOptions & { env?: EnvLike } = {},
): Promise<AiAnalysis> {
  const env = options.env ?? readEnv();
  const backend = describeAiBackend(env);

  if (backend.mode === 'demo') {
    return { ...backend, insights: analyzeDemo(transactions), error: '' };
  }

  try {
    const insights =
      backend.provider === 'google-gemini'
        ? await callGemini(env, transactions, options)
        : await callOpenAiCompatible(env, transactions, options);
    if (insights.length === 0) throw new Error('the provider returned no usable insight');
    return { ...backend, insights, error: '' };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return {
      mode: 'demo',
      provider: 'local',
      model: 'deterministic-rules',
      label: `DEMO MODE — the AI provider call failed, showing the local deterministic analysis instead`,
      insights: analyzeDemo(transactions, `AI provider error: ${message}`),
      error: message,
    };
  }
}
