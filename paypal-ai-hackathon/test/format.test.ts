import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  formatAmount,
  formatDateTime,
  formatKind,
  formatSource,
  toGridRow,
  toGridRows,
} from '../src/format.ts';
import type { Transaction } from '../src/types.ts';

const ROW: Transaction = {
  id: '0VF52815937971249',
  date: '2026-10-28T09:12:44Z',
  amount: 1234.5,
  currency: 'USD',
  eventCode: 'T0006',
  kind: 'SALE',
  counterparty: 'buyer.one@example.com',
  source: 'live',
};

test('formatAmount groups thousands and always shows two decimals', () => {
  assert.equal(formatAmount(1234.5, 'USD'), '1,234.50 USD');
  assert.equal(formatAmount(0, 'EUR'), '0.00 EUR');
  assert.equal(formatAmount(-12, 'USD'), '-12.00 USD');
  assert.equal(formatAmount(1000000, 'EUR'), '1,000,000.00 EUR');
  assert.equal(formatAmount(Number.NaN, 'USD'), '0.00 USD');
});

test('formatDateTime renders UTC and keeps unparsable input', () => {
  assert.equal(formatDateTime('2026-10-28T09:12:44Z'), '2026-10-28 09:12 UTC');
  assert.equal(formatDateTime('not-a-date'), 'not-a-date');
  assert.equal(formatDateTime(''), '');
});

test('formatKind and formatSource are explicit about what the row is', () => {
  assert.equal(formatKind('SALE'), 'Payment received');
  assert.equal(formatKind('REFUND'), 'Refund');
  assert.equal(formatKind('PAYOUT'), 'Payment sent');
  assert.equal(formatKind(''), 'Unknown');
  assert.equal(formatSource('live'), 'LIVE (PayPal sandbox)');
  assert.equal(formatSource('demo'), 'DEMO (fake data)');
});

test('toGridRow builds the row shown by AG Grid', () => {
  const row = toGridRow(ROW);

  assert.equal(row.id, '0VF52815937971249');
  assert.equal(row.date, '2026-10-28T09:12:44Z');
  assert.equal(row.dateLabel, '2026-10-28 09:12 UTC');
  assert.equal(row.amount, 1234.5);
  assert.equal(row.amountLabel, '1,234.50 USD');
  assert.equal(row.currency, 'USD');
  assert.equal(row.eventCode, 'T0006');
  assert.equal(row.kindLabel, 'Payment received');
  assert.equal(row.counterparty, 'buyer.one@example.com');
  assert.equal(row.source, 'live');
  assert.equal(row.sourceLabel, 'LIVE (PayPal sandbox)');
});

test('toGridRows keeps the raw numeric amount so the grid sorts numerically', () => {
  const rows = toGridRows([ROW, { ...ROW, id: 'T2', amount: 9.99 }]);
  assert.equal(rows.length, 2);
  assert.equal(rows[1].amount, 9.99);
  assert.equal(rows[1].amountLabel, '9.99 USD');
});
