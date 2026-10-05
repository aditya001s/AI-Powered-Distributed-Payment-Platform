import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api, configureClient, request, resetClient, toQueryString } from './client';
import type { TokenProvider } from './client';

/**
 * The HTTP client.
 *
 * <p>Three behaviours here are worth a test each, and all three are about failure rather than success.
 *
 * <p><strong>The single refresh on 401.</strong> One retry after a successful refresh, and never a loop. A
 * loop turns a genuinely revoked session into an infinite request storm against the auth service, which is
 * both a self-inflicted outage and indistinguishable from an attack.
 *
 * <p><strong>Error bodies that are not JSON.</strong> A 502 from a proxy, or a rate-limit response with an
 * empty body, must surface as its status code rather than as a `SyntaxError` — otherwise the UI reports a
 * syntax problem for a service being down.
 *
 * <p><strong>Query strings that keep `false` and `0`.</strong> `blockingOnly=false` and an absent filter are
 * different queries to the fraud service, and dropping falsy values would silently change what was asked.
 */

const BASE = '';

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

/** A stub provider whose refresh outcome and call count the test controls. */
function stubTokens(overrides: Partial<TokenProvider> = {}): TokenProvider & {
  refreshCalls: () => number;
  lostCalls: () => number;
} {
  let refreshCalls = 0;
  let lostCalls = 0;

  const provider = {
    accessToken: () => 'access-token-1',
    refresh: async () => {
      refreshCalls += 1;
      return true;
    },
    onSessionLost: () => {
      lostCalls += 1;
    },
    ...overrides,
  };

  return {
    ...provider,
    refresh: async () => {
      refreshCalls += 1;
      return overrides.refresh ? overrides.refresh() : true;
    },
    onSessionLost: () => {
      lostCalls += 1;
      overrides.onSessionLost?.();
    },
    refreshCalls: () => refreshCalls,
    lostCalls: () => lostCalls,
  };
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  resetClient();
  vi.unstubAllGlobals();
});

describe('toQueryString', () => {
  it('drops null and undefined', () => {
    expect(toQueryString({ a: null, b: undefined, c: 'x' })).toBe('?c=x');
  });

  /**
   * The assertion that matters. A truthiness check here would drop `blockingOnly=false` and
   * `minRiskScore=0`, quietly turning a specific query into a broad one.
   */
  it('keeps false and zero', () => {
    expect(toQueryString({ blockingOnly: false, minRiskScore: 0 })).toBe(
      '?blockingOnly=false&minRiskScore=0',
    );
  });

  it('returns an empty string for no parameters', () => {
    expect(toQueryString(undefined)).toBe('');
    expect(toQueryString({})).toBe('');
  });

  it('encodes values that would otherwise break the URL', () => {
    expect(toQueryString({ sort: 'createdAt,asc', q: 'a&b=c' })).toBe(
      '?sort=createdAt%2Casc&q=a%26b%3Dc',
    );
  });
});

describe('request', () => {
  it('calls the gateway and returns the parsed body', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { payments: 3 }));

    const body = await api.get<{ payments: number }>('/api/admin/analytics/overview');

    expect(body).toEqual({ payments: 3 });
    // The gateway, not a service. Calling a service directly would bypass the rate limits, the
    // CORS policy and the /api/admin/** role check, and would work locally.
    expect(fetchMock.mock.calls[0]?.[0]).toBe(`${BASE}/api/admin/analytics/overview`);
  });

  it('attaches the bearer token from the provider', async () => {
    configureClient(stubTokens());
    fetchMock.mockResolvedValue(jsonResponse(200, {}));

    await api.get('/api/fraud/stats');

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer access-token-1');
  });

  /**
   * No Content-Type on a GET. It would trigger a CORS preflight for no reason, and the gateway's
   * allowed-header list is deliberately narrow.
   */
  it('sends no Content-Type when there is no body', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, {}));

    await api.get('/api/fraud/stats');

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect((init.headers as Record<string, string>)['Content-Type']).toBeUndefined();
  });

  it('sends a JSON body on a post', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { decisionId: 'dec-1' }));

    await api.post('/api/fraud/alerts/a1/decide', { action: 'RELEASE', reason: 'Verified' });

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(init.body).toBe('{"action":"RELEASE","reason":"Verified"}');
  });

  /** Login and refresh must not carry a stale token into a credential exchange. */
  it('omits the token on an anonymous request', async () => {
    configureClient(stubTokens());
    fetchMock.mockResolvedValue(jsonResponse(200, {}));

    await api.postAnonymous('/api/auth/login', { email: 'a@b.c', password: 'x' });

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect((init.headers as Record<string, string>)['Authorization']).toBeUndefined();
  });

  it('returns undefined for a 204', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await expect(api.post('/api/auth/logout', {})).resolves.toBeUndefined();
  });

  /**
   * A 200 with an empty body is legitimate on the endpoints that return Void. `JSON.parse('')` throws, and
   * doing so here would report a successful action as a failure — the worst direction for an approve or
   * block to fail in.
   */
  it('treats an empty 200 body as success, not as a parse failure', async () => {
    fetchMock.mockResolvedValue(new Response('', { status: 200 }));

    await expect(api.post('/api/fraud/alerts/a1/take')).resolves.toBeUndefined();
  });
});

describe('ApiError', () => {
  it('carries the platform error body and the correlation id', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        409,
        {
          status: 409,
          code: 'ALERT_ALREADY_DECIDED',
          message: 'This alert has already been decided',
          correlationId: 'corr-123',
        },
        { 'X-Correlation-Id': 'header-corr' },
      ),
    );

    const error = await api.get('/api/fraud/alerts/a1').catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(409);
    expect(apiError.code).toBe('ALERT_ALREADY_DECIDED');
    expect(apiError.isConflict).toBe(true);
    // The body's own id wins over the header: it is the one the service logged against.
    expect(apiError.correlationId).toBe('corr-123');
    expect(apiError.message).toBe('This alert has already been decided');
  });

  it('falls back to the correlation header when the body carries none', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(500, { status: 500, code: 'INTERNAL', message: 'boom' }, {
        'X-Correlation-Id': 'header-corr',
      }),
    );

    const error = (await api.get('/x').catch((caught: unknown) => caught)) as ApiError;

    expect(error.correlationId).toBe('header-corr');
  });

  it('exposes field errors from a validation failure', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(400, {
        status: 400,
        code: 'VALIDATION_FAILED',
        message: 'Validation failed',
        fieldErrors: [{ field: 'reason', message: 'must not be blank' }],
      }),
    );

    const error = (await api
      .post('/api/fraud/alerts/a1/decide', {})
      .catch((caught: unknown) => caught)) as ApiError;

    expect(error.fieldErrors).toEqual([{ field: 'reason', message: 'must not be blank' }]);
  });

  /**
   * A gateway or proxy failure will not be JSON. Throwing a parse error here would replace a useful status
   * code with an unrelated `SyntaxError`, and the UI would report a syntax problem for a service being down.
   */
  it('survives an error body that is not JSON', async () => {
    fetchMock.mockResolvedValue(
      new Response('<html>502 Bad Gateway</html>', {
        status: 502,
        headers: { 'Content-Type': 'text/html' },
      }),
    );

    const error = (await api.get('/x').catch((caught: unknown) => caught)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(502);
    expect(error.code).toBe('UNKNOWN');
    expect(error.message).toContain('502');
  });

  it('survives an empty error body', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 429 }));

    const error = (await api.get('/x').catch((caught: unknown) => caught)) as ApiError;

    expect(error.isRateLimited).toBe(true);
  });

  it('classifies the statuses the UI reacts to', async () => {
    for (const [status, property] of [
      [403, 'isForbidden'],
      [404, 'isNotFound'],
      [409, 'isConflict'],
      [429, 'isRateLimited'],
    ] as const) {
      fetchMock.mockResolvedValue(new Response(null, { status }));
      const error = (await api.get('/x').catch((caught: unknown) => caught)) as ApiError;
      expect(error[property], `status ${status}`).toBe(true);
    }
  });
});

describe('refreshing on 401', () => {
  it('refreshes once and retries the request', async () => {
    const tokens = stubTokens();
    configureClient(tokens);

    fetchMock
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(jsonResponse(200, { payments: 1 }));

    const body = await api.get<{ payments: number }>('/api/admin/analytics/overview');

    expect(body).toEqual({ payments: 1 });
    expect(tokens.refreshCalls()).toBe(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  /**
   * <strong>The assertion that prevents a request storm.</strong> A second 401 after a successful refresh is
   * the end of it: the error surfaces rather than triggering another refresh.
   */
  it('does not loop when the retried request also fails', async () => {
    const tokens = stubTokens();
    configureClient(tokens);

    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));

    await expect(api.get('/api/admin/analytics/overview')).rejects.toBeInstanceOf(ApiError);

    expect(tokens.refreshCalls()).toBe(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('reports the session lost when refreshing fails', async () => {
    const tokens = stubTokens({ refresh: async () => false });
    configureClient(tokens);

    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));

    await expect(api.get('/api/fraud/stats')).rejects.toBeInstanceOf(ApiError);

    expect(tokens.lostCalls()).toBe(1);
    // No retry: there is nothing to retry with.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  /** A 401 from the login or refresh endpoint is the answer, not a trigger to refresh. */
  it('never refreshes in response to an anonymous request', async () => {
    const tokens = stubTokens();
    configureClient(tokens);

    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));

    await expect(
      api.postAnonymous('/api/auth/login', { email: 'a@b.c', password: 'wrong' }),
    ).rejects.toBeInstanceOf(ApiError);

    expect(tokens.refreshCalls()).toBe(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  /** With no provider wired there is nothing to refresh with, and the 401 must surface unchanged. */
  it('surfaces a 401 unchanged when no provider is configured', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));

    const error = (await request('/api/fraud/stats').catch((caught: unknown) => caught)) as ApiError;

    expect(error.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  /** A 403 is an authorisation problem. Refreshing the token cannot fix it and must not be attempted. */
  it('does not refresh on a 403', async () => {
    const tokens = stubTokens();
    configureClient(tokens);

    fetchMock.mockResolvedValue(new Response(null, { status: 403 }));

    await expect(api.get('/api/admin/analytics/overview')).rejects.toBeInstanceOf(ApiError);

    expect(tokens.refreshCalls()).toBe(0);
  });
});
