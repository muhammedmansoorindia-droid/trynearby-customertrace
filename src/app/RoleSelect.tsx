/**
 * Role selection — the entry point.
 *
 * Mirrors the Stitch "role selection" screen. Choosing Admin needs no identity; the
 * Restaurant and Creator portals must pick *which* one, because every downstream query is
 * scoped to that id and silently defaulting to the first record would look like a bug.
 */

import { useNavigate } from 'react-router-dom';

import { useAppStore } from '@/app/store';
import { Callout, inputClass } from '@/app/ui';
import type { Portal } from '@/types/models';

const PORTALS = [
  {
    id: 'admin' as const,
    title: 'Admin',
    subtitle: 'Governance, approvals & platform health',
    icon: 'shield',
    iconClass: 'bg-secondary-container text-secondary',
    route: '/admin',
    needsIdentity: false,
  },
  {
    id: 'restaurant' as const,
    title: 'Restaurant',
    subtitle: 'Partners, offers, scans & redemptions',
    icon: 'restaurant',
    iconClass: 'bg-secondary-container text-secondary',
    route: '/restaurant',
    needsIdentity: true,
  },
  {
    id: 'creator' as const,
    title: 'Creator',
    subtitle: 'Campaigns, QR passes & analytics',
    icon: 'campaign',
    iconClass: 'bg-tertiary-fixed text-tertiary',
    route: '/creator',
    needsIdentity: true,
  },
];

export function RoleSelect() {
  const navigate = useNavigate();
  const db = useAppStore((s) => s.db);
  const setPortal = useAppStore((s) => s.setPortal);
  const resetDemo = useAppStore((s) => s.resetDemo);

  const enter = (portal: Portal, id?: string) => {
    if (portal === 'restaurant' && id) setPortal(portal, { restaurantId: id });
    else if (portal === 'creator' && id) setPortal(portal, { creatorId: id });
    else setPortal(portal);
    navigate(PORTALS.find((p) => p.id === portal)?.route ?? '/');
  };

  return (
    <main className="min-h-screen bg-background">
      <a href="#portal-picker" className="ct-skip-link">
        Skip to portal picker
      </a>

      <div className="ct-canvas max-w-lg px-gutter py-margin-lg">
        <header className="text-center pt-space-xl pb-space-lg">
          <p className="font-label-sm text-label-sm uppercase tracking-[0.2em] text-primary">
            Customer Trace
          </p>
          <h1 className="mt-2 font-display-md text-display-md text-on-surface">
            Who are you signing in as?
          </h1>
          <p className="mx-auto mt-2 max-w-xs font-body-md text-body-md text-on-surface-variant">
            Pick a portal to continue. Everything is seeded locally — no account needed.
          </p>
        </header>

        <div id="portal-picker" className="space-y-space-md">
          {PORTALS.map((portal) => (
            <section key={portal.id} className="space-y-space-sm">
              <button
                type="button"
                onClick={() => {
                  if (portal.needsIdentity) return;
                  enter(portal.id);
                }}
                disabled={portal.needsIdentity}
                className={`w-full flex items-center justify-between p-space-lg rounded-xl text-left
                  shadow-elevate-medium active:scale-[0.99] transition-all duration-200 group
                  ${
                    portal.needsIdentity
                      ? 'bg-surface-container-lowest text-on-surface border border-outline-variant/30 hover:bg-surface-container-high shadow-elevate-low'
                      : 'bg-primary text-on-primary hover:bg-tertiary-container'
                  }`}
              >
                <span className="flex items-center gap-space-md">
                  <span
                    className={`w-12 h-12 rounded-xl flex items-center justify-center flex-shrink-0 ${
                      portal.needsIdentity
                        ? portal.iconClass
                        : 'bg-surface/20 text-on-primary'
                    }`}
                  >
                    <span className="material-symbols-outlined text-[28px]" aria-hidden="true">
                      {portal.icon}
                    </span>
                  </span>
                  <span className="flex flex-col">
                    <span className="font-headline-sm text-headline-sm font-bold">
                      {portal.title}
                    </span>
                    <span
                      className={`text-body-sm text-body-sm ${
                        portal.needsIdentity ? 'text-on-surface-variant' : 'text-on-primary/80'
                      }`}
                    >
                      {portal.subtitle}
                    </span>
                  </span>
                </span>
                {!portal.needsIdentity ? (
                  <span
                    className="material-symbols-outlined text-[24px] group-hover:translate-x-1 transition-transform"
                    aria-hidden="true"
                  >
                    arrow_forward
                  </span>
                ) : null}
              </button>

              {portal.needsIdentity ? (
                <label className="block">
                  <span className="sr-only">Choose a {portal.title.toLowerCase()}</span>
                  <select
                    className={inputClass}
                    value=""
                    onChange={(event) => {
                      if (event.target.value) enter(portal.id, event.target.value);
                    }}
                  >
                    <option value="">
                      {portal.id === 'restaurant'
                        ? 'Choose a restaurant…'
                        : 'Choose a creator…'}
                    </option>
                    {portal.id === 'restaurant'
                      ? db.restaurants.map((r) => (
                          <option key={r.id} value={r.id}>
                            {r.name} · {r.city}
                          </option>
                        ))
                      : db.creators.map((c) => (
                          <option key={c.id} value={c.id}>
                            {c.displayName} · @{c.handle}
                          </option>
                        ))}
                  </select>
                </label>
              ) : null}
            </section>
          ))}
        </div>

        <div className="mt-space-xl space-y-space-md">
          <Callout tone="info">
            <strong className="font-label-md">How the data works.</strong> A QR scan is
            <em> interest</em>, not a sale. Only a redemption recorded at the till counts as a
            verified customer — which is why the funnel numbers differ at every step.
          </Callout>

          <button
            type="button"
            onClick={() => {
              resetDemo();
              navigate('/');
            }}
            className="w-full text-center font-label-sm text-label-sm text-on-surface-variant
              hover:text-primary transition-colors"
          >
            Reset demo data
          </button>
        </div>
      </div>
    </main>
  );
}
