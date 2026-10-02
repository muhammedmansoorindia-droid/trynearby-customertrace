import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import { Button, Callout, inputClass } from '@/app/ui';
import { dashboardRouteFor, useAuthStore, type Portal } from '@/app/authStore';

/**
 * Portal-specific sign-in.
 *
 * <p>The selected portal is passed by the route so each portal has its own page. The suffix hint
 * is a convenience for development accounts only — the backend decides access from the stored
 * role, and rejects a mismatch with a clear message.
 */
const PORTAL_COPY: Record<Portal, { label: string; blurb: string; suffix: string }> = {
  trynearby: {
    label: 'TryNearby',
    blurb: 'Platform governance, campaign approvals and oversight.',
    suffix: 'Trynearby',
  },
  restaurant: {
    label: 'Restaurant',
    blurb: 'Campaign requests, QR scans, redemptions and your reports.',
    suffix: 'restrnt',
  },
  creator: {
    label: 'Creator',
    blurb: 'Create campaigns, track approval status and see performance.',
    suffix: 'creator',
  },
};

export function SignIn({ portal }: { portal: Portal }) {
  const navigate = useNavigate();
  const copy = PORTAL_COPY[portal];

  const signIn = useAuthStore((s) => s.signIn);
  const signingIn = useAuthStore((s) => s.signingIn);
  const error = useAuthStore((s) => s.error);
  const connectionError = useAuthStore((s) => s.connectionError);
  const clearError = useAuthStore((s) => s.clearError);

  const [userId, setUserId] = useState('');
  const [password, setPassword] = useState('');

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    clearError();
    const ok = await signIn(userId, password, portal);
    // Only navigate after the backend has confirmed the credentials and role.
    if (ok) navigate(dashboardRouteFor(portal), { replace: true });
  }

  return (
    <main className="min-h-screen bg-background flex items-center justify-center px-gutter">
      <div className="w-full max-w-sm">
        <Link
          to="/"
          className="mb-gutter inline-flex items-center gap-1.5 font-label-md text-label-md text-on-surface-variant hover:text-primary"
        >
          <span className="material-symbols-outlined text-[18px]" aria-hidden="true">
            arrow_back
          </span>
          Back to portal selection
        </Link>

        <div className="rounded-xl border border-outline-variant/40 bg-surface-container-lowest p-space-lg shadow-elevation-low">
          <span className="material-symbols-outlined text-[32px] text-primary" aria-hidden="true">
            {portal === 'trynearby' ? 'shield' : portal === 'restaurant' ? 'restaurant' : 'campaign'}
          </span>

          <h1 className="mt-2 font-headline-sm text-headline-sm text-on-surface font-bold">
            Sign in to {copy.label}
          </h1>
          <p className="mt-1 font-body-sm text-body-sm text-on-surface-variant">{copy.blurb}</p>

          {error ? (
            <div className="mt-gutter" role="alert">
              <Callout tone={connectionError ? 'warning' : 'error'}>{error}</Callout>
            </div>
          ) : null}

          <form className="mt-gutter flex flex-col gap-space-sm" onSubmit={onSubmit} noValidate>
            <div>
              <label
                className="block font-label-md text-label-md text-on-surface mb-1"
                htmlFor="userId"
              >
                User ID
              </label>
              <input
                id="userId"
                name="userId"
                autoComplete="username"
                required
                value={userId}
                onChange={(e) => setUserId(e.target.value)}
                placeholder={`name@${copy.suffix}`}
                className={inputClass}
                aria-describedby="userId-hint"
              />
              <p id="userId-hint" className="mt-1 font-body-sm text-body-sm text-outline">
                Development accounts end with <code>{copy.suffix}</code>.
              </p>
            </div>

            <div>
              <label
                className="block font-label-md text-label-md text-on-surface mb-1"
                htmlFor="password"
              >
                Password
              </label>
              <input
                id="password"
                name="password"
                type="password"
                autoComplete="current-password"
                required
                minLength={8}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className={inputClass}
              />
            </div>

            <Button type="submit" disabled={signingIn} className="mt-space-sm w-full justify-center">
              {signingIn ? 'Signing in…' : `Sign in to ${copy.label}`}
            </Button>
          </form>
        </div>
      </div>
    </main>
  );
}