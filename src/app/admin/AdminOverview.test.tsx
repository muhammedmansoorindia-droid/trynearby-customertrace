/**
 * Screen-level tests for the async states.
 *
 * <p>These pin the behaviour that keeps mock data from creeping back in: while a request is in
 * flight the screen shows a loading state, a failure shows an error with a working retry, and a
 * successful-but-empty response shows an empty state rather than a blank panel.
 *
 * <p>`fetch` is stubbed, so no backend is required to run them.
 */
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AdminOverview } from '@/app/admin/AdminOverview';
import { setAccessToken } from '@/services/api';

const fetchMock = vi.fn();

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  setAccessToken('test-token');
});

/** Renders the admin overview with the router it expects. */
function renderOverview() {
  return render(
    <MemoryRouter>
      <AdminOverview />
    </MemoryRouter>,
  );
}

const EMPTY_PLATFORM = {
  totalRestaurants: 0,
  totalCreators: 0,
  totalCampaigns: 0,
  activeCampaigns: 0,
  pendingCampaigns: 0,
  rejectedCampaigns: 0,
  totalBookings: 0,
  activeSubscriptions: 0,
  totalScans: 0,
  totalRedemptions: 0,
  totalVerifiedCustomers: 0,
  conversionRatePercent: 0,
};

const EMPTY_PAGE = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 50 };

describe('AdminOverview async states', () => {
  it('shows a loading state before the first response arrives', async () => {
    // Never resolves, so the screen stays in its loading state for the assertion.
    fetchMock.mockImplementation(() => new Promise(() => {}));

    renderOverview();

    // Several panels load independently, so more than one loading state may be present.
    expect((await screen.findAllByTestId('loading-state')).length).toBeGreaterThan(0);
  });

  it('renders an empty state rather than blank panels when there is no data', async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).includes('/summary')) return jsonResponse(EMPTY_PLATFORM);
      return jsonResponse(EMPTY_PAGE);
    });

    renderOverview();

    // The queue panel reports "clear"; the metric tiles read zero.
    expect(await screen.findByText(/the queue is clear/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/verified customers/i)).toBeInTheDocument();
  });

  it('shows an error with a retry button when the request fails', async () => {
    fetchMock.mockImplementation(async () => jsonResponse({ message: 'Boom' }, 500));

    renderOverview();

    // The summary and the queue fail independently, so more than one error panel can appear.
    expect((await screen.findAllByTestId('error-state')).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('button', { name: /retry/i }).length).toBeGreaterThan(0);
  });

  it('says the API is unreachable when the transport fails', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    renderOverview();

    // A connection problem is named explicitly, rather than a vague "something went wrong".
    const alerts = await screen.findAllByTestId('error-state');
    // A connection problem is named explicitly, rather than a vague "something went wrong".
    expect(alerts[0]).toHaveTextContent(/could not reach/i);
  });

  it('re-requests on retry and recovers', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    fetchMock.mockImplementation(async (url: string) => {
      attempt += 1;
      if (attempt <= 2) return jsonResponse({ message: 'Boom' }, 500);
      if (String(url).includes('/summary')) return jsonResponse(EMPTY_PLATFORM);
      return jsonResponse(EMPTY_PAGE);
    });

    renderOverview();

    await screen.findAllByTestId('error-state');
    // Each panel owns its retry, so every one has to be clicked to clear all errors.
    for (const retry of screen.getAllByRole('button', { name: /retry/i })) {
      await user.click(retry);
    }

    await waitFor(() => expect(screen.queryAllByTestId('error-state')).toHaveLength(0));
    expect(await screen.findByText(/the queue is clear/i)).toBeInTheDocument();
  });

  it('does not present scans as verified customers', async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).includes('/summary')) {
        return jsonResponse({ ...EMPTY_PLATFORM, totalScans: 40, totalVerifiedCustomers: 3 });
      }
      return jsonResponse(EMPTY_PAGE);
    });

    renderOverview();

    // Both numbers appear, and the customer figure is distinct from the scan figure.
    // The customer tile reads 3 while the scan tile reads 40 — they are never the same figure.
    const customerTile = await screen.findByLabelText(/verified customers/i);
    await waitFor(() => expect(customerTile).toHaveTextContent('3'));
    expect(screen.getByLabelText(/QR & code scans/i)).toHaveTextContent('40');
  });
});