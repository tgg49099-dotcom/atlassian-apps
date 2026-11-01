/**
 * Formatting helpers for the grid rows.
 *
 * Everything here is pure and locale-independent on purpose: the same input
 * always produces the same string, which makes the formatting testable and
 * makes the demo reproducible in a screenshot.
 */
import type { DataSource, Transaction } from './types.ts';

export interface GridRow {
  id: string;
  /** Raw ISO date, kept so the grid can sort on the real value. */
  date: string;
  dateLabel: string;
  /** Raw amount, kept so the grid can sort numerically. */
  amount: number;
  amountLabel: string;
  currency: string;
  eventCode: string;
  kind: string;
  kindLabel: string;
  counterparty: string;
  source: DataSource;
  sourceLabel: string;
}

/** `1234.5` -> `1,234.50`; always two decimals, always a comma separator. */
export function formatAmount(amount: number, currency: string): string {
  const safe = Number.isFinite(amount) ? amount : 0;
  const fixed = Math.abs(safe).toFixed(2);
  const [integerPart, decimalPart] = fixed.split('.');
  const grouped = integerPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const sign = safe < 0 ? '-' : '';
  return `${sign}${grouped}.${decimalPart} ${currency}`;
}

/** ISO-8601 -> `YYYY-MM-DD HH:MM UTC`. Unparsable input is returned as-is. */
export function formatDateTime(iso: string): string {
  if (!iso) return '';
  const date = new Date(iso);
  const time = date.getTime();
  if (!Number.isFinite(time)) return iso;
  const pad = (value: number, width = 2) => String(value).padStart(width, '0');
  return (
    `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ` +
    `${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())} UTC`
  );
}

/** Human label for a transaction kind. */
export function formatKind(kind: string): string {
  switch (kind) {
    case 'SALE':
      return 'Payment received';
    case 'REFUND':
      return 'Refund';
    case 'PAYOUT':
      return 'Payment sent';
    case '':
      return 'Unknown';
    default:
      return kind;
  }
}

/** Human label for the origin of a row. Never ambiguous on purpose. */
export function formatSource(source: DataSource): string {
  return source === 'live' ? 'LIVE (PayPal sandbox)' : 'DEMO (fake data)';
}

/** Builds the row displayed by AG Grid. */
export function toGridRow(transaction: Transaction): GridRow {
  return {
    id: transaction.id,
    date: transaction.date,
    dateLabel: formatDateTime(transaction.date),
    amount: transaction.amount,
    amountLabel: formatAmount(transaction.amount, transaction.currency),
    currency: transaction.currency,
    eventCode: transaction.eventCode,
    kind: transaction.kind,
    kindLabel: formatKind(transaction.kind),
    counterparty: transaction.counterparty,
    source: transaction.source,
    sourceLabel: formatSource(transaction.source),
  };
}

/** Builds every grid row at once. */
export function toGridRows(transactions: Transaction[]): GridRow[] {
  return transactions.map(toGridRow);
}
