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

/** The decision values the backend records. Note the `-ED` ending, not `APPROVE`. */
export type Decision = 'APPROVED' | 'REJECTED';

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
  /** No title: the backend names the campaign from its generated code. */
  description?: string;
  offerText: string;
  discountPercent: number;
  minBillPaise?: number | null;
  targetCustomers: number;
  durationDays: number;
  startDate: string;
  endDate: string;
}

/**
 * The campaign lists as the backend actually returns them.
 *
 * <p>These endpoints paginate server-side with `page`/`size` but serialise the page as a **bare
 * JSON array**, not Spring's `{content, totalElements}` envelope. Normalising here keeps the
 * screens reading one consistent shape, and {@link totalElements} reports the rows returned on
 * this page — the backend does not expose a separate count.
 */
export interface Page<T> {
  content: T[];
  /** Rows on this page. The backend sends no separate total, so this is the page length. */
  totalElements: number;
  page: number;
  size: number;
}

const DEFAULT_PAGE = 0;
const DEFAULT_SIZE = 50;

/**
 * Normalises a campaign list response into the page shape the screens consume.
 *
 * <p>Accepts either a bare array (what the backend sends) or an envelope (in case that changes),
 * so a future backend fix cannot silently blank these screens again.
 */
function toPage<T>(body: unknown, page: number, size: number): Page<T> {
  if (Array.isArray(body)) {
    const content = body as T[];
    return { content, totalElements: content.length, page, size };
  }
  const envelope = body as Partial<Page<T>> | null;
  const content = envelope?.content ?? [];
  return {
    content,
    totalElements: envelope?.totalElements ?? content.length,
    page: envelope?.page ?? page,
    size: envelope?.size ?? size,
  };
}

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

/** Order a campaign list is returned in. Mirrors the backend's `CampaignSort`. */
export type CampaignSort = 'newest' | 'oldest' | 'updated' | 'target';

export interface CampaignQuery {
  status?: string;
  /** Free-text match on the campaign code, creator name or restaurant name. */
  search?: string;
  /** Server-side ordering. Omit to let the backend apply its default. */
  sort?: CampaignSort;
  /** Zero-based page index passed straight to the backend. */
  page?: number;
  /** Rows per page. */
  size?: number;
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
  const page = query.page ?? DEFAULT_PAGE;
  const size = query.size ?? DEFAULT_SIZE;
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) params.set('status', status);
  if (query.sort) params.set('sort', query.sort);

  // Search and sort are answered by the database, not by filtering the page we happened to
  // receive. Filtering after the fact would only ever see the current page, so a term matching a
  // campaign on page 3 would report "no results" while standing on page 1.
  const term = query.search?.trim();
  if (term) params.set('q', term);

  const body = await api.get<unknown>(`${base}?${params.toString()}`);
  return toPage<Campaign>(body, page, size);
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

  /**
   * The administrator's half of the dual-approval workflow.
   *
   * <p>The decision is implied by the route (`/approve`, `/reject`), so the body carries only an
   * optional reason. Sending a `decision` field would be rejected as malformed.
   */
  approveAsAdmin: (id: number, reason?: string) =>
    api.post<Campaign>(`/api/admin/campaigns/${id}/approve`, { reason }),
  rejectAsAdmin: (id: number, reason?: string) =>
    api.post<Campaign>(`/api/admin/campaigns/${id}/reject`, { reason }),

  /** The restaurant's half of the dual-approval workflow. */
  approveAsRestaurant: (id: number, reason?: string) =>
    api.post<Campaign>(`/api/restaurants/campaigns/${id}/approve`, { reason }),
  rejectAsRestaurant: (id: number, reason?: string) =>
    api.post<Campaign>(`/api/restaurants/campaigns/${id}/reject`, { reason }),
};