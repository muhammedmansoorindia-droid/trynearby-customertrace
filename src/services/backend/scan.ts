/**
 * Scan, lookup and redemption contracts.
 *
 * <p>The distinction this module exists to protect: a **scan records interest only** and never
 * produces a verified customer. Only `redeem` does. Both are separate endpoints on the backend and
 * the UI must never present one as the other.
 */
import { api } from '@/services/api';

export interface LookupResult {
  promoCode: string;
  campaignTitle: string;
  offerText: string;
  restaurantName: string | null;
  restaurantId: number;
  campaignId: number;
  /** `ACTIVE` is the only redeemable campaign state. */
  campaignStatus: string;
  validity: string;
  limitStatus: string;
  redemptions: number;
  verifiedCustomers: number;
  targetCustomers: number;
  exclusiveTier: boolean;
  tierLetter: string | null;
}

export interface ScanResult {
  scanId: number;
  promoCode: string;
  /** False when the code was presented but rejected; the reason is in `message`. */
  valid: boolean;
  message: string;
  campaignId: number | null;
  campaignTitle: string | null;
  offerText: string | null;
  /** Unchanged by a scan — this is why a scan is not a customer. */
  verifiedCustomers: number;
  targetCustomers: number;
}

export interface RedeemResult {
  redemptionId: number;
  promoCode: string;
  campaignId: number;
  campaignTitle: string;
  verifiedCustomers: number;
  targetCustomers: number;
  totalRedemptions: number;
}

export interface RedeemRequest {
  promoCode: string;
  customerRef?: string;
  billPaise?: number;
  discountPaise?: number;
  channel?: 'DINE_IN' | 'TAKEAWAY' | 'DELIVERY';
  tableRef?: string;
  visitDate?: string;
}

export const scanApi = {
  /** Staff look a code up before acting on it. Read-only; creates nothing. */
  lookup: (promoCode: string) =>
    api.get<LookupResult>(`/api/restaurants/scan/lookup?code=${encodeURIComponent(promoCode)}`),

  /** Records that a code was presented. Never creates a verified customer. */
  scan: (promoCode: string, method: 'QR' | 'MANUAL', deviceLabel?: string) =>
    api.post<ScanResult>('/api/restaurants/scan/scan', { promoCode, method, deviceLabel }),

  /** Confirms a verified customer visit. This is the only call that creates one. */
  redeem: (body: RedeemRequest) => api.post<RedeemResult>('/api/restaurants/scan/redeem', body),
};

/**
 * Turns a backend error into staff-facing guidance.
 *
 * <p>The backend's codes are specific and worth showing verbatim; this only adds the "what do I do
 * next" line, and never replaces the server's own message.
 */
export function explainScanError(code: string, fallback: string): string {
  switch (code) {
    case 'INVALID_FORMAT':
      return 'That does not look like a Customer Trace promo code. Codes are 7 characters.';
    case 'UNKNOWN_CODE':
      return 'No campaign matches that code. Check for typos, or ask the customer to resend it.';
    case 'CAMPAIGN_NOT_LIVE':
      return 'This campaign is not live. A campaign must be approved by both TryNearby and the restaurant before it can be redeemed.';
    case 'NOT_STARTED':
      return 'This campaign has not started yet.';
    case 'EXPIRED':
      return 'This campaign has expired.';
    case 'QUOTA_EXHAUSTED':
      return 'This campaign has reached its customer limit.';
    case 'TIER_MONTHLY_LIMIT':
      return 'This exclusive tier has already been used once by this restaurant this month.';
    case 'ALREADY_REDEEMED':
      return 'This code has already been redeemed today for this customer.';
    default:
      return fallback;
  }
}