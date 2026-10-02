import { Link } from 'react-router-dom';

import { Callout } from '@/app/ui';

/**
 * Portal selection — the entry point.
 *
 * <p>Exactly three buttons, each opening that portal's own sign-in page. There are deliberately
 * no restaurant or creator pickers here: identity is established by signing in, so the account
 * is proven by the backend rather than asserted by this screen.
 */
const PORTALS = [
  {
    id: 'trynearby' as const,
    title: 'TryNearby',
    subtitle: 'Platform governance, campaign approvals and oversight.',
    icon: 'shield',
    iconClass: 'bg-secondary-container text-secondary',
  },
  {
    id: 'restaurant' as const,
    title: 'Restaurant',
    subtitle: 'Campaign requests, QR scans, redemptions and reports.',
    icon: 'restaurant',
    iconClass: 'bg-tertiary-container text-on-tertiary-fixed-variant',
  },
  {
    id: 'creator' as const,
    title: 'Creator',
    subtitle: 'Create campaigns, track approvals and see performance.',
    icon: 'campaign',
    iconClass: 'bg-primary-fixed text-on-primary-fixed-variant',
  },
];


export function RoleSelect() {
  return (
    <main className="min-h-screen bg-background">
      <a href="#portal-picker" className="ct-skip-link">
        Skip to portal selection
      </a>

      <div className="ct-canvas max-w-lg px-gutter py-margin-lg">
        <header className="text-center pt-space-xl pb-space-lg">
          <p className="font-label-sm text-label-sm uppercase tracking-[0.2em] text-primary">
            Customer Trace
          </p>
          <h1 className="mt-2 font-display-md text-display-md text-on-surface">
            Choose your portal
          </h1>
          <p className="mx-auto mt-2 max-w-xs font-body-md text-body-md text-on-surface-variant">
            Sign in with the User ID issued to you. Each account belongs to exactly one portal.
          </p>
        </header>

        <div id="portal-picker" className="space-y-space-md">
          {PORTALS.map((portal) => (
            <Link
              key={portal.id}
              to={`/signin/${portal.id}`}
              className="group flex items-center gap-space-md rounded-xl border border-outline-variant/30
                bg-surface-container-lowest p-space-lg shadow-elevate-low transition-all
                hover:border-primary hover:bg-surface-container-high active:scale-[0.99]"
            >
              <span
                className={`w-12 h-12 rounded-xl flex items-center justify-center flex-shrink-0 ${portal.iconClass}`}
              >
                <span className="material-symbols-outlined text-[28px]" aria-hidden="true">
                  {portal.icon}
                </span>
              </span>
              <span className="flex flex-col">
                <span className="font-headline-sm text-headline-sm font-bold text-on-surface">
                  {portal.title}
                </span>
                <span className="font-body-sm text-body-sm text-on-surface-variant">
                  {portal.subtitle}
                </span>
              </span>
              <span
                className="material-symbols-outlined text-[24px] text-on-surface-variant ml-auto
                  group-hover:translate-x-1 transition-transform"
                aria-hidden="true"
              >
                arrow_forward
              </span>
            </Link>
          ))}
        </div>

        <div className="mt-space-xl">
          <Callout tone="info">
            <strong className="font-label-md">How the data works.</strong> A QR scan is
            <em> interest</em>, not a sale. Only a redemption recorded at the till counts as a
            verified customer — which is why the funnel numbers differ at every step.
          </Callout>
        </div>
      </div>
    </main>
  );
}
