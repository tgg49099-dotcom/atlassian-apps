import { useCallback, useEffect, useMemo, useState } from 'react';
import { AgGridReact } from 'ag-grid-react';
import { AllCommunityModule, ModuleRegistry, themeQuartz, type ColDef } from 'ag-grid-community';
import { analyzeDemo, type AiAnalysis } from './ai.ts';
import { formatAmount, formatDateTime, formatKind, formatSource, toGridRows, type GridRow } from './format.ts';
import { demoSnapshot } from './paypal.ts';
import type { PayPalSnapshot } from './types.ts';

ModuleRegistry.registerModules([AllCommunityModule]);

const COLUMN_DEFS: ColDef<GridRow>[] = [
  {
    headerName: 'Date',
    field: 'date',
    valueFormatter: (params) => formatDateTime(String(params.value ?? '')),
    sort: 'desc',
    minWidth: 170,
  },
  { headerName: 'Transaction id', field: 'id', minWidth: 150 },
  {
    headerName: 'Amount',
    field: 'amount',
    valueFormatter: (params) => formatAmount(Number(params.value ?? 0), String(params.data?.currency ?? 'USD')),
    type: 'rightAligned',
    minWidth: 140,
  },
  { headerName: 'Currency', field: 'currency', maxWidth: 110 },
  {
    headerName: 'Type',
    field: 'kind',
    valueFormatter: (params) => formatKind(String(params.value ?? '')),
    minWidth: 160,
  },
  { headerName: 'PayPal event', field: 'eventCode', maxWidth: 130 },
  { headerName: 'Counterparty', field: 'counterparty', minWidth: 220 },
  {
    headerName: 'Origin',
    field: 'source',
    valueFormatter: (params) => formatSource(params.value === 'live' ? 'live' : 'demo'),
    maxWidth: 190,
  },
];

const FALLBACK_ANALYSIS: AiAnalysis = {
  mode: 'demo',
  provider: 'local',
  model: 'deterministic-rules',
  label: 'DEMO MODE — no AI provider configured (local deterministic analysis)',
  insights: [],
  error: '',
};

export default function App() {
  const [snapshot, setSnapshot] = useState<PayPalSnapshot | null>(null);
  const [analysis, setAnalysis] = useState<AiAnalysis | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [apiReachable, setApiReachable] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');

    let nextSnapshot: PayPalSnapshot | null = null;
    let reachable = true;

    try {
      const response = await fetch('/api/snapshot', { headers: { Accept: 'application/json' } });
      if (response.ok) {
        nextSnapshot = (await response.json()) as PayPalSnapshot;
      } else {
        reachable = false;
      }
    } catch {
      reachable = false;
    }

    setApiReachable(reachable);

    if (!nextSnapshot) {
      // Static hosting (no server): fall back to the bundled demo snapshot.
      nextSnapshot = demoSnapshot();
      setAnalysis({
        ...FALLBACK_ANALYSIS,
        label: 'DEMO MODE — the API is not available on this host, running the local deterministic analysis',
        insights: analyzeDemo(nextSnapshot.transactions),
      });
      setSnapshot(nextSnapshot);
      setLoading(false);
      return;
    }

    setSnapshot(nextSnapshot);

    try {
      const response = await fetch('/api/analyze', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ transactions: nextSnapshot.transactions }),
      });
      if (response.ok) {
        setAnalysis((await response.json()) as AiAnalysis);
      } else {
        setAnalysis({
          ...FALLBACK_ANALYSIS,
          label: 'DEMO MODE — the analysis API is not available on this host, running the local deterministic analysis',
          insights: analyzeDemo(nextSnapshot.transactions),
        });
      }
    } catch {
      setAnalysis({
        ...FALLBACK_ANALYSIS,
        label: 'DEMO MODE — the analysis API is not available on this host, running the local deterministic analysis',
        insights: analyzeDemo(nextSnapshot.transactions),
      });
    }

    setLoading(false);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const rows = useMemo(() => toGridRows(snapshot?.transactions ?? []), [snapshot]);
  const isDemoData = (snapshot?.mode ?? 'demo') === 'demo';
  const isDemoAi = (analysis?.mode ?? 'demo') === 'demo';

  return (
    <div className="page">
      <header className="header">
        <div>
          <h1>PayPal + AI — Transaction Insights</h1>
          <p className="subtitle">
            PayPal sandbox transactions rendered with <strong>AG Grid</strong>, analysed by a pluggable{' '}
            <strong>AI adapter</strong>. No credential ever reaches the browser.
          </p>
        </div>
        <button className="reload" onClick={() => void load()} disabled={loading}>
          {loading ? 'Loading…' : 'Reload'}
        </button>
      </header>

      <section className="badges">
        <span className={`badge ${isDemoData ? 'badge-demo' : 'badge-live'}`}>
          Data: {snapshot?.label ?? 'loading…'}
        </span>
        <span className={`badge ${isDemoAi ? 'badge-demo' : 'badge-live'}`}>
          AI: {analysis?.label ?? 'loading…'}
        </span>
        {snapshot && (
          <span className="badge badge-neutral">
            {snapshot.transactions.length} transactions · fetched {formatDateTime(snapshot.fetchedAt)}
          </span>
        )}
      </section>

      {error && <div className="alert alert-error">{error}</div>}

      {snapshot && snapshot.errors.length > 0 && (
        <div className="alert alert-error">
          <strong>PayPal read error(s):</strong>
          <ul>
            {snapshot.errors.map((message) => (
              <li key={message}>{message}</li>
            ))}
          </ul>
        </div>
      )}

      {analysis && analysis.error && (
        <div className="alert alert-warning">
          <strong>AI provider error:</strong> {analysis.error} — showing the local deterministic analysis instead.
        </div>
      )}

      <section className="panel">
        <h2>AI insights</h2>
        {analysis && analysis.insights.length > 0 ? (
          <ul className="insights">
            {analysis.insights.map((insight) => (
              <li key={insight.id} className={`insight insight-${insight.severity}`}>
                <div className="insight-head">
                  <span className="insight-title">{insight.title}</span>
                  {insight.metric && <span className="insight-metric">{insight.metric}</span>}
                </div>
                <p className="insight-detail">{insight.detail}</p>
                <span className="insight-origin">
                  origin: {insight.origin === 'ai' ? `AI provider (${analysis.provider})` : 'local deterministic rules'}
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="muted">{loading ? 'Analysing…' : 'No insight available.'}</p>
        )}
      </section>

      <section className="panel">
        <h2>Transactions</h2>
        <div className="grid-wrap">
          <AgGridReact<GridRow>
            theme={themeQuartz}
            rowData={rows}
            columnDefs={COLUMN_DEFS}
            defaultColDef={{ resizable: true, sortable: true, filter: true }}
            animateRows={false}
            suppressCellFocus={false}
          />
        </div>
      </section>

      {snapshot && snapshot.balances.length > 0 && (
        <section className="panel">
          <h2>Balances</h2>
          <div className="cards">
            {snapshot.balances.map((balance) => (
              <div className="card" key={balance.currency}>
                <span className="card-label">{balance.currency}</span>
                <span className="card-value">{formatAmount(balance.available, balance.currency)}</span>
                <span className="card-sub">available · pending {formatAmount(balance.pending, balance.currency)}</span>
              </div>
            ))}
          </div>
        </section>
      )}

      <footer className="footer">
        <p>
          {isDemoData
            ? 'The rows above are fake data generated by the app. Connect a PayPal sandbox app to read real sandbox data.'
            : 'The rows above were read from the PayPal sandbox API with the configured credentials.'}
        </p>
        {!apiReachable && (
          <p className="muted">
            The JSON API is not reachable on this host, so the app is running its built-in demo data.
          </p>
        )}
      </footer>
    </div>
  );
}
