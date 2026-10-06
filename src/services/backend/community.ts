/**
 * Reviews, subscriptions, notifications and creator assignments.
 *
 * <p>Grouped in one module because each is small and none is used by every screen.
 */
import { api } from '@/services/api';

// ── Reviews ─────────────────────────────────────────────────────────────────

export interface Review {
  id: number;
  creatorId: number;
  creatorName: string | null;
  restaurantId: number;
  restaurantName: string | null;
  campaignId: number | null;
  rating: number;
  body: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface RestaurantReviewSummary {
  restaurantId: number;
  /** Derived from stored rows; 0 when there are none. Never a placeholder value. */
  averageRating: number;
  totalReviews: number;
  reviews: Review[];
}

export const reviewsApi = {
  create: (body: { restaurantId: number; campaignId?: number; rating: number; body: string }) =>
    api.post<Review>('/api/creator/reviews', body),
  update: (id: number, body: { rating: number; body: string }) =>
    api.put<Review>(`/api/creator/reviews/${id}`, body),
  listForCreator: () => api.get<Review[]>('/api/creator/reviews'),
  /** Null when the creator has not reviewed that restaurant yet. */
  ownReviewOf: (restaurantId: number) =>
    api.get<Review | null>(`/api/creator/reviews/restaurant/${restaurantId}`),
  listForOwnRestaurant: () => api.get<Review[]>('/api/restaurant/reviews'),
  /** Public profile view; readable by any signed-in user. */
  summaryFor: (restaurantId: number) =>
    api.get<RestaurantReviewSummary>(`/api/restaurants/${restaurantId}/reviews`),
};

// ── Subscriptions ───────────────────────────────────────────────────────────

export interface Subscription {
  id: number;
  restaurantId: number;
  planCode: string | null;
  planName: string | null;
  status: 'TRIALING' | 'ACTIVE' | 'PAST_DUE' | 'CANCELLED';
  billingCycle: string;
  amountPaise: number;
  entitled: boolean;
  startedAt: string;
  renewsAt: string | null;
  cancelledAt: string | null;
  updatedAt: string;
}

/** A platform subscription as the TryNearby administrator sees it. */
export interface AdminSubscription {
  id: number;
  restaurantId: number;
  /** Resolved by the backend, so the table never shows a bare id. */
  restaurantName: string | null;
  planCode: string | null;
  planName: string | null;
  status: 'TRIALING' | 'ACTIVE' | 'PAST_DUE' | 'CANCELLED';
  billingCycle: string;
  amountPaise: number;
  entitled: boolean;
  renewsAt: string | null;
}

/** A restaurant as the administrator sees it, with status and workload. */
export interface RestaurantSummary {
  id: number;
  name: string;
  city: string;
  region: string | null;
  status: string;
  ratingCount: number;
  /**
   * Mean rating out of five.
   *
   * <p>Omitted entirely by the backend's `non_null` serialisation when there are no ratings, so this
   * arrives as `undefined` rather than `null`. Check with `== null`, which covers both.
   */
  averageRating?: number;
  subscriptionPlan: string | null;
  subscriptionStatus: string | null;
  activeCreatorCount: number;
  campaignCount: number;
  userId: string | null;
}

/** A creator as the administrator sees it, with status and workload. */
export interface CreatorSummary {
  id: number;
  displayName: string;
  handle: string;
  city: string | null;
  niche: string | null;
  followers: number;
  verified: boolean;
  status: string;
  activeRestaurantCount: number;
  campaignCount: number;
  userId: string | null;
}

/** Statuses the backend accepts for a restaurant or creator profile. */
export const PROFILE_STATUSES = ['ACTIVE', 'SUSPENDED'] as const;

export interface SubscriptionPlan {
  id: number;
  code: string;
  name: string;
  monthlyPricePaise: number;
  features: string | null;
  maxCampaigns: number;
}

export const subscriptionsApi = {
  /** 404 when the restaurant has never subscribed; the UI shows that as an empty state. */
  current: () => api.get<Subscription>('/api/restaurant/subscription'),
  history: () => api.get<Subscription[]>('/api/restaurant/subscriptions'),
  plans: () => api.get<SubscriptionPlan[]>('/api/plans'),
};

// ── Notifications ────────────────────────────────────────────────────────────

export interface NotificationItem {
  id: number;
  type: string;
  title: string;
  body: string | null;
  link: string | null;
  read: boolean;
  createdAt: string;
}

export interface NotificationPage {
  items: NotificationItem[];
  total: number;
  unreadCount: number;
  page: number;
  size: number;
}

export const notificationsApi = {
  list: (options: { unreadOnly?: boolean; page?: number; size?: number } = {}) => {
    const params = new URLSearchParams();
    if (options.unreadOnly) params.set('unreadOnly', 'true');
    params.set('page', String(options.page ?? 0));
    params.set('size', String(options.size ?? 20));
    return api.get<NotificationPage>(`/api/notifications?${params.toString()}`);
  },
  unreadCount: () => api.get<{ unreadCount: number }>('/api/notifications/unread-count'),
  markRead: (id: number) => api.post<NotificationItem>(`/api/notifications/${id}/read`),
  markAllRead: () => api.post<{ updated: number }>('/api/notifications/read-all'),
};

// ── Creator assignments ──────────────────────────────────────────────────────

export interface Assignment {
  id: number;
  restaurantId: number;
  creatorId: number;
  active: boolean;
  assignedBy: number | null;
  createdAt: string;
  updatedAt: string;
  /** Null when the profile has since been deleted. */
  restaurantName: string | null;
  creatorName: string | null;
}

/** One selectable creator or restaurant for the admin pickers. */
export interface DirectoryEntry {
  id: number;
  name: string;
  /** Handle, city, or `… · inactive` for a deactivated profile. */
  subtitle: string;
}

/**
 * Platform administration: profile status and subscriptions.
 *
 * <p>Every route is administrator-only on the backend, so these calls are for the TryNearby portal.
 */
export const adminApi = {
  restaurants: () => api.get<RestaurantSummary[]>('/api/admin/restaurants'),
  setRestaurantStatus: (id: number, status: string) =>
    api.patch<RestaurantSummary>(`/api/admin/restaurants/${id}/status`, { status }),
  creators: () => api.get<CreatorSummary[]>('/api/admin/creators'),
  setCreatorStatus: (id: number, status: string) =>
    api.patch<CreatorSummary>(`/api/admin/creators/${id}/status`, { status }),
  subscriptions: () => api.get<AdminSubscription[]>('/api/admin/subscriptions'),
  /** Plan and status changes the restaurant is told about by notification. */
  changeSubscriptionPlan: (id: number, planId: number) =>
    api.put<AdminSubscription>(`/api/admin/subscriptions/${id}/plan`, { planId }),
  changeSubscriptionStatus: (id: number, status: string) =>
    api.patch<AdminSubscription>(`/api/admin/subscriptions/${id}/status`, { status }),
};

export const assignmentsApi = {
  listForRestaurant: () => api.get<Assignment[]>('/api/restaurant/assignments'),
  listForCreator: () => api.get<Assignment[]>('/api/creator/assignments'),
  listAll: () => api.get<Assignment[]>('/api/admin/assignments'),
  /** Administrator pickers; the server refuses these to non-admins. */
  creatorDirectory: () => api.get<CreatorSummary[]>('/api/admin/creators'),
  restaurantDirectory: () => api.get<RestaurantSummary[]>('/api/admin/restaurants'),
  /** A restaurant may only ever assign to itself; its id comes from the token. */
  assignAsRestaurant: (creatorId: number) =>
    api.post<Assignment>('/api/restaurant/assignments', { creatorId }),
  assignAsAdmin: (restaurantId: number, creatorId: number) =>
    api.post<Assignment>('/api/admin/assignments', { restaurantId, creatorId }),
  deactivateAsRestaurant: (id: number) =>
    api.delete<Assignment>(`/api/restaurant/assignments/${id}`),
  deactivateAsAdmin: (id: number) => api.delete<Assignment>(`/api/admin/assignments/${id}`),
  reactivate: (id: number) =>
    api.post<Assignment>(`/api/admin/assignments/${id}/reactivate`),
};