/**
 * Authentication state.
 *
 * <p>Holds only non-sensitive facts about the session (who is signed in and their roles). The
 * access token itself lives in `services/api.ts` in memory and is never persisted; surviving a
 * reload relies on the HttpOnly refresh cookie.
 */
import { create } from 'zustand';

import {
  ApiError,
  fetchCurrentUser,
  login as apiLogin,
  logout as apiLogout,
  refreshSession,
  setUnauthorizedHandler,
  type AuthUser,
} from '@/services/api';

export type Portal = 'trynearby' | 'restaurant' | 'creator';

export interface AuthState {
  user: AuthUser | null;
  /** True while the initial refresh or a sign-in is in flight. */
  initialising: boolean;
  signingIn: boolean;
  error: string | null;
  /** Set when a call failed because the API could not be reached. */
  connectionError: boolean;

  /** Attempts a silent sign-in from the refresh cookie. Safe to call on every load. */
  restore: () => Promise<void>;
  signIn: (userId: string, password: string, portal: Portal) => Promise<boolean>;
  signOut: () => Promise<void>;
  clearError: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  initialising: true,
  signingIn: false,
  error: null,
  connectionError: false,

  async restore() {
    set({ initialising: true, error: null });
    try {
      const result = await refreshSession();
      if (!result) {
        set({ user: null });
        return;
      }
      // The refresh token proves the session; /me fills in the profile and role ids.
      const user = await fetchCurrentUser();
      set({ user, connectionError: false });
    } catch (error) {
      // A transport failure must not silently look like "signed out"; the UI shows a
      // connection error so the user knows the backend is unreachable.
      const offline = error instanceof ApiError && error.isNetworkError;
      set({ user: null, connectionError: offline });
    } finally {
      set({ initialising: false });
    }
  },

  async signIn(userId, password, portal) {
    set({ signingIn: true, error: null, connectionError: false });
    try {
      await apiLogin(userId.trim(), password, portal);
      const user = await fetchCurrentUser();
      set({ user, signingIn: false });
      return true;
    } catch (error) {
      const offline = error instanceof ApiError && error.isNetworkError;
      set({
        signingIn: false,
        connectionError: offline,
        error:
          error instanceof Error
            ? error.message
            : 'Something went wrong. Please try again.',
      });
      return false;
    }
  },

  async signOut() {
    try {
      await apiLogout();
    } finally {
      set({ user: null, error: null });
    }
  },

  clearError() {
    set({ error: null });
  },
}));

// When any request returns 401, drop the user so the router can send them to sign-in.
setUnauthorizedHandler(() => {
  useAuthStore.setState({ user: null });
});

/** The route a portal lands on after a successful sign-in. */
export function dashboardRouteFor(portal: Portal): string {
  switch (portal) {
    case 'trynearby':
      return '/trynearby';
    case 'restaurant':
      return '/restaurant';
    case 'creator':
      return '/creator';
  }
}

/** True when the signed-in user holds the role backing the given portal. */
export function userCanAccess(user: AuthUser | null, portal: Portal): boolean {
  if (!user) return false;
  const role =
    portal === 'trynearby' ? 'ADMIN' : portal === 'restaurant' ? 'RESTAURANT' : 'CREATOR';
  return user.roles.includes(role);
}

/**
 * The portal the signed-in user belongs to, derived from their roles.
 *
 * <p>Used to drive navigation. When a user somehow holds two portal roles, the first match in
 * `PORTALS` wins so the nav is deterministic rather than arbitrary.
 */
export function portalForRoles(user: AuthUser | null): Portal | undefined {
  if (!user) return undefined;
  return (['trynearby', 'restaurant', 'creator'] as const).find((p) => userCanAccess(user, p));
}

/** Zustand selector form, so components subscribe only to the value they need. */
export const selectPortal = (state: AuthState): Portal | undefined =>
  portalForRoles(state.user);