import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactElement, ReactNode } from 'react';
import { ApiError, configureClient } from '../api/client';
import { authApi } from '../api/endpoints';
import type { TokenResponse, UserSummary } from '../api/types';

/**
 * Session state, and the only place tokens are held.
 *
 * ## Where the tokens live, and why
 *
 * The access token is kept **in memory only**. It is the credential that opens
 * every admin endpoint, and a copy in `localStorage` would be readable by any
 * injected script for the token's whole lifetime and would survive a reload after
 * the operator walked away.
 *
 * The refresh token is kept in `sessionStorage`. It has to survive a page reload —
 * an operator who presses F5 in the middle of working a queue of held payments
 * should not be signed out — but `sessionStorage` is scoped to the tab and cleared
 * when the browser closes, which matters on a shared operations machine in a way
 * `localStorage` does not.
 *
 * Honest limitation: an httpOnly, SameSite cookie would be stronger still, because
 * script could not read it at all. The platform issues bearer tokens and the
 * gateway authenticates from the `Authorization` header, so moving to cookies is a
 * backend change (and brings CSRF back into scope). This is the strongest option
 * available without redesigning the token transport.
 */

const REFRESH_TOKEN_KEY = 'aegis.refresh-token';

/**
 * How early to renew, in seconds.
 *
 * Renewing exactly at expiry guarantees a race: the request that carries the
 * still-valid-for-one-more-millisecond token arrives after it expired. A minute of
 * margin also covers modest clock skew between the browser and the auth service.
 */
const RENEW_MARGIN_SECONDS = 60;

/** Never schedule a renewal tighter than this, or a short-lived token becomes a busy loop. */
const MINIMUM_RENEW_DELAY_SECONDS = 5;

export type AuthStatus = 'restoring' | 'authenticated' | 'anonymous';

export interface AuthContextValue {
  status: AuthStatus;
  user: UserSummary | null;
  /** Set when the session ended by itself rather than by the operator, so the login page can say so. */
  expired: boolean;
  login(email: string, password: string): Promise<void>;
  logout(): Promise<void>;
  /** True when the user holds any of the named roles. Accepts bare or ROLE_-prefixed names. */
  hasRole(...roles: string[]): boolean;
  isAdmin: boolean;
  isAnalyst: boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Normalises a role name.
 *
 * The platform carries two representations of the same role: the bare vocabulary
 * stored on the account and in the JWT `roles` claim (`ADMIN`), and the Spring
 * authority derived from it (`ROLE_ADMIN`). Accepting either means a rename on one
 * side cannot silently empty the navigation of an admin who really is an admin.
 */
function normaliseRole(role: string): string {
  const upper = role.toUpperCase();
  return upper.startsWith('ROLE_') ? upper.slice('ROLE_'.length) : upper;
}

export function AuthProvider({ children }: { children: ReactNode }): ReactElement {
  const [status, setStatus] = useState<AuthStatus>('restoring');
  const [user, setUser] = useState<UserSummary | null>(null);
  const [expired, setExpired] = useState(false);

  /*
   * Refs, not state, for everything on the request path.
   *
   * The HTTP client reads the access token synchronously while building a request.
   * Reading it from state would hand it whatever value was captured when the
   * provider last rendered, which after a refresh is the *old* token — producing a
   * 401 immediately after a successful renewal, which is a genuinely baffling bug
   * to chase.
   */
  const accessTokenRef = useRef<string | null>(null);
  const refreshTokenRef = useRef<string | null>(null);

  /**
   * The in-flight refresh, shared by every caller.
   *
   * Refresh tokens rotate: the first exchange invalidates the token it was called
   * with. Six panels loading at once would each fire a refresh, the first would
   * succeed and the other five would fail against a now-revoked token, and the
   * session would be destroyed by its own recovery. Collapsing to one promise is
   * not an optimisation, it is what makes rotation survivable.
   */
  const refreshInFlight = useRef<Promise<boolean> | null>(null);

  const renewTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** Guards against a resolved refresh writing tokens after the operator signed out. */
  const generation = useRef(0);

  const cancelRenewal = useCallback((): void => {
    if (renewTimer.current !== null) {
      clearTimeout(renewTimer.current);
      renewTimer.current = null;
    }
  }, []);

  const clearSession = useCallback(
    (wasExpired: boolean): void => {
      generation.current += 1;
      cancelRenewal();
      refreshInFlight.current = null;
      accessTokenRef.current = null;
      refreshTokenRef.current = null;
      try {
        sessionStorage.removeItem(REFRESH_TOKEN_KEY);
      } catch {
        // Storage can be unavailable (private mode, disabled cookies). The session
        // is already gone from memory, which is the part that matters; failing to
        // tidy up must not throw out of a logout.
      }
      setUser(null);
      setExpired(wasExpired);
      setStatus('anonymous');
    },
    [cancelRenewal],
  );

  /** Declared as a ref so `adopt` can schedule a renewal that calls back into refresh. */
  const refreshRef = useRef<() => Promise<boolean>>(async () => false);

  const adopt = useCallback(
    (tokens: TokenResponse): void => {
      accessTokenRef.current = tokens.accessToken;
      refreshTokenRef.current = tokens.refreshToken;
      try {
        sessionStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken);
      } catch {
        // Without storage the session simply does not survive a reload. Degraded,
        // not broken — and preferable to refusing to sign in at all.
      }
      setUser(tokens.user);
      setExpired(false);
      setStatus('authenticated');

      // Renew ahead of expiry. Waiting for a 401 means one request per page load
      // fails first, and on a POST that is a decision the operator has to repeat.
      cancelRenewal();
      const delay = Math.max(tokens.expiresIn - RENEW_MARGIN_SECONDS, MINIMUM_RENEW_DELAY_SECONDS);
      renewTimer.current = setTimeout(() => {
        void refreshRef.current();
      }, delay * 1000);
    },
    [cancelRenewal],
  );

  const refresh = useCallback(async (): Promise<boolean> => {
    if (refreshInFlight.current) {
      return refreshInFlight.current;
    }

    const token = refreshTokenRef.current;
    if (!token) {
      return false;
    }

    const attemptGeneration = generation.current;
    const attempt = (async (): Promise<boolean> => {
      try {
        const tokens = await authApi.refresh(token);
        // A logout that landed while this was in flight wins. Adopting here would
        // silently resurrect a session the operator deliberately ended.
        if (attemptGeneration !== generation.current) {
          return false;
        }
        adopt(tokens);
        return true;
      } catch (error) {
        // A rejected or expired refresh token is a finished session, and saying so
        // is correct. A network failure is not — reporting "signed out" for a
        // thirty-second gateway blip would throw away a recoverable session, so
        // the token is kept and the next attempt can succeed.
        if (error instanceof ApiError) {
          return false;
        }
        throw error;
      } finally {
        refreshInFlight.current = null;
      }
    })();

    refreshInFlight.current = attempt;
    return attempt;
  }, [adopt]);

  refreshRef.current = refresh;

  const onSessionLostRef = useRef<() => void>(() => {});
  onSessionLostRef.current = useCallback((): void => {
    clearSession(true);
  }, [clearSession]);

  /*
   * Wire the client before the first render commits.
   *
   * `useEffect` would run after children have already mounted and fired their
   * loading requests, and those would go out with no token at all — every panel
   * would flash a 401 on first paint.
   *
   * The callbacks close over refs rather than values, so this runs exactly once
   * and never needs rewiring when the session changes.
   */
  const configured = useRef(false);
  if (!configured.current) {
    configured.current = true;
    configureClient({
      accessToken: () => accessTokenRef.current,
      refresh: () => refreshRef.current(),
      onSessionLost: () => {
        // Deferred: this fires from inside a fetch, and setting React state
        // synchronously there would update a component mid-request.
        setTimeout(() => onSessionLostRef.current(), 0);
      },
    });
  }

  /** Restore a session from a stored refresh token on first mount. */
  useEffect(() => {
    let cancelled = false;

    const stored = ((): string | null => {
      try {
        return sessionStorage.getItem(REFRESH_TOKEN_KEY);
      } catch {
        return null;
      }
    })();

    if (!stored) {
      setStatus('anonymous');
      return;
    }

    refreshTokenRef.current = stored;
    void refresh()
      .then((restored) => {
        if (cancelled) {
          return;
        }
        if (!restored) {
          clearSession(false);
        }
      })
      .catch(() => {
        // The gateway is unreachable rather than the session being invalid. Land on
        // the login page, but keep the stored token so a retry can still recover.
        if (!cancelled) {
          setStatus('anonymous');
        }
      });

    return () => {
      cancelled = true;
    };
  }, [refresh, clearSession]);

  useEffect(() => cancelRenewal, [cancelRenewal]);

  const login = useCallback(
    async (email: string, password: string): Promise<void> => {
      const tokens = await authApi.login(email, password);
      generation.current += 1;
      adopt(tokens);
    },
    [adopt],
  );

  const logout = useCallback(async (): Promise<void> => {
    const token = refreshTokenRef.current;
    // Local state is cleared first, and unconditionally. If revocation fails the
    // operator must still end up signed out on this machine; leaving them signed
    // in because a network call failed is the wrong way for a logout to fail.
    clearSession(false);
    if (!token) {
      return;
    }
    try {
      await authApi.logout(token);
    } catch {
      // Best effort. The token expires on its own schedule regardless.
    }
  }, [clearSession]);

  const roles = useMemo(
    () => new Set((user?.roles ?? []).map(normaliseRole)),
    [user],
  );

  const hasRole = useCallback(
    (...candidates: string[]): boolean => candidates.some((role) => roles.has(normaliseRole(role))),
    [roles],
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      expired,
      login,
      logout,
      hasRole,
      isAdmin: roles.has('ADMIN'),
      isAnalyst: roles.has('FRAUD_ANALYST'),
    }),
    [status, user, expired, login, logout, hasRole, roles],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    // A component rendered outside the provider would otherwise read undefined and
    // fail somewhere unrelated. Failing here names the actual mistake.
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
