/**
 * Application shell and routing.
 *
 * <p>The route guard checks the signed-in user's role and sends them to their own portal if they
 * try to reach another one. Deeper permission decisions are not duplicated here: the backend
 * enforces tenant and role rules on every endpoint, and each screen renders from the flags the
 * backend returns.
 */

import { useEffect } from 'react';
import { Route, Routes, useLocation } from 'react-router-dom';

import { selectPortal, useAuthStore } from '@/app/authStore';
import { RoleSelect } from '@/app/RoleSelect';
import { SignIn } from '@/app/SignIn';
import { PortalLayout } from '@/app/PortalLayout';
import { AdminOverview } from '@/app/admin/AdminOverview';
import { AdminCampaigns } from '@/app/admin/AdminCampaigns';
import { AdminAssignments } from '@/app/admin/AdminAssignments';
import { AdminDirectory } from '@/app/admin/AdminDirectory';
import { AdminSubscriptions } from '@/app/admin/AdminSubscriptions';
import { RestaurantDashboard } from '@/app/restaurant/RestaurantDashboard';
import { RestaurantCampaigns } from '@/app/restaurant/RestaurantCampaigns';
import { RestaurantScanner } from '@/app/restaurant/RestaurantScanner';
import { RestaurantBookings } from '@/app/restaurant/RestaurantBookings';
import { RestaurantSubscription } from '@/app/restaurant/RestaurantSubscription';
import { RestaurantAssignments } from '@/app/restaurant/RestaurantAssignments';
import { CreatorDashboard } from '@/app/creator/CreatorDashboard';
import { CreatorCampaigns } from '@/app/creator/CreatorCampaigns';
import { CreatorWizard } from '@/app/creator/CreatorWizard';
import { CreatorBookings } from '@/app/creator/CreatorBookings';
import { CreatorReviews } from '@/app/creator/CreatorReviews';
import { NotificationCentre } from '@/app/NotificationCentre';
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
  const portal = useAuthStore(selectPortal);
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
        <Route path="/trynearby/assignments" element={<AdminAssignments />} />
        <Route path="/trynearby/directory" element={<AdminDirectory />} />
        <Route path="/trynearby/subscriptions" element={<AdminSubscriptions />} />
        <Route path="/restaurant" element={<RestaurantDashboard />} />
        <Route path="/restaurant/campaigns" element={<RestaurantCampaigns />} />
        <Route path="/restaurant/scan" element={<RestaurantScanner />} />
        <Route path="/restaurant/bookings" element={<RestaurantBookings />} />
        <Route path="/restaurant/subscription" element={<RestaurantSubscription />} />
        <Route path="/restaurant/creators" element={<RestaurantAssignments />} />
        <Route path="/creator" element={<CreatorDashboard />} />
        <Route path="/creator/campaigns" element={<CreatorCampaigns />} />
        <Route path="/creator/new" element={<CreatorWizard />} />
        <Route path="/creator/bookings" element={<CreatorBookings />} />
        <Route path="/creator/reviews" element={<CreatorReviews />} />
        {/* One inbox for every portal; the backend scopes it to the signed-in user. */}
        <Route path="/notifications" element={<NotificationCentre />} />
      </Route>
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
