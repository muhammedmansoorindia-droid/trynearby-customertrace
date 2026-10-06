/**
 * Backend client tests.
 *
 * <p>These assert the parts that are easy to get subtly wrong and expensive to notice: that
 * verified customers are never confused with scans, that the status mapping keeps `PENDING` in the
 * dual-approval state, that cross-tenant ids are never sent from the client, and that failures
 * surface as errors rather than as empty data.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, API_BASE_URL, request, setAccessToken } from '@/services/api';
import { allowedNextStatuses, bookingsApi } from '@/services/backend/bookings';
import { campaignsApi, toFrontendStatus } from '@/services/backend/campaigns';
import { assignmentsApi, notificationsApi, reviewsApi } from '@/services/backend/community';
import { reportsApi } from '@/services/backend/reports';
import { explainScanError, scanApi } from '@/services/backend/scan';

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  setAccessToken('test-token');
});

// The cookie tests stub `document`; leaving that in place would leak into later suites.
afterEach(() => {
  vi.unstubAllGlobals();
});

/** Makes every fetch call resolve with `body`. */
function reply(body: unknown, status = 200) {
  // A Response body can only be read once, so each call gets a fresh instance.
  fetchMock.mockImplementation(async () =>
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
}

/** The URL of the nth fetch call, for asserting on request shape. */
function urlOf(index = 0): string {
  return String(fetchMock.mock.calls[index][0]);
}

describe('request', () => {
  it('sends the in-memory bearer token and never a localStorage one', async () => {
    reply({ ok: true });

    await request('/api/ping');

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer test-token');
    // The refresh cookie is deliberately withheld from ordinary API calls.
    expect(init.credentials).toBe('same-origin');
  });

  it('turns a transport failure into a network ApiError, not empty data', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(request('/api/ping')).rejects.toMatchObject({
      name: 'ApiError',
      status: 0,
    });
  });

  it('exposes the backend error code and message on a non-2xx', async () => {
    reply({ code: 'INVALID_STATUS_TRANSITION', message: 'Not allowed.' }, 409);

    await expect(request('/api/bookings/1/status')).rejects.toMatchObject({
      status: 409,
      code: 'INVALID_STATUS_TRANSITION',
      message: 'Not allowed.',
    });
  });

  it('marks 401 as unauthorized and 403 as forbidden', () => {
    expect(new ApiError(401, {}).isUnauthorized).toBe(true);
    expect(new ApiError(403, {}).isForbidden).toBe(true);
    expect(new ApiError(409, {}).isUnauthorized).toBe(false);
  });
});

describe('cookie-authenticated requests', () => {
  /** Mirrors document.cookie holding the readable CSRF cookie the backend issues on login. */
  function withXsrfCookie(value: string | null) {
    vi.stubGlobal('document', { cookie: value === null ? '' : `XSRF-TOKEN=${value}` });
  }

  it('sends both the custom header and the echoed XSRF token', async () => {
    reply({ accessToken: 'fresh' });
    withXsrfCookie('abc-123');

    await request('/api/auth/refresh', { method: 'POST', withCookie: true });

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    const headers = init.headers as Record<string, string>;
    // Spring Security's CSRF repository requires the echo; sending only the custom
    // header 403s, and a reload would sign the user out.
    expect(headers['X-CT-CSRF']).toBe('1');
    expect(headers['X-XSRF-TOKEN']).toBe('abc-123');
    expect(init.credentials).toBe('include');
  });

  it('decodes a percent-encoded cookie value before echoing it', async () => {
    reply({ accessToken: 'fresh' });
    withXsrfCookie('a%20b');

    await request('/api/auth/refresh', { method: 'POST', withCookie: true });

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('a b');
  });

  it('omits the echo when no CSRF cookie has been issued yet', async () => {
    reply({ accessToken: 'fresh' });
    withXsrfCookie(null);

    await request('/api/auth/refresh', { method: 'POST', withCookie: true });

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    const headers = init.headers as Record<string, string>;
    expect(headers['X-CT-CSRF']).toBe('1');
    expect(headers['X-XSRF-TOKEN']).toBeUndefined();
  });

  it('never sends the CSRF headers on ordinary API calls', async () => {
    reply({ ok: true });
    withXsrfCookie('abc-123');

    await request('/api/auth/me');

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    const headers = init.headers as Record<string, string>;
    expect(headers['X-CT-CSRF']).toBeUndefined();
    expect(headers['X-XSRF-TOKEN']).toBeUndefined();
  });
});

describe('campaign status mapping', () => {
  it('keeps PENDING in the dual-approval state and ACTIVE as the live state', () => {
    // PENDING means submitted and awaiting BOTH decisions — mapping it to anything implying a
    // single approval would misrepresent the workflow.
    expect(toFrontendStatus('PENDING')).toBe('pending_admin');
    expect(toFrontendStatus('ACTIVE')).toBe('live');
    expect(toFrontendStatus('DRAFT')).toBe('draft');
    expect(toFrontendStatus('REJECTED')).toBe('rejected');
    expect(toFrontendStatus('CANCELLED')).toBe('cancelled');
  });
});

describe('campaignsApi', () => {
  it('scopes each list to its portal path', async () => {
    reply([]);

    await campaignsApi.listForAdmin();
    expect(urlOf(0)).toContain('/api/admin/campaigns');

    await campaignsApi.listForCreator();
    expect(urlOf(1)).toContain('/api/creator/campaigns');

    await campaignsApi.listForRestaurant();
    expect(urlOf(2)).toContain('/api/restaurants/campaigns');
  });

  it('normalises the bare array the backend returns into a page', async () => {
    // The backend serialises these lists as a plain JSON array, NOT Spring's page envelope.
    // Reading `.content` off the raw body yields undefined and blanks every campaign screen.
    const rows = [
      { id: 1, title: 'One' },
      { id: 2, title: 'Two' },
    ];
    reply(rows);

    const page = await campaignsApi.listForAdmin();
    expect(Array.isArray(page.content)).toBe(true);
    expect(page.content).toHaveLength(2);
    expect(page.totalElements).toBe(2);
  });

  it('still accepts a page envelope if the backend later returns one', async () => {
    reply({ content: [{ id: 7, title: 'Seven' }], totalElements: 9, page: 1, size: 5 });

    const page = await campaignsApi.listForAdmin();
    expect(page.content[0].id).toBe(7);
    // The server's own total wins, so a future envelope is not silently downgraded.
    expect(page.totalElements).toBe(9);
  });

  it('passes page and size through to the backend', async () => {
    reply([]);

    await campaignsApi.listForCreator({ page: 2, size: 10 });
    expect(urlOf(0)).toContain('page=2');
    expect(urlOf(0)).toContain('size=10');
  });

  it('passes a known status through as a filter', async () => {
    reply([]);

    await campaignsApi.listForAdmin({ status: 'PENDING' });
    expect(urlOf(0)).toContain('status=PENDING');
  });

  it('ignores an unrecognised status rather than sending it to the server', async () => {
    reply([]);

    await campaignsApi.listForAdmin({ status: 'NOT_A_STATUS' });
    expect(urlOf(0)).not.toContain('status=');
  });

  it('asks the server to search rather than filtering the page in hand', async () => {
    reply([
      { id: 1, title: 'Biryani Bash', restaurantName: 'Copper', creatorName: 'Pooja' },
      { id: 2, title: 'Dinner Special', restaurantName: 'Silver', creatorName: 'Asha' },
    ]);

    const page = await campaignsApi.listForAdmin({ search: 'biryani' });

    // The term goes to the database. Filtering the returned page here would only ever see the
    // current page, so a match on page 3 would wrongly report "no results".
    expect(urlOf(0)).toContain('q=biryani');
    // Rows are passed through untouched, since the server already did the matching.
    expect(page.content).toHaveLength(2);
  });

  it('sends the requested order to the server', async () => {
    reply([]);

    await campaignsApi.listForAdmin({ sort: 'oldest' });
    expect(urlOf(0)).toContain('sort=oldest');
  });

  it('omits an empty search box rather than sending a blank term', async () => {
    reply([]);

    await campaignsApi.listForAdmin({ search: '   ' });
    expect(urlOf(0)).not.toContain('q=');
  });

  it('sends both halves of the dual-approval workflow to their own endpoints', async () => {
    reply({ id: 1 });

    await campaignsApi.approveAsAdmin(5, 'looks fine');
    expect(urlOf(0)).toContain('/api/admin/campaigns/5/approve');

    await campaignsApi.approveAsRestaurant(5, 'we can host it');
    expect(urlOf(1)).toContain('/api/restaurants/campaigns/5/approve');
  });

  it('sends only a reason, because the route itself carries the decision', async () => {
    reply({ id: 1 });

    await campaignsApi.rejectAsAdmin(5, 'cannot be verified');

    // The backend rejects a body containing a `decision` field as malformed, so the client must
    // not invent one — the `/approve` vs `/reject` path *is* the decision.
    expect(JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string)).toEqual({
      reason: 'cannot be verified',
    });
    expect(urlOf(0)).toContain('/reject');
  });

  it('takes the promo code from the server rather than minting it client-side', async () => {
    reply({ id: 5, promoCode: 'AB1234C' });

    const campaign = await campaignsApi.submit(5);
    expect(campaign.promoCode).toBe('AB1234C');
    expect(urlOf(0)).toContain('/api/creator/campaigns/5/submit');
  });
});

describe('reportsApi', () => {
  it('omits date parameters when no range is given', async () => {
    reply({ totalScans: 0 });

    await reportsApi.platformSummary();
    // Asserted against API_BASE_URL, never a literal host: the base URL is configurable via
    // VITE_API_BASE_URL (it must be, so a phone can reach the API), so a hardcoded
    // "localhost:8080" here would fail for anyone using a LAN address.
    expect(urlOf(0)).toBe(`${API_BASE_URL}/api/reports/platform/summary`);
  });

  it('sends an inclusive from/to range when provided', async () => {
    reply({ totalScans: 0 });

    await reportsApi.restaurantSummary({ from: '2026-01-01', to: '2026-01-31' });
    expect(urlOf(0)).toContain('from=2026-01-01');
    expect(urlOf(0)).toContain('to=2026-01-31');
  });
});

describe('scanApi', () => {
  it('uses the distinct scan and redeem endpoints', async () => {
    reply({ valid: true, verifiedCustomers: 3 });

    await scanApi.scan('AB1234C', 'QR');
    expect(urlOf(0)).toContain('/api/restaurants/scan/scan');

    await scanApi.redeem({ promoCode: 'AB1234C', billPaise: 240000 });
    expect(urlOf(1)).toContain('/api/restaurants/scan/redeem');
  });

  it('looks a code up without writing anything', async () => {
    reply({ promoCode: 'AB1234C' });

    await scanApi.lookup('AB1234C');
    expect((fetchMock.mock.calls[0][1] as RequestInit).method ?? 'GET').toBe('GET');
  });

  it('explains each refusal reason in staff-facing language', () => {
    expect(explainScanError('EXPIRED', 'fallback')).toMatch(/expired/i);
    expect(explainScanError('ALREADY_REDEEMED', 'fallback')).toMatch(/already been redeemed/i);
    expect(explainScanError('TIER_MONTHLY_LIMIT', 'fallback')).toMatch(/once.*this month/i);
    // An unknown code falls back to the server's own wording rather than inventing one.
    expect(explainScanError('SOMETHING_NEW', 'server message')).toBe('server message');
  });
});

describe('bookingsApi', () => {
  it('uses the namespace matching the caller scope', async () => {
    reply({ items: [], total: 0, page: 0, size: 20 });

    await bookingsApi.list('creator');
    expect(urlOf(0)).toContain('/api/creator/bookings');

    await bookingsApi.list('restaurant', { status: 'REQUESTED' });
    expect(urlOf(1)).toContain('/api/restaurant/bookings');
    expect(urlOf(1)).toContain('status=REQUESTED');
  });

  it('offers a restaurant confirm/decline but only a creator cancel', () => {
    expect(allowedNextStatuses('REQUESTED', 'restaurant')).toEqual([
      'CONFIRMED',
      'DECLINED',
      'CANCELLED',
    ]);
    expect(allowedNextStatuses('REQUESTED', 'creator')).toEqual(['CANCELLED']);
  });

  it('offers nothing once a booking is terminal', () => {
    expect(allowedNextStatuses('COMPLETED', 'restaurant')).toEqual([]);
    expect(allowedNextStatuses('CANCELLED', 'restaurant')).toEqual([]);
    expect(allowedNextStatuses('DECLINED', 'restaurant')).toEqual([]);
  });
});

describe('notificationsApi and assignmentsApi', () => {
  it('never sends a recipient id — the server scopes to the token', async () => {
    reply({ items: [], total: 0, unreadCount: 0, page: 0, size: 20 });

    await notificationsApi.list({ unreadOnly: true });
    expect(urlOf(0)).not.toContain('recipient');
    expect(urlOf(0)).toContain('unreadOnly=true');
  });

  it('assigns a restaurant only to itself, with no restaurant id in the body', async () => {
    reply({ id: 1 }, 201);

    await assignmentsApi.assignAsRestaurant(42);
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    // The restaurant comes from the access token, so it is never client-supplied.
    expect(JSON.parse(init.body as string)).toEqual({ creatorId: 42 });
  });

  it('sends both ids when an administrator targets any venue', async () => {
    reply({ id: 1 }, 201);

    await assignmentsApi.assignAsAdmin(7, 42);
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    // Unlike the restaurant route, the admin route must name both sides of the pair.
    expect(JSON.parse(init.body as string)).toEqual({ restaurantId: 7, creatorId: 42 });
    expect(urlOf(0)).toContain('/api/admin/assignments');
  });

  it('reads the admin pickers and the full assignment list', async () => {
    reply([]);

    await assignmentsApi.listAll();
    await assignmentsApi.creatorDirectory();
    await assignmentsApi.restaurantDirectory();

    expect(urlOf(0)).toContain('/api/admin/assignments');
    expect(urlOf(1)).toContain('/api/admin/creators');
    expect(urlOf(2)).toContain('/api/admin/restaurants');
  });

  it('deactivates with DELETE and reactivates via the dedicated route', async () => {
    reply({ id: 5, active: false });

    await assignmentsApi.deactivateAsAdmin(5);
    expect(fetchMock.mock.calls[0][1]?.method).toBe('DELETE');

    await assignmentsApi.reactivate(5);
    expect(urlOf(1)).toContain('/api/admin/assignments/5/reactivate');
    // Reactivate is a POST: it re-enables a row rather than creating one.
    expect(fetchMock.mock.calls[1][1]?.method).toBe('POST');
  });
});

describe('campaign creation', () => {
  it('never sends a title — the backend names the campaign from its generated code', async () => {
    reply({ id: 1, status: 'DRAFT' }, 201);

    await campaignsApi.create({
      restaurantId: 3,
      offerText: '10% off',
      discountPercent: 5,
      targetCustomers: 25,
      durationDays: 7,
      startDate: '2026-10-01',
      endDate: '2026-10-08',
    });

    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body).not.toHaveProperty('title');
  });

  it('sends the discount, target and duration that the code will encode', async () => {
    reply({ id: 1, status: 'DRAFT' }, 201);

    await campaignsApi.create({
      restaurantId: 3,
      offerText: '10% off',
      discountPercent: 9,
      targetCustomers: 25,
      durationDays: 14,
      startDate: '2026-10-01',
      endDate: '2026-10-15',
    });

    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    // 25 customers, 14 days and 9% (letter I) are exactly what KT2514I would state.
    expect(body).toMatchObject({
      discountPercent: 9,
      targetCustomers: 25,
      durationDays: 14,
    });
  });
});

describe('reviewsApi', () => {
  it('reads a restaurant profile through the public summary route', async () => {
    reply({ restaurantId: 3, averageRating: 4.5, totalReviews: 2, reviews: [] });

    const summary = await reviewsApi.summaryFor(3);
    expect(urlOf(0)).toContain('/api/restaurants/3/reviews');
    expect(summary.averageRating).toBe(4.5);
  });
});