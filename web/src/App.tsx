import { useEffect, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, get, message, post, startSession } from './api';
import type { Lease, Property, Run } from './api';
import { DatabaseState, type CommittedAction } from './DatabaseState';

type Page = 'properties' | 'lab';
type History = { reserve?: { key: string; settled: boolean }; cancel?: { key: string; leaseId: string; settled: boolean } };
type Action = { kind: 'reserve' | 'cancel'; key: string; leaseId?: string };

function Arrow() { return <span aria-hidden="true">→</span>; }
function Icon({ kind = 'property' }: { kind?: 'property' | 'lab' | 'brand' }) {
  return <svg className={`icon icon-${kind}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {kind === 'lab' ? <><path d="M9 3h6M10 3v6l-5.5 9a2 2 0 0 0 1.7 3h11.6a2 2 0 0 0 1.7-3L14 9V3M8 14h8" /><path d="M10 17h.01M14 18h.01" /></> : <><rect x="5" y="3" width="14" height="18" rx="2" /><path d="M9 7h1m4 0h1M9 11h1m4 0h1M10 21v-6h4v6" /></>}
  </svg>;
}
function Pill({ children, tone = 'green' }: { children: ReactNode; tone?: string }) {
  return <span className={`pill ${tone}`}>{children}</span>;
}

export function App() {
  const client = useQueryClient();
  const [page, setPage] = useState<Page>('properties');
  const [lastAction, setLastAction] = useState<CommittedAction>();
  const session = useQuery({ queryKey: ['session'], queryFn: startSession, staleTime: Infinity });
  const inventory = useQuery({
    queryKey: ['properties', session.data?.id],
    queryFn: ({ signal }) => get<Property[]>('/properties', signal),
    enabled: Boolean(session.data), refetchInterval: 2500,
  });
  const properties = [...(inventory.data ?? [])].sort((a, b) => a.displayName.localeCompare(b.displayName) || a.id.localeCompare(b.id));
  const connected = session.isSuccess && inventory.isSuccess && !inventory.isError;
  const error = session.error ?? inventory.error;
  const refresh = () => {
    if (session.isError) void session.refetch();
    else {
      void inventory.refetch();
      void client.invalidateQueries({ queryKey: ['lease'] });
    }
  };

  return <div className="shell">
    <aside className="sidebar">
      <a className="brand" href="#" onClick={event => { event.preventDefault(); setPage('properties'); }}>
        <span className="brand-mark"><Icon kind="brand" /></span> LeaseFlow
      </a>
      <span className="side-caption">Workspace</span>
      <nav aria-label="Main navigation">
        <button aria-current={page === 'properties' ? 'page' : undefined} onClick={() => setPage('properties')}><Icon /> Properties</button>
        <button aria-current={page === 'lab' ? 'page' : undefined} onClick={() => setPage('lab')}><Icon kind="lab" /> Concurrency lab</button>
      </nav>
      <div className="sidebar-note"><span className="tiny-label">About this workspace</span><p>Your demo inventory is private to this session. Reservations last 15 minutes.</p></div>
      <div className="sidebar-bottom"><span className={`connection-dot ${connected ? 'online' : ''}`} />{connected ? 'Backend connected' : 'Backend not connected'}<small>Local development</small></div>
    </aside>

    <div className="workspace">
      <header className="topbar"><span>LeaseFlow <span className="slash">/</span> <b>{page === 'properties' ? 'Properties' : 'Concurrency lab'}</b></span><div><span className="environment">Local demo</span><span className="avatar" title={session.data?.id}>D</span></div></header>
      <main>
        <div className="page-heading"><div><h1>{page === 'properties' ? 'Properties' : 'Concurrency lab'}</h1><p className="lead">{page === 'properties' ? 'Manage your reservations and view their database records.' : 'Test competing reservations on a single property.'}</p></div><button className="button secondary refresh" onClick={refresh} disabled={session.isFetching || inventory.isFetching}><span aria-hidden="true">↻</span> Refresh</button></div>

        {error ? <div className="connection-error" role="alert"><div className="error-icon">!</div><div><strong>Connect the backend to start testing</strong><p>{message(error)}</p><p className="setup-hint">Start PostgreSQL and Redis, then run <code>./mvnw spring-boot:run -Dspring-boot.run.profiles=local</code></p></div><button className="button secondary" onClick={() => void session.refetch()}>Reconnect</button></div> : null}
        {session.isPending || (session.isSuccess && inventory.isPending) ? <p role="status" className="loading">Connecting to your isolated demo session…</p> : null}

        {page === 'properties' ? <>
          <section className="stats" aria-label="Inventory overview">
            <Stat label="Properties" value={inventory.data ? properties.length : '—'} hint="In this session" />
            <Stat label="Available" value={inventory.data ? properties.filter(p => p.status === 'AVAILABLE').length : '—'} hint="Ready to reserve" />
            <Stat label="Reserved" value={inventory.data ? properties.filter(p => p.status === 'RESERVED').length : '—'} hint="15-minute holds" />
          </section>
          <div className="section-heading"><div><h2>Inventory <span className="count">{properties.length}</span></h2><p>Select a property to reserve a 15-minute hold.</p></div><Pill tone="neutral">{session.data?.redisEnabled ? 'PostgreSQL + Redis' : 'PostgreSQL mode'}</Pill></div>
          {properties.length ? <section className="property-grid" aria-label="Properties">{properties.map((property) => <PropertyCard key={property.id} property={property} owner={session.data!.id} onCommit={setLastAction} />)}</section> : <div className="empty-inventory"><span aria-hidden="true">▥</span><h3>Your test inventory will appear here</h3><p>Once connected, the server creates three private properties for your session.</p></div>}
          <DatabaseState properties={properties} updatedAt={inventory.dataUpdatedAt} fetching={inventory.isFetching} error={error} lastAction={lastAction} />
          <section className="lab-banner"><div><h2>Concurrency lab</h2><p>Compare reservation outcomes with up to 1,000 competing requests.</p></div><button className="button secondary" onClick={() => setPage('lab')}>Open lab <Arrow /></button></section>
        </> : <Lab connected={connected} defaultRedis={session.data?.redisEnabled ?? false} />}
        <footer><span>LeaseFlow <span className="footer-dot">·</span> Reservation workspace</span><span>Local demo · Reserve and cancel</span></footer>
      </main>
    </div>
  </div>;
}

function Stat({ label, value, hint }: { label: string; value: number | string; hint: string }) {
  return <div className="stat"><span className="tiny-label">{label}</span><strong>{value}</strong><span>{hint}</span></div>;
}

function readHistory(id: string): History {
  try {
    const value = JSON.parse(sessionStorage.getItem(`leaseflow.actions.v1.${id}`) ?? '{}') as History;
    return value && typeof value === 'object' ? value : {};
  } catch { return {}; }
}

function PropertyCard({ property, owner, onCommit }: { property: Property; owner: string; onCommit: (action: CommittedAction) => void }) {
  const client = useQueryClient();
  const [history, setHistory] = useState<History>(() => readHistory(property.id));
  const [notice, setNotice] = useState('');
  const [receipt, setReceipt] = useState<Lease | null>(null);
  const lease = useQuery({
    queryKey: ['lease', property.activeLeaseId],
    queryFn: ({ signal }) => get<Lease>(`/leases/${property.activeLeaseId}`, signal),
    enabled: Boolean(property.activeLeaseId), refetchInterval: 2500,
  });
  function remember(next: History) {
    setHistory(next);
    try { sessionStorage.setItem(`leaseflow.actions.v1.${property.id}`, JSON.stringify(next)); } catch { /* Keys still live in memory. */ }
  }
  const action = useMutation({
    mutationFn: (request: Action) => post<Lease>(request.kind === 'reserve' ? `/properties/${property.id}/leases` : `/leases/${request.leaseId}/cancel`, undefined, request.key),
    onSuccess: (response, request) => {
      remember({ ...history, [request.kind]: { key: request.key, leaseId: request.leaseId, settled: true } });
      setReceipt(response.data);
      onCommit({ propertyId: property.id, leaseId: response.data.id, kind: request.kind, replayed: response.replayed, receivedAt: Date.now() });
      setNotice(response.replayed ? 'Original response replayed. Current availability is shown above.' : request.kind === 'reserve' ? 'Reservation committed. This property is now held for you.' : 'Hold released. The property can be reserved again.');
      return Promise.all([
        client.invalidateQueries({ queryKey: ['properties', owner] }),
        client.invalidateQueries({ queryKey: ['lease'] }),
      ]);
    },
    onError: (error, request) => {
      if (error instanceof ApiError && [400, 401, 403, 404, 409, 422].includes(error.status)) {
        remember({ ...history, [request.kind]: { key: request.key, leaseId: request.leaseId, settled: true } });
      }
      void client.invalidateQueries({ queryKey: ['properties', owner] });
    },
  });
  function reserve(replay = false) {
    const saved = history.reserve;
    const key = saved && (replay || !saved.settled) ? saved.key : crypto.randomUUID();
    remember({ ...history, reserve: { key, settled: false } });
    setNotice('');
    action.mutate({ kind: 'reserve', key });
  }
  function cancel() {
    if (!property.activeLeaseId) return;
    const saved = history.cancel;
    const key = saved?.leaseId === property.activeLeaseId && !saved.settled ? saved.key : crypto.randomUUID();
    remember({ ...history, cancel: { key, leaseId: property.activeLeaseId, settled: false } });
    setNotice('');
    action.mutate({ kind: 'cancel', key, leaseId: property.activeLeaseId });
  }
  const available = property.status === 'AVAILABLE';
  return <article className="property-card">
    <div className="property-art" aria-hidden="true"><Icon /></div>
    <div className="property-content"><div className="card-title"><h3>{property.displayName}</h3><Pill tone={available ? 'green' : 'amber'}>{available ? 'Available' : property.status === 'RESERVED' ? 'Reserved' : 'Booked'}</Pill></div>
      <p className="property-id">ID <code>{property.id.slice(0, 8)}</code><span>Version {property.version}</span></p>
      <div className="hold-area">{lease.data && property.status === 'RESERVED' ? <><span className="tiny-label">Hold expires in</span><Countdown deadline={lease.data.holdExpiresAt} /></> : <><span className="tiny-label">Hold duration</span><strong>15 <span>minutes</span></strong></>}</div>
      {lease.error ? <p className="inline-error" role="alert">{message(lease.error)}</p> : null}
      <button className={`button ${available ? 'primary' : 'secondary'} full`} disabled={action.isPending || (!available && lease.data?.status !== 'RESERVED')} onClick={() => available ? reserve() : cancel()}>{action.isPending ? 'Processing…' : available ? history.reserve && !history.reserve.settled ? 'Retry reservation' : 'Reserve property' : 'Cancel reservation'}{!action.isPending ? <Arrow /> : null}</button>
      {history.reserve ? <button className="text-button replay" disabled={action.isPending} onClick={() => reserve(true)}>Replay reservation request</button> : <p className="card-footnote">Retries use the same request key.</p>}
      {action.error ? <p className="inline-error" role="alert">{message(action.error)}</p> : null}
      {notice ? <p className="inline-notice" role="status">{notice}</p> : null}
      {receipt ? <details className="receipt"><summary>View committed response</summary><pre>{JSON.stringify(receipt, null, 2)}</pre><p>Key <code>{action.variables?.key}</code></p></details> : null}
    </div>
  </article>;
}

function Countdown({ deadline }: { deadline: string }) {
  const [now, setNow] = useState(Date.now);
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 1000); return () => window.clearInterval(timer); }, []);
  const seconds = Math.max(0, Math.ceil((Date.parse(deadline) - now) / 1000));
  return <strong className={seconds === 0 ? 'expired' : ''}>{seconds === 0 ? <span>Awaiting server expiration</span> : <>{String(Math.floor(seconds / 60)).padStart(2, '0')}<span>:</span>{String(seconds % 60).padStart(2, '0')}</>}</strong>;
}

function Lab({ connected, defaultRedis }: { connected: boolean; defaultRedis: boolean }) {
  const [attempts, setAttempts] = useState(100);
  const [redis, setRedis] = useState(defaultRedis);
  const [completedAt, setCompletedAt] = useState(0);
  const run = useMutation({
    mutationFn: () => post<Run>('/demo/runs', { attempts, redisEnabled: redis }),
    onSuccess: () => setCompletedAt(Date.now()),
  });
  const result = run.data?.data;
  return <>
    <section className="lab-layout">
      <div className="lab-config panel"><span className="tiny-label">Test settings</span><h2>Configure test</h2><p>Every run creates fresh inventory in your session and calls the real reservation service.</p>
        <fieldset disabled={run.isPending}><legend>Reservation attempts</legend><div className="size-options">{[10, 100, 1000].map(count => <button key={count} aria-pressed={count === attempts} onClick={() => setAttempts(count)}>{count.toLocaleString()}</button>)}</div></fieldset>
        <label className="toggle-row"><div><strong>Redis admission lock</strong><span>Compare against database-only mode.</span></div><input type="checkbox" role="switch" checked={redis} onChange={event => setRedis(event.target.checked)} disabled={run.isPending} /></label>
        <div className="test-scope"><p>Up to 32 simultaneous workers. One run at a time per backend instance. No shared inventory is reset.</p></div>
        <button className="button primary full" disabled={!connected || run.isPending} onClick={() => run.mutate()}>{run.isPending ? 'Running test…' : 'Run concurrency test'}{!run.isPending ? <Arrow /> : null}</button>
        {run.isPending ? <p className="running-note" role="status">The server is collecting actual outcomes. Large runs may take up to two minutes.</p> : null}
        {run.error ? <p className="inline-error" role="alert">{message(run.error)}</p> : null}
      </div>
      <div className="lab-result panel" aria-live="polite" aria-busy={run.isPending}>
        {!result ? <div className="result-empty"><span className="target-symbol"><Icon kind="lab" /></span><span className="tiny-label">Results</span><h2>No results yet</h2><p>Successful reservations, rejected requests, elapsed time, and the final lease count will appear here.</p><div className="expected-result">Expected result <strong>1 active lease</strong><span>Measured after every completed test</span></div></div> : <>
          <div className="result-heading"><div><span className="tiny-label">{result.attempts.toLocaleString()} ATTEMPTS · {result.redisEnabled ? 'REDIS + DATABASE' : 'DATABASE ONLY'}</span><h2>{result.verified ? 'Test completed' : 'Review required'}</h2></div><Pill tone={result.verified ? 'green' : 'amber'}>{result.verified ? 'Verified' : 'Not verified'}</Pill></div>
          <div className="result-hero"><div><strong>{result.metrics.successes}</strong><span>successful reservation</span></div><div><strong>{(result.elapsedMs / 1000).toFixed(2)}<small>s</small></strong><span>measured execution time</span></div></div>
          <div className="outcome-bar" aria-label="Outcome distribution">{Object.entries(result.metrics).filter(([, value]) => value > 0).map(([key, value]) => <span className={`bar-${key}`} key={key} style={{ flex: value }} title={`${key}: ${value}`} />)}</div>
          <dl className="result-metrics"><div><dt>Property unavailable</dt><dd>{result.metrics.unavailable}</dd></div><div><dt>Redis lock contention</dt><dd>{result.metrics.lockContention}</dd></div><div><dt>Optimistic-lock conflicts</dt><dd>{result.metrics.optimisticConflicts}</dd></div><div><dt>Database constraint conflicts</dt><dd>{result.metrics.databaseConflicts}</dd></div><div><dt>Unexpected errors</dt><dd className={result.metrics.unexpectedErrors ? 'error-text' : ''}>{result.metrics.unexpectedErrors}</dd></div></dl>
          <div className="database-proof"><div><span className="tiny-label">Database summary</span><Pill tone="neutral">{result.property.status}</Pill></div><p><span>Active leases</span><strong>{result.activeLeaseCount}</strong></p><p><span>Property ID</span><code>{result.property.id}</code></p><p><span>Winning lease</span><code>{result.activeLease?.id ?? 'None'}</code></p><p><span>Ownership matches</span><strong>{result.activeLease && result.property.activeLeaseId === result.activeLease.id ? 'Yes' : 'No'}</strong></p></div>
          {result.errors.length ? <p className="inline-error">Errors: {result.errors.join(', ')}</p> : null}
        </>}
      </div>
    </section>
    <DatabaseState properties={result ? [result.property] : []} updatedAt={result ? completedAt : 0} fetching={run.isPending} error={null} snapshot={{ runId: result?.id, lease: result?.activeLease ?? null, running: run.isPending }} />
    <section className="lab-how"><span className="tiny-label">About this test</span><h2>How results are verified</h2><p>Redis can reduce contention, but the database transaction, version checks, and unique index protect inventory in either mode. Switch Redis off and run again to compare real outcomes.</p><p className="muted">A successful test requires exactly one successful reservation, one active lease, matching property ownership, and zero unexpected errors. Results are scoped to this run.</p></section>
  </>;
}
