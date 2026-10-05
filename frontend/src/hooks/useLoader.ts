import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';

/**
 * Loads one thing, and tracks the three states every panel here needs to
 * distinguish: still loading, failed, or loaded.
 *
 * Written by hand rather than pulling in a data-fetching library. The dashboard
 * fetches a handful of endpoints, and what it actually needs from such a library —
 * cancellation, an error that is not just `null`, and a refresh that does not blank
 * the screen — is about sixty lines. What it would also get is a cache with its own
 * staleness model layered on top of a read model that already reports its own
 * staleness, and two disagreeing answers to "how old is this number" is worse than
 * one.
 */

export type LoadState<T> =
  | { status: 'loading'; data: null; error: null }
  | { status: 'ready'; data: T; error: null }
  | { status: 'failed'; data: T | null; error: ApiError | Error };

export interface Loader<T> {
  state: LoadState<T>;
  /** Re-runs the fetch, keeping the current data on screen while it does. */
  reload: () => void;
  /** True during a reload that already has data displayed. */
  refreshing: boolean;
}

/**
 * @param fetcher receives an `AbortSignal` and must pass it to the request.
 * @param deps    re-runs the fetch when these change, like `useEffect`.
 * @param refreshMs polls when set. Off by default.
 */
export function useLoader<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  deps: ReadonlyArray<unknown>,
  refreshMs?: number,
): Loader<T> {
  const [state, setState] = useState<LoadState<T>>({ status: 'loading', data: null, error: null });
  const [refreshing, setRefreshing] = useState(false);
  const [nonce, setNonce] = useState(0);

  /*
   * Held in a ref so the effect below does not depend on it.
   *
   * Callers pass an inline arrow function, which is a new value on every render. In
   * the dependency array that would re-fetch on every render, and since a fetch
   * sets state, every render would cause another render. The `deps` argument is the
   * explicit, honest way to say when a refetch is actually wanted.
   */
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  /**
   * Whether anything is already on screen, mirrored into a ref.
   *
   * The effect reads this to decide between "show a spinner" and "keep the numbers
   * and mark them refreshing". It must not be a dependency: including it would
   * re-run the fetch the moment the first result arrives, forever.
   */
  const hasDataRef = useRef(false);
  hasDataRef.current = state.data !== null;

  useEffect(() => {
    const controller = new AbortController();
    let live = true;

    if (hasDataRef.current) {
      // Keep the previous figures visible during a reload. Dropping back to a
      // spinner makes an auto-refreshing page flicker, and an operator reading a
      // number when it vanishes will assume it changed.
      setRefreshing(true);
    } else {
      setState({ status: 'loading', data: null, error: null });
    }

    fetcherRef
      .current(controller.signal)
      .then((data) => {
        if (live) {
          setState({ status: 'ready', data, error: null });
        }
      })
      .catch((caught: unknown) => {
        if (!live) {
          return;
        }
        // An abort is this component tidying up, not a failure. Rendering it as one
        // would flash an error on every navigation away from a loading page.
        if (caught instanceof DOMException && caught.name === 'AbortError') {
          return;
        }
        // A 401 is handled by the client, which refreshes and retries, and signs out
        // if that fails. By the time an error surfaces here it is a real one.
        setState((previous) => ({
          status: 'failed',
          data: previous.data,
          error: caught instanceof Error ? caught : new Error(String(caught)),
        }));
      })
      .finally(() => {
        if (live) {
          setRefreshing(false);
        }
      });

    return () => {
      live = false;
      // Cancels the in-flight request. Without this, navigating between pages
      // quickly leaves earlier responses to arrive last and overwrite newer data.
      controller.abort();
    };
  }, [...deps, nonce]);

  const reload = useCallback(() => {
    setNonce((value) => value + 1);
  }, []);

  useEffect(() => {
    if (!refreshMs || refreshMs <= 0) {
      return;
    }
    const timer = setInterval(reload, refreshMs);
    return () => clearInterval(timer);
  }, [refreshMs, reload]);

  return { state, reload, refreshing };
}

/**
 * A readable sentence for a failed load.
 *
 * The platform returns a structured error for every failure, so there is no need to
 * guess. Where a status code has an operational meaning the dashboard says it: a 403
 * is a role problem and no amount of retrying will fix it, while a 429 is a wait.
 */
export function describeError(error: Error): string {
  if (!(error instanceof ApiError)) {
    return 'Could not reach the platform. The gateway may be down.';
  }
  if (error.isForbidden) {
    return 'Your role does not allow this. Nothing was changed.';
  }
  if (error.isNotFound) {
    return 'Not found. It may have been removed, or the id may be wrong.';
  }
  if (error.isRateLimited) {
    return 'Rate limited. Wait a few seconds and try again.';
  }
  if (error.status >= 500) {
    // Naming the service being unavailable matters: an operator seeing this on the
    // risk page and not the overview knows which consumer to look at.
    return error.correlationId
      ? `The service failed to answer (${error.code}). Reference: ${error.correlationId}`
      : `The service failed to answer (${error.code}).`;
  }
  return error.correlationId
    ? `${error.message} (reference: ${error.correlationId})`
    : error.message;
}
