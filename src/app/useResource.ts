/**
 * Data-loading hook for API-backed screens.
 *
 * <p>Centralised so no screen has to reinvent loading flags, and so one rule holds everywhere:
 * **a failed request never renders stale or mock data as if it had succeeded.** On failure the
 * data is cleared and the error is surfaced, because a dashboard quietly showing yesterday's
 * numbers is worse than one showing an error.
 */
import { useCallback, useEffect, useRef, useState } from 'react';

import { ApiError } from '@/services/api';

export interface Resource<T> {
  data: T | null;
  error: ApiError | null;
  loading: boolean;
  /** Re-runs the loader; used by every retry button. */
  reload: () => void;
}

export interface UseResourceOptions {
  /**
   * Skips fetching entirely. Used when a route has no id yet or the user is not entitled, so an
   * impossible request is never sent.
   */
  enabled?: boolean;
}

export function useResource<T>(
  loader: (signal: AbortSignal) => Promise<T>,
  deps: unknown[],
  options: UseResourceOptions = {},
): Resource<T> {
  const { enabled = true } = options;
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [loading, setLoading] = useState(enabled);
  const [nonce, setNonce] = useState(0);

  // Kept in a ref so a loader identity change alone cannot re-trigger the effect.
  const loaderRef = useRef(loader);
  loaderRef.current = loader;

  useEffect(() => {
    if (!enabled) {
      setLoading(false);
      return;
    }

    // An in-flight request is aborted when deps change or the component unmounts, so a slow
    // response cannot overwrite newer data.
    const controller = new AbortController();
    let active = true;
    setLoading(true);
    setError(null);

    loaderRef
      .current(controller.signal)
      .then((result) => {
        if (!active) return;
        setData(result);
        setError(null);
      })
      .catch((cause: unknown) => {
        if (!active || (cause as Error)?.name === 'AbortError') return;
        // Clear the data so the screen cannot show a stale figure next to an error.
        setData(null);
        setError(
          cause instanceof ApiError
            ? cause
            : new ApiError(0, { status: 0, code: 'UNKNOWN', message: String(cause) }),
        );
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
      controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, nonce, ...deps]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);

  return { data, error, loading, reload };
}

export interface Mutation<TArgs extends unknown[], TResult> {
  run: (...args: TArgs) => Promise<TResult | null>;
  pending: boolean;
  error: ApiError | null;
  /** Clears a previous error so a retry starts clean. */
  reset: () => void;
}

/**
 * Wraps a write so that it reports pending/error state and never double-fires.
 *
 * <p>The UI only updates after `run` resolves, which is what keeps a screen from showing an
 * approval that the backend actually refused.
 */
export function useMutation<TArgs extends unknown[], TResult>(
  action: (...args: TArgs) => Promise<TResult>,
): Mutation<TArgs, TResult> {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  // Guards against a double click submitting twice before the first call settles.
  const inFlight = useRef(false);

  const run = useCallback(
    async (...args: TArgs): Promise<TResult | null> => {
      if (inFlight.current) return null;
      inFlight.current = true;
      setPending(true);
      setError(null);
      try {
        return await action(...args);
      } catch (cause) {
        setError(
          cause instanceof ApiError
            ? cause
            : new ApiError(0, { status: 0, code: 'UNKNOWN', message: String(cause) }),
        );
        return null;
      } finally {
        inFlight.current = false;
        setPending(false);
      }
    },
    [action],
  );

  const reset = useCallback(() => setError(null), []);

  return { run, pending, error, reset };
}