export type Property = {
  id: string; displayName: string; status: 'AVAILABLE' | 'RESERVED' | 'BOOKED';
  activeLeaseId: string | null; version: number;
};
export type Lease = {
  id: string; propertyId: string; status: 'RESERVED' | 'CONFIRMING' | 'COMPENSATING' | 'BOOKED' | 'CANCELLED' | 'EXPIRED' | 'FAILED';
  holdExpiresAt: string; createdAt: string;
};
export type Session = { id: string; redisEnabled: boolean };
export type Run = {
  id: string; attempts: number; parallelism: number; redisEnabled: boolean; elapsedMs: number;
  metrics: { successes: number; unavailable: number; lockContention: number; optimisticConflicts: number;
    databaseConflicts: number; unexpectedErrors: number };
  property: Property; activeLeaseCount: number; activeLease: Lease | null; verified: boolean; errors: string[];
};

export class ApiError extends Error {
  constructor(message: string, public status: number, public code: string) { super(message); }
}
let csrf: { headerName: string; token: string } | undefined;

async function decode<T>(response: Response): Promise<T> {
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new ApiError(body?.detail ?? `Request failed (${response.status}).`, response.status, body?.code ?? 'HTTP_ERROR');
  }
  return body as T;
}

async function token() {
  if (!csrf) csrf = await decode<{ headerName: string; token: string }>(await fetch('/api/v1/csrf', { credentials: 'same-origin' }));
  return csrf;
}

export async function get<T>(url: string, signal?: AbortSignal): Promise<T> {
  return decode<T>(await fetch(`/api/v1${url}`, { credentials: 'same-origin', signal, cache: 'no-store' }));
}

export async function post<T>(url: string, data?: unknown, key?: string): Promise<{ data: T; replayed: boolean }> {
  const security = await token();
  let response: Response;
  try {
    response = await fetch(`/api/v1${url}`, {
      method: 'POST', credentials: 'same-origin',
      headers: { [security.headerName]: security.token, ...(key ? { 'Idempotency-Key': key } : {}),
        ...(data !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      body: data === undefined ? undefined : JSON.stringify(data),
    });
  } catch {
    throw new ApiError('Connection lost. Keep the same key and retry to find out whether the request committed.', 0, 'NETWORK_ERROR');
  }
  if (response.status === 403) csrf = undefined;
  if (response.status === 401) csrf = undefined;
  return { data: await decode<T>(response), replayed: response.headers.get('Idempotency-Replayed') === 'true' };
}

export async function startSession(): Promise<Session> {
  // A reconnect may follow an expired server session; obtain its current CSRF token.
  csrf = undefined;
  return (await post<Session>('/demo/session')).data;
}
export function message(error: unknown) { return error instanceof Error ? error.message : 'Something went wrong. Please retry.'; }
