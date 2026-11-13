import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { get, message, type Lease, type Property } from './api';

export type CommittedAction = {
  propertyId: string; leaseId: string; kind: 'reserve' | 'cancel'; replayed: boolean; receivedAt: number;
};

function time(value: number | string) {
  return new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function DatabaseState({ properties, updatedAt, fetching, error, lastAction, snapshot }: {
  properties: Property[]; updatedAt: number; fetching: boolean; error: unknown; lastAction?: CommittedAction;
  snapshot?: { runId?: string; lease: Lease | null; running: boolean };
}) {
  const [selection, setSelection] = useState<{ propertyId: string; actionAt?: number }>();
  // Follow each new request automatically, but let visitors inspect any other row afterwards.
  const selectedId = selection?.actionAt === lastAction?.receivedAt
    ? selection?.propertyId : lastAction?.propertyId;
  const selected = properties.find(property => property.id === selectedId) ?? properties[0];
  const leaseId = selected?.activeLeaseId
    ?? (selected?.id === lastAction?.propertyId ? lastAction?.leaseId : undefined);
  const lease = useQuery({
    queryKey: ['lease', leaseId],
    queryFn: ({ signal }) => get<Lease>(`/leases/${leaseId}`, signal),
    enabled: Boolean(leaseId) && !snapshot, refetchInterval: snapshot ? false : 2500,
  });
  const leaseData = snapshot ? snapshot.lease : lease.data;

  return <section className="database-panel" aria-labelledby="database-title">
    <div className="database-heading">
      <div><span className="eyebrow">{snapshot ? 'BEHIND THE RACE' : 'BEHIND EVERY REQUEST'}</span><h2 id="database-title">Your database, in view<span>.</span></h2><p>{snapshot ? 'Inspect the property and winning lease read from PostgreSQL at the end of each run.' : 'See the persisted property and lease state change as you interact.'}</p></div>
      <span className={`sync-status ${error ? 'stale' : ''}`}><span className={`connection-dot ${updatedAt && !error ? 'online' : ''}`} />{snapshot ? snapshot.running ? 'Test in progress…' : snapshot.runId ? 'Completed run snapshot' : 'Waiting for a run' : error ? 'Updates unavailable' : !updatedAt ? 'Waiting for data' : fetching ? 'Syncing…' : 'Auto-refresh · 2.5s'}</span>
    </div>
    {error ? <p className="database-warning" role="alert">Showing the last successful read. {message(error)}</p> : null}
    <div className="database-body">
      <div className="database-records">
        <div className="record-caption"><span>PROPERTY RECORDS</span><span>{properties.length} rows</span></div>
        <div className="database-table-scroll"><table className="database-table"><caption className="sr-only">Current property records. Select a property to inspect its lease.</caption>
          <thead><tr><th scope="col">Property</th><th scope="col">Status</th><th scope="col">Version</th></tr></thead>
          <tbody>{properties.map(property => <tr key={property.id} className={selected?.id === property.id ? 'selected' : ''}>
            <th scope="row"><button aria-pressed={selected?.id === property.id} onClick={() => setSelection({ propertyId: property.id, actionAt: lastAction?.receivedAt })}>{property.displayName}<code>{property.id.slice(0, 8)}…</code></button></th>
            <td><span className={`db-status status-${property.status.toLowerCase()}`}>{property.status}</span></td><td><code>v{property.version}</code></td>
          </tr>)}</tbody>
        </table></div>
        {!properties.length ? <p className="database-placeholder">{snapshot ? snapshot.running ? 'Requests are racing. Database records will appear when the run completes.' : 'Run a concurrency test to inspect its database records.' : 'Connect the backend to inspect your session’s records.'}</p> : null}
        <div className="database-read-time">{updatedAt && properties.length ? <>{snapshot ? 'Result received' : 'Last property read'} <time dateTime={new Date(updatedAt).toISOString()}>{time(updatedAt)}</time></> : 'No database reads yet'}<span>{snapshot ? 'Isolated test inventory' : 'Session-scoped inventory'}</span></div>
      </div>
      <div className="lease-inspector">
        <div className="record-caption"><span>LEASE INSPECTOR</span><span aria-hidden="true">↳</span></div>
        <h3>{selected?.displayName ?? 'Select a property'}</h3>
        <dl><div><dt>Property status</dt><dd>{selected?.status ?? '—'}</dd></div><div><dt>Active lease ID</dt><dd><code>{selected ? selected.activeLeaseId ?? 'NULL' : '—'}</code></dd></div></dl>
        {!snapshot && lease.isError ? <p className="inline-error" role="alert">Lease update unavailable. {message(lease.error)}</p> : leaseData ? <div className="lease-record">
          <div className="lease-record-heading"><span>{snapshot ? 'WINNING LEASE' : selected?.activeLeaseId === leaseData.id ? 'CURRENT LEASE' : 'LAST REQUEST’S LEASE'}</span><span className={`db-status status-${leaseData.status.toLowerCase()}`}>{leaseData.status}</span></div>
          <dl><div><dt>Lease ID</dt><dd><code>{leaseData.id}</code></dd></div><div><dt>Created</dt><dd>{time(leaseData.createdAt)}</dd></div><div><dt>Hold deadline</dt><dd>{time(leaseData.holdExpiresAt)}</dd></div></dl>
          <p className="lease-read-time">{snapshot ? 'Read at run completion' : `Lease read at ${time(lease.dataUpdatedAt)}`}</p>
        </div> : leaseId && !snapshot ? <p className="database-placeholder" role="status">Reading lease from the backend…</p> : <div className="no-lease"><span aria-hidden="true">◇</span><p>{snapshot && !snapshot.runId ? 'Waiting for test results' : 'No active lease'}</p><small>{snapshot ? 'The server reports the winning lease after each completed run.' : 'Reserve this property to see its lease record here.'}</small></div>}
      </div>
    </div>
    <div className="database-event" role="status"><span className="event-icon" aria-hidden="true">↳</span>{snapshot ? <p><strong>{snapshot.running ? 'Collecting database results' : snapshot.runId ? 'Run complete' : 'Ready to watch a race'}</strong><span>{snapshot.runId ? <>Run <code>{snapshot.runId}</code> · Snapshot captured after all attempts; run again for a new result.</> : 'Each test uses a fresh property. Its persisted records appear here after completion.'}</span></p> : lastAction ? <p><strong>{lastAction.replayed ? 'Original response replayed' : lastAction.kind === 'reserve' ? 'Reservation committed' : 'Cancellation committed'}</strong><span>{time(lastAction.receivedAt)} · {lastAction.replayed ? 'The records above are fetched separately to show current state.' : 'Property and lease records refreshed from the backend.'}</span></p> : <p><strong>Ready to watch a transaction</strong><span>Reserve or cancel a property. Its latest records will appear here automatically.</span></p>}</div>
  </section>;
}
