/**
 * Shared domain types.
 *
 * `Transaction.source` records where the row came from:
 *   - `live` : read from the PayPal sandbox API with real credentials;
 *   - `demo` : generated locally because no credentials were configured.
 * The UI uses it so that demo rows can never be mistaken for real data.
 */
export type DataSource = 'live' | 'demo';

export interface Transaction {
  /** PayPal transaction id (or a stable synthetic id in demo mode). */
  id: string;
  /** ISO-8601 UTC timestamp of the transaction. */
  date: string;
  /** Signed amount, in the major unit of `currency`. */
  amount: number;
  /** ISO-4217 currency code, e.g. "USD". */
  currency: string;
  /** Raw PayPal transaction event code, e.g. "T0006". Empty in demo mode. */
  eventCode: string;
  /** Human-readable kind derived from `eventCode`: SALE / REFUND / PAYOUT / ... */
  kind: string;
  /** Email of the payer or the receiver, when the API exposes one. */
  counterparty: string;
  /** Where this row comes from. */
  source: DataSource;
}

export interface Balance {
  currency: string;
  available: number;
  pending: number;
}

export interface WebhookSummary {
  id: string;
  url: string;
  eventTypes: string[];
}

export interface PayPalSnapshot {
  mode: DataSource;
  /** Banner shown in the UI. Never contains a secret. */
  label: string;
  transactions: Transaction[];
  balances: Balance[];
  webhooks: WebhookSummary[];
  /** Non-empty when at least one read call failed. The UI shows it verbatim. */
  errors: string[];
  fetchedAt: string;
}
