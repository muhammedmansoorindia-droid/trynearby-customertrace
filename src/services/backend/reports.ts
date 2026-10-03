/**
 * Reports and analytics contracts.
 *
 * <p>Every figure here comes from persisted rows. In particular `verifiedCustomers` is derived
 * from redemptions on the backend — it is deliberately *not* a scan count, and the UI must not
 * present it as one.
 */
import { api } from '@/services/api';

export interface DateRange {
  /** Inclusive `YYYY-MM-DD`; omitted means "the backend's default window". */
  from?: string;
  to?: string;
}

/** Builds the shared `from`/`to` query string, skipping absent bounds. */
function rangeParams(range?: DateRange): string {
  const params = new URLSearchParams();
  if (range?.from) params.set('from', range.from);
  if (range?.to) params.set('to', range.to);
  const query = params.toString();
  return query ? `?${query}` : '';
}

export interface SummaryReport {
  from: string;
  to: string;
  scans: number;
  redemptions: number;
  verifiedCustomers: number;
  conversionRatePercent: number;
  discountPaise: number;
  billPaise: number;
}

export interface PlatformSummary {
  totalRestaurants: number;
  totalCreators: number;
  totalCampaigns: number;
  activeCampaigns: number;
  pendingCampaigns: number;
  rejectedCampaigns: number;
  totalBookings: number;
  activeSubscriptions: number;
  totalScans: number;
  totalRedemptions: number;
  totalVerifiedCustomers: number;
  conversionRatePercent: number;
}

export interface CampaignPerformance {
  id: number;
  title: string;
  status: string;
  restaurantName: string | null;
  creatorName: string | null;
  promoCode: string | null;
  targetCustomers: number;
  scans: number;
  redemptions: number;
  verifiedCustomers: number;
  conversionRatePercent: number;
  discountPaise: number;
}

export interface TrendPoint {
  day: string;
  scans: number;
  redemptions: number;
}

export interface TrendReport {
  points: TrendPoint[];
}

export interface StatusBreakdownEntry {
  status: string;
  count: number;
}

export interface StatusBreakdown {
  entries: StatusBreakdownEntry[];
}

export const reportsApi = {
  platformSummary: (range?: DateRange) =>
    api.get<PlatformSummary>(`/api/reports/platform/summary${rangeParams(range)}`),
  platformCampaigns: (range?: DateRange) =>
    api.get<CampaignPerformance[]>(`/api/reports/platform/campaigns${rangeParams(range)}`),
  platformTrend: (range?: DateRange) =>
    api.get<TrendReport>(`/api/reports/platform/trend${rangeParams(range)}`),

  restaurantSummary: (range?: DateRange) =>
    api.get<SummaryReport>(`/api/reports/restaurant/summary${rangeParams(range)}`),
  restaurantCampaigns: (range?: DateRange) =>
    api.get<CampaignPerformance[]>(`/api/reports/restaurant/campaigns${rangeParams(range)}`),
  restaurantTrend: (range?: DateRange) =>
    api.get<TrendReport>(`/api/reports/restaurant/trend${rangeParams(range)}`),
  restaurantStatusBreakdown: () =>
    api.get<StatusBreakdown>('/api/reports/restaurant/status-breakdown'),

  creatorSummary: (range?: DateRange) =>
    api.get<SummaryReport>(`/api/reports/creator/summary${rangeParams(range)}`),
  creatorCampaigns: (range?: DateRange) =>
    api.get<CampaignPerformance[]>(`/api/reports/creator/campaigns${rangeParams(range)}`),
  creatorTrend: (range?: DateRange) =>
    api.get<TrendReport>(`/api/reports/creator/trend${rangeParams(range)}`),
  creatorStatusBreakdown: () =>
    api.get<StatusBreakdown>('/api/reports/creator/status-breakdown'),
};

/**
 * Turns a trend series into a sparkline-friendly `{ label, value }` pair list.
 *
 * <p>Sorted by day so the caller can render in chronological order regardless of how the backend
 * grouped the rows.
 */
export function toSparkline(trend: TrendReport): { label: string; value: number }[] {
  return [...trend.points]
    .sort((a, b) => a.day.localeCompare(b.day))
    .map((point) => ({ label: point.day, value: point.scans }));
}