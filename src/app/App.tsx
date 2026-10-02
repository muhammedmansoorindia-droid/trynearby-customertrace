/**
 * Application shell and routing.
 *
 * Three portals live in one SPA over one store. The route guard is deliberately thin: it
 * only checks that *a* portal is active and bounces to the role screen otherwise, because
 * every deeper permission decision already lives in `campaignWorkflow`'s transition table.
 * Guarding again here would create a second source of truth for the same rules.
 */

import { useEffect } from 'react';
import { Route, Routes, useLocation } from 'react-router-dom';

import { useAppStore } from '@/app/store';
import { RoleSelect } from '@/app/RoleSelect';
import { SignIn } from '@/app/SignIn';
import { PortalLayout } from '@/app/PortalLayout';
import { AdminOverview } from '@/app/admin/AdminOverview';
import { AdminCampaigns } from '@/app/admin/AdminCampaigns';
import { RestaurantDashboard } from '@/app/restaurant/RestaurantDashboard';
import { RestaurantCampaigns } from '@/app/restaurant/RestaurantCampaigns';
import { RestaurantScanner } from '@/app/restaurant/RestaurantScanner';
import { RestaurantBookings } from '@/app/restaurant/RestaurantBookings';
import { CreatorDashboard } from '@/app/creator/CreatorDashboard';
import { CreatorCampaigns } from '@/app/creator/CreatorCampaigns';
import { CreatorWizard } from '@/app/creator/CreatorWizard';
import { NotFound } from '@/app/NotFound';

/**
 * The router is mounted by the entry point (`src/main.tsx`), not here.
 *
 * Rendering a second <BrowserRouter> inside the one that wraps this component makes React
 * Router throw ("You cannot render a <Router> inside another <Router>"), which unmounts the
 * whole tree and leaves a blank page. Keeping this component router-agnostic also lets tests
 * mount it inside a <MemoryRouter> at any starting route.
 */
export default function App() {
  return <Shell />;
}

function Shell() {
  const portal = useAppStore((s) => s.session.portal);
  const location = useLocation();

  // Move focus to the top of the page on navigation so keyboard and screen-reader users are
  // not left at the bottom of the previous screen.
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [location.pathname]);

  if (!portal) {
    return (
      <Routes>
        <Route path="/" element={<RoleSelect />} />
        {/* Portal-specific sign-in. Each portal has its own page so the suffix hint and
            the expected role are unambiguous. */}
        <Route path="/signin/trynearby" element={<SignIn portal="trynearby" />} />
        <Route path="/signin/restaurant" element={<SignIn portal="restaurant" />} />
        <Route path="/signin/creator" element={<SignIn portal="creator" />} />
        {/* Anything else while signed out falls back to portal selection rather than a
            dead-end NotFound screen. */}
        <Route path="*" element={<RoleSelect />} />
      </Routes>
    );
  }

  return (
    <Routes>
      {/* The portal-selection URL must stay registered even while a portal is active.
          Otherwise "/" falls through to the catch-all below, and NotFound's
          "Back to portal selection" link — which points at "/" — would navigate straight
          back into NotFound, a dead end the user cannot escape. */}
      <Route path="/" element={<RoleSelect />} />

      {/* Portal-specific sign-in. Each portal has its own page so the suffix hint and
          the expected role are unambiguous. */}
      <Route path="/signin/trynearby" element={<SignIn portal="trynearby" />} />
      <Route path="/signin/restaurant" element={<SignIn portal="restaurant" />} />
      <Route path="/signin/creator" element={<SignIn portal="creator" />} />

      <Route element={<PortalLayout />}>
        <Route path="/trynearby" element={<AdminOverview />} />
        <Route path="/trynearby/campaigns" element={<AdminCampaigns />} />
        <Route path="/restaurant" element={<RestaurantDashboard />} />
        <Route path="/restaurant/campaigns" element={<RestaurantCampaigns />} />
        <Route path="/restaurant/scan" element={<RestaurantScanner />} />
        <Route path="/restaurant/bookings" element={<RestaurantBookings />} />
        <Route path="/creator" element={<CreatorDashboard />} />
        <Route path="/creator/campaigns" element={<CreatorCampaigns />} />
        <Route path="/creator/new" element={<CreatorWizard />} />
      </Route>
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
