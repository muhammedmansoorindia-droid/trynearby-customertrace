/**
 * Portal chrome: top bar, primary navigation and the outlet the routes render into.
 *
 * The nav is derived from the active portal rather than hard-coded per screen, so switching
 * portals can never leave a stale link pointing into another portal's routes.
 */

import { NavLink, Outlet } from 'react-router-dom';

import { useAppStore } from '@/app/store';
import { Badge, Button, EmptyState } from '@/app/ui';
import { cn } from '@/utils';
import type { Portal } from '@/types/models';

const NAV: Record<Exclude<Portal, null>, Array<{ to: string; label: string; icon: string }>> = {
  admin: [
    { to: '/admin', label: 'Overview', icon: 'dashboard' },
    { to: '/admin/campaigns', label: 'Campaigns', icon: 'approval' },
  ],
  restaurant: [
    { to: '/restaurant', label: 'Dashboard', icon: 'dashboard' },
    { to: '/restaurant/scan', label: 'Scan & redeem', icon: 'qr_code_scanner' },
    { to: '/restaurant/campaigns', label: 'Campaigns', icon: 'campaign' },
    { to: '/restaurant/bookings', label: 'Bookings', icon: 'event' },
  ],
  creator: [
    { to: '/creator', label: 'Dashboard', icon: 'dashboard' },
    { to: '/creator/campaigns', label: 'Campaigns', icon: 'campaign' },
    { to: '/creator/new', label: 'New campaign', icon: 'add_circle' },
  ],
};

export function PortalLayout() {
  const session = useAppStore((s) => s.session);
  const db = useAppStore((s) => s.db);
  const signOut = useAppStore((s) => s.signOut);
  const lastError = useAppStore((s) => s.lastError);
  const clearError = useAppStore((s) => s.clearError);

  const portal = session.portal;
  if (!portal) return null;

  const identity =
    portal === 'restaurant'
      ? db.restaurants.find((r) => r.id === session.restaurantId)?.name
      : portal === 'creator'
        ? db.creators.find((c) => c.id === session.creatorId)?.displayName
        : 'Platform governance';

  const unread = db.notifications.filter(
    (n) => n.audience === portal && !n.read && (n.recipientId === null || n.recipientId === session.restaurantId || n.recipientId === session.creatorId),
  ).length;

  return (
    <div className="min-h-screen bg-background">
      <a href="#main" className="ct-skip-link">
        Skip to main content
      </a>

      {/* pt-safe mirrors Stitch's fixed <header class="… pt-safe"> so the sticky bar clears
          the notch on notched devices. Requires viewport-fit=cover (set in index.html). */}
      <header className="sticky top-0 z-50 border-b border-outline-variant/30 bg-surface/85 backdrop-blur pt-safe">
        <div className="ct-canvas flex items-center justify-between gap-space-md px-gutter py-space-sm">
          <div className="flex items-center gap-space-md">
            <span className="font-headline-sm text-headline-sm font-bold text-on-surface">
              Customer Trace
            </span>
            <Badge className="bg-primary-fixed text-on-primary-fixed-variant border-primary-fixed">
              {portal}
            </Badge>
            {identity ? (
              <span className="hidden sm:inline text-body-sm text-body-sm text-on-surface-variant">
                {identity}
              </span>
            ) : null}
          </div>

          <div className="flex items-center gap-space-sm">
            {unread > 0 ? (
              <span
                className="inline-flex items-center gap-1.5 rounded-full bg-primary px-3 py-1
                  font-label-sm text-label-sm text-on-primary"
                title={`${unread} unread notification${unread === 1 ? '' : 's'}`}
              >
                <span className="material-symbols-outlined text-[16px]" aria-hidden="true">
                  notifications
                </span>
                {unread}
              </span>
            ) : null}
            <Button variant="ghost" onClick={signOut}>
              Switch portal
            </Button>
          </div>
        </div>

        <nav aria-label="Primary" className="ct-canvas px-gutter">
          <ul className="ct-scroll-x flex gap-space-xs">
            {NAV[portal].map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  end={item.to === `/${portal}`}
                  className={({ isActive }) =>
                    cn(
                      'flex items-center gap-space-xs whitespace-nowrap rounded-t-lg px-space-md py-space-sm',
                      'font-label-md text-label-md border-b-2 transition-colors',
                      isActive
                        ? 'border-primary text-primary bg-surface-container-low'
                        : 'border-transparent text-on-surface-variant hover:text-on-surface hover:bg-surface-container',
                    )
                  }
                >
                  <span className="material-symbols-outlined text-[18px]" aria-hidden="true">
                    {item.icon}
                  </span>
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      </header>

      <main id="main" className="ct-canvas px-gutter py-gutter">
        {lastError ? (
          <div
            role="alert"
            className="mb-gutter flex items-start justify-between gap-space-md rounded-lg
              border border-error/25 bg-error-container px-space-md py-space-sm
              text-body-md text-body-md text-on-error-container"
          >
            <span>{lastError}</span>
            <button
              type="button"
              onClick={clearError}
              aria-label="Dismiss message"
              className="shrink-0 material-symbols-outlined text-[20px]"
            >
              close
            </button>
          </div>
        ) : null}

        <Outlet />
      </main>
    </div>
  );
}

/** Shown when a portal has no data to render — never a blank screen. */
export function NoData({ message }: { message: string }) {
  return <EmptyState title="Nothing here yet" hint={message} />;
}
