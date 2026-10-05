import type { ApiErrorBody } from './types';

/**
 * The single HTTP client.
 *
 * Everything the dashboard fetches goes through the gateway on one origin, with one
 * error shape and one place that knows about the access token. That last point is
 * the reason this file exists rather than components calling `fetch` directly: a
 * token read in twenty places is a token that eventually gets logged, put in a
 * query string, or left attached to a request after logout.
 */

/**
 * The gateway, not a service.
 *
 * Every request goes through it so that the rate limits, the CORS policy and the
 * `/api/admin/**` role check all apply. Calling a service directly would bypass
 * all three, and would work locally — which is what makes it a tempting mistake.
 */
const BASE_URL = '';

/** The gateway echoes this back, and it is the id support quotes to find a request. */
const CORRELATION_HEADER = 'X-Correlation-Id';

/**
 * A failed request, carrying what the platform actually said.
 *
 * The server returns a structured `ApiError` for every failure, including from the
 * gateway itself, so there is no reason for the UI to invent its own messages. The
 * `correlationId` is the field that matters: it is what turns "it didn't work" into
 * a request somebody can find in a log.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId: string | undefined;
  readonly fieldErrors: ReadonlyArray<{ field: string; message: string }>;

  constructor(status: number, body: Partial<ApiErrorBody> | null, correlationId?: string) {
    super(body?.message ?? `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = body?.code ?? 'UNKNOWN';
    this.correlationId = body?.correlationId ?? correlationId;
    this.fieldErrors = (body?.fieldErrors ?? []).map((error) => ({
      field: error.field,
      message: error.message,
    }));
  }

  /** Whether the caller is not allowed to do this, as opposed to not signed in. */
  get isForbidden(): boolean {
    return this.status === 403;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }

  /** A conflict is usually somebody else having acted first, which the UI should say. */
  get isConflict(): boolean {
    return this.status === 409;
  }

  get isRateLimited(): boolean {
    return this.status === 429;
  }
}

/**
 * How the client gets a token, and what it does when one expires.
 *
 * Injected rather than imported so this module holds no state and the auth layer
 * owns the token's lifetime. It also means a test can drive the client without a
 * React tree.
 */
export interface TokenProvider {
  /** The current access token, or null when signed out. */
  accessToken(): string | null;

  /**
   * Obtains a fresh access token. Resolves false when refreshing is impossible, at
   * which point the caller is signed out.
   *
   * Implementations must collapse concurrent calls into one refresh — see
   * `AuthProvider`. Six panels loading at once would otherwise each spend the
   * refresh token, and rotation means only the first would succeed.
   */
  refresh(): Promise<boolean>;

  /** Called when the session cannot be recovered. */
  onSessionLost(): void;
}

let tokens: TokenProvider | null = null;

/** Wires the client to the auth layer. Called once, from the auth provider. */
export function configureClient(provider: TokenProvider): void {
  tokens = provider;
}

/** For tests, and for a clean slate between them. */
export function resetClient(): void {
  tokens = null;
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** Query parameters. Undefined and null values are dropped rather than sent as "undefined". */
  query?: Record<string, string | number | boolean | null | undefined>;
  signal?: AbortSignal;
  /** Set on the two endpoints that mint credentials, which must not carry a stale token. */
  anonymous?: boolean;
}

/**
 * Performs one request, refreshing once on a 401.
 *
 * The retry is deliberately limited to a single attempt after a successful refresh.
 * A loop would turn a genuinely revoked session into an infinite request storm
 * against the auth service, which is both a self-inflicted outage and
 * indistinguishable from an attack.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(path, options);

  if (response.status !== 401 || options.anonymous || !tokens) {
    return handle<T>(response);
  }

  const refreshed = await tokens.refresh();
  if (!refreshed) {
    tokens.onSessionLost();
    return handle<T>(response);
  }

  return handle<T>(await send(path, options));
}

async function send(path: string, options: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = {};

  // Only sent when there is a body. A GET with a Content-Type triggers a CORS
  // preflight for no reason, and the gateway's allowed-header list is narrow.
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }

  if (!options.anonymous) {
    const token = tokens?.accessToken();
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
  }

  return fetch(`${BASE_URL}${path}${toQueryString(options.query)}`, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    ...(options.signal ? { signal: options.signal } : {}),
  });
}

async function handle<T>(response: Response): Promise<T> {
  const correlationId = response.headers.get(CORRELATION_HEADER) ?? undefined;

  if (response.status === 204) {
    return undefined as T;
  }

  if (!response.ok) {
    throw new ApiError(response.status, await readErrorBody(response), correlationId);
  }

  const text = await response.text();
  // A 200 with an empty body is legitimate on the endpoints that return Void.
  // JSON.parse('') throws, and doing so here would report a successful action as a
  // failure — the worst possible direction for an approve or block to fail in.
  return (text.length === 0 ? (undefined as T) : (JSON.parse(text) as T));
}

/**
 * Reads the platform's error body, tolerating anything else.
 *
 * A 502 from a proxy, or a rate-limit response with an empty body, will not be
 * JSON. Throwing a parse error here would replace a useful status code with an
 * unrelated `SyntaxError`, and the UI would report a syntax problem for a service
 * being down.
 */
async function readErrorBody(response: Response): Promise<Partial<ApiErrorBody> | null> {
  try {
    const text = await response.text();
    return text.length === 0 ? null : (JSON.parse(text) as Partial<ApiErrorBody>);
  } catch {
    return null;
  }
}

/** Builds a query string, dropping absent values. */
export function toQueryString(
  query: Record<string, string | number | boolean | null | undefined> | undefined,
): string {
  if (!query) {
    return '';
  }
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    // Explicitly excludes null and undefined, and explicitly INCLUDES false and 0.
    // A `blockingOnly=false` filter and an absent one mean different things to the
    // fraud service, and dropping falsy values would silently change the query.
    if (value !== null && value !== undefined) {
      params.append(key, String(value));
    }
  }
  const encoded = params.toString();
  return encoded.length === 0 ? '' : `?${encoded}`;
}

export const api = {
  get: <T>(path: string, query?: RequestOptions['query'], signal?: AbortSignal): Promise<T> =>
    request<T>(path, { method: 'GET', ...(query ? { query } : {}), ...(signal ? { signal } : {}) }),

  post: <T>(path: string, body?: unknown): Promise<T> =>
    request<T>(path, { method: 'POST', ...(body === undefined ? {} : { body }) }),

  /** For login and refresh, which must not carry an existing token. */
  postAnonymous: <T>(path: string, body: unknown): Promise<T> =>
    request<T>(path, { method: 'POST', body, anonymous: true }),
};
