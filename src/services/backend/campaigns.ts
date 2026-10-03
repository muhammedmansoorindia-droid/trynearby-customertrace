/**
 * Backend campaign contracts.
 *
 * <p>These mirror the Java records exactly. The backend uses UPPER_SNAKE statuses while the demo
 * store used lower-case words, so {@link toFrontendStatus} is the single place that translation
 * happens.
 */
import { api } from '@/services/api';

/** Statuses as the backend spells them. */
export type BackendCampaignStatus = 'DRAFT' | 'PENDING' | 'ACTIVE' | 'REJECTED' | 'CANCELLED';

/** The demo store's vocabulary, kept so presentational helpers stay unchanged. */
export type FrontendCampaignStatus =
  | 'draft'
  | 'pending_admin'
  | 'approved'
  | 'rejected'
  | 'live'
  | 'completed'
  | 'cancelled';

export type Decision = 'APPROVE' | 'REJECT';

export interface Campaign {
  id: number;
  creatorId: number;
  creatorName: string | null;
  restaurantId: number;
  restaurantName: string | null;
  title: string;
  description: string | null;
  offerText: string;
  discountPercent: number;
  minBillPaise: number | null;
  targetCustomers: number;
  durationDays: number;
  startDate: string;
  endDate: string;
  status: BackendCampaignStatus;
  adminDecision: Decision | null;
  restaurantDecision: Decision | null;
  adminReason: string | null;
  restaurantReason: string | null;
  promoCode: string | null;
  resubmissionCount: number;
  /** The backend tells us whether the caller may submit; we never guess it. */
  canSubmit: boolean;
  canApprove: boolean;
  submittedAt: string | null;
  activatedAt: string | null;
  createdAt: string;
}

export interface StatusHistoryEntry {
  id: number;
  fromStatus: BackendCampaignStatus | null;
  toStatus: BackendCampaignStatus;
  reason: string | null;
  changedByUserId: string | null;
  changedAt: string;
}

export interface CreateCampaignRequest {
  restaurantId: number;
  title: string;
  description?: string;
  offerText: string;
  discountPercent: number;
  minBillPaise?: number | null;
  targetCustomers: number;
  durationDays: number;
  startDate: string;
  endDate: string;
}

/** Spring's page envelope for the paged campaign lists. */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

const PAGE_PARAM = 'page=0&size=50';

/**
 * Maps a backend status onto the vocabulary the existing UI helpers already speak.
 *
 * <p>`PENDING` is the dual-approval state: submitted and awaiting both decisions, so it maps to
 * `pending_admin` rather than to anything implying a single approval. `ACTIVE` is the only
 * redeemable state and maps to `live`.
 */
export function toFrontendStatus(status: BackendCampaignStatus): FrontendCampaignStatus {
  switch (status) {
    case 'DRAFT':
      return 'draft';
    case 'PENDING':
      return 'pending_admin';
    case 'ACTIVE':
      return 'live';
    case 'REJECTED':
      return 'rejected';
    case 'CANCELLED':
      return 'cancelled';
  }
}

/** Type guard so a status filter parameter is never built from an unchecked string. */
function toBackendStatus(value: string): BackendCampaignStatus | undefined {
  const upper = value.toUpperCase();
  return (
    ['DRAFT', 'PENDING', 'ACTIVE', 'REJECTED', 'CANCELLED'] as const
  ).includes(upper as BackendCampaignStatus)
    ? (upper as BackendCampaignStatus)
    : undefined;
}

export interface CampaignQuery {
  status?: string;
  search?: string;
}

/**
 * Fetches campaigns for one portal, optionally filtered server-side.
 *
 * <p>Search filters the returned page rather than being sent to the backend, because the backend's
 * list endpoints paginate and expose no title-search parameter; filtering what was actually
 * fetched keeps the display consistent with the request.
 */
async function fetchScoped(base: string, query: CampaignQuery = {}): Promise<Page<Campaign>> {
  const status = query.status ? toBackendStatus(query.status) : undefined;
  const params = new URLSearchParams(PAGE_PARAM);
  if (status) params.set('status', status);

  const page = await api.get<Page<Campaign>>(`${base}?${params.toString()}`);
  const search = query.search?.trim().toLowerCase();
  if (!search) return page;

  const content = page.content.filter(
    (c) =>
      c.title.toLowerCase().includes(search) ||
      (c.restaurantName ?? '').toLowerCase().includes(search) ||
      (c.creatorName ?? '').toLowerCase().includes(search),
  );
  return { ...page, content };
}

export const campaignsApi = {
  /** Campaigns awaiting the administrator's decision. */
  listForAdmin: (query?: CampaignQuery) => fetchScoped('/api/admin/campaigns', query),
  /** Campaigns for the signed-in creator. */
  listForCreator: (query?: CampaignQuery) => fetchScoped('/api/creator/campaigns', query),
  /** Campaigns addressed to the signed-in restaurant. */
  listForRestaurant: (query?: CampaignQuery) =>
    fetchScoped('/api/restaurants/campaigns', query),

  detailForAdmin: (id: number) => api.get<Campaign>(`/api/admin/campaigns/${id}`),
  detailForCreator: (id: number) => api.get<Campaign>(`/api/creator/campaigns/${id}`),
  detailForRestaurant: (id: number) => api.get<Campaign>(`/api/restaurants/campaigns/${id}`),

  historyForAdmin: (id: number) =>
    api.get<StatusHistoryEntry[]>(`/api/admin/campaigns/${id}/history`),
  historyForCreator: (id: number) =>
    api.get<StatusHistoryEntry[]>(`/api/creator/campaigns/${id}/history`),
  historyForRestaurant: (id: number) =>
    api.get<StatusHistoryEntry[]>(`/api/restaurants/campaigns/${id}/history`),

  create: (body: CreateCampaignRequest) =>
    api.post<Campaign>('/api/creator/campaigns', body),

  /**
   * Submits a draft for review. The backend mints the promo code here, so the response is the
   * authoritative campaign rather than an optimistic local guess.
   */
  submit: (id: number) => api.post<Campaign>(`/api/creator/campaigns/${id}/submit`),

  /** The administrator's half of the dual-approval workflow. */
  approveAsAdmin: (id: number, reason?: string) =>
    api.post<Campaign>(`/api/admin/campaigns/${id}/approve`, { decision: 'APPROVE', reason }),
  rejectAsAdmin: (id: number, reason?: string) =>
    api.post<Campaign>(`/api/admin/campaigns/${id}/reject`, { decision: 'REJECT', reason }),

  /** The restaurant's half of the dual-approval workflow. */
  approveAsRestaurant: (id: number, reason?: string) =>
    api.post<Campaign>(`/api/restaurants/campaigns/${id}/approve`, {
      decision: 'APPROVE',
      reason,
    }),
  rejectAsRestaurant: (id: number, reason?: string) =>
    api.post<Campaign>(`/api/restaurants/campaigns/${id}/reject`, {
      decision: 'REJECT',
      reason,
    }),
};