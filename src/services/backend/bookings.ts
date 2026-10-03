/**
 * Booking contracts.
 *
 * <p>Status strings are passed through untouched: the backend is the authority on which
 * transitions are legal and answers `409 INVALID_STATUS_TRANSITION` for the rest, so the UI never
 * has to duplicate that table to stay correct.
 */
import { api } from '@/services/api';

export type BookingStatus =
  | 'REQUESTED'
  | 'CONFIRMED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'DECLINED';

export interface Booking {
  id: number;
  creatorId: number;
  restaurantId: number;
  /** Date-only `YYYY-MM-DD`. */
  visitDate: string;
  partySize: number;
  status: BookingStatus;
  note: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface BookingHistoryEntry {
  id: number;
  bookingId: number;
  fromStatus: BookingStatus | null;
  toStatus: BookingStatus;
  changedBy: number | null;
  reason: string | null;
  changedAt: string;
}

export interface BookingDetail {
  booking: Booking;
  history: BookingHistoryEntry[];
}

export interface BookingPage {
  items: Booking[];
  total: number;
  page: number;
  size: number;
}

export interface CreateBookingRequest {
  restaurantId: number;
  visitDate: string;
  partySize: number;
  note?: string;
}

export type BookingScope = 'creator' | 'restaurant' | 'admin';

export interface BookingQuery {
  status?: string;
  page?: number;
  size?: number;
}

function bookingParams(query: BookingQuery): string {
  const params = new URLSearchParams();
  if (query.status) params.set('status', query.status);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 20));
  return `?${params.toString()}`;
}

export const bookingsApi = {
  list: (scope: BookingScope, query: BookingQuery = {}) =>
    api.get<BookingPage>(`/api/${scope}/bookings${bookingParams(query)}`),

  detail: (scope: BookingScope, id: number) =>
    api.get<BookingDetail>(`/api/${scope}/bookings/${id}`),

  create: (body: CreateBookingRequest) => api.post<Booking>('/api/creator/bookings', body),

  /**
   * Requests a status change. Only a restaurant may confirm/decline/complete and only a creator
   * may cancel; the backend rejects anything else with a clear code.
   */
  changeStatus: (scope: BookingScope, id: number, status: BookingStatus, reason?: string) =>
    api.patch<Booking>(`/api/${scope}/bookings/${id}/status`, { status, reason }),
};

/** Statuses the backend accepts, in the order they read best in a filter bar. */
export const BOOKING_STATUSES: BookingStatus[] = [
  'REQUESTED',
  'CONFIRMED',
  'COMPLETED',
  'DECLINED',
  'CANCELLED',
];

/**
 * The status changes a given actor may attempt, used only to grey out impossible buttons.
 *
 * <p>Mirrors the backend's allow-list. The server remains authoritative: a permitted-looking
 * button can still be refused, and the resulting error is shown verbatim.
 */
export function allowedNextStatuses(
  current: BookingStatus,
  scope: BookingScope,
): BookingStatus[] {
  const restaurantActions: Partial<Record<BookingStatus, BookingStatus[]>> = {
    REQUESTED: ['CONFIRMED', 'DECLINED', 'CANCELLED'],
    CONFIRMED: ['COMPLETED', 'CANCELLED'],
  };
  const transitions = restaurantActions[current];
  if (!transitions) return [];
  if (scope === 'restaurant' || scope === 'admin') return transitions;
  return transitions.filter((s) => s === 'CANCELLED');
}