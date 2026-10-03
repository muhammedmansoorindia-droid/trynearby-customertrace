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
}

export const assignmentsApi = {
  listForRestaurant: () => api.get<Assignment[]>('/api/restaurant/assignments'),
  listForCreator: () => api.get<Assignment[]>('/api/creator/assignments'),
  listAll: () => api.get<Assignment[]>('/api/admin/assignments'),
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