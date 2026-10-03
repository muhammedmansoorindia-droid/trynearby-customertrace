/**
 * Live integration: the real UI components against a running backend.
 *
 * <p>Unlike the unit tests in this directory, `fetch` is NOT stubbed here — every request goes to
 * http://localhost:8080 over real HTTP. This is what proves the screens render data the backend
 * actually returns, rather than a fixture shaped like it.
 *
 * <p>Skipped automatically when the backend is not running, so the suite stays green offline.
 */
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, describe, expect, it } from 'vitest';

import { AdminOverview } from '@/app/admin/AdminOverview';
import { useAuthStore } from '@/app/authStore';
import { getAccessToken, setAccessToken } from '@/services/api';

const BASE = 'http://localhost:8080';
const PASSWORD = process.env.QA_DEV_PASSWORD ?? 'DevOnly!Pass123';

async function signIn(userId: string, portal: string) {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId, password: PASSWORD, portal }),
  });
  if (!res.ok) throw new Error(`login failed: ${res.status}`);
  const data = await res.json();
  setAccessToken(data.accessToken);
  const me = await (
    await fetch(`${BASE}/api/auth/me`, { headers: { Authorization: `Bearer ${data.accessToken}` } })
  ).json();
  useAuthStore.setState({ user: me, initialising: false });
  return data.accessToken;
}

beforeAll(async () => {
  // Fails fast with a clear message rather than a confusing per-test error when the backend
  // is not running; the whole describe block is opt-in via QA_LIVE=1 anyway.
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId: 'admin@Trynearby', password: PASSWORD, portal: 'trynearby' }),
  });
  if (!res.ok) throw new Error(`backend at ${BASE} is not reachable for live tests`);
});

describe.runIf(process.env.QA_LIVE === '1')('live backend rendering', () => {
  it('admin overview renders real platform figures', async () => {
    await signIn('admin@Trynearby', 'trynearby');
    render(
      <MemoryRouter>
        <AdminOverview />
      </MemoryRouter>,
    );

    // The screen must leave its loading state and show metric tiles with real numbers.
    await waitFor(() => expect(screen.queryByTestId('loading-state')).not.toBeInTheDocument(), {
      timeout: 8000,
    });

    const scanTile = await screen.findByLabelText(/QR & code scans/i);
    const customerTile = screen.getByLabelText(/verified customers/i);
    expect(scanTile).toHaveTextContent(/\d/);
    expect(customerTile).toHaveTextContent(/\d/);

    // The regression this guards: the campaign list is a bare JSON array, so reading `.content`
    // off it yielded undefined and rendered "the queue is clear" despite live rows.
    const rows = await (
      await fetch(`${BASE}/api/admin/campaigns?status=PENDING`, {
        headers: { Authorization: `Bearer ${getAccessToken()}` },
      })
    ).json();
    expect(Array.isArray(rows)).toBe(true);

    const clear = screen.queryByText(/the queue is clear/i);
    if (rows.length === 0) {
      expect(clear).toBeInTheDocument();
    } else {
      // Rows exist in the database, so the UI must NOT claim the queue is empty.
      expect(clear).not.toBeInTheDocument();
    }
  });

  it('a rejected token renders an error state, never fabricated zeros', async () => {
    setAccessToken('invalid-token-for-negative-test');
    render(
      <MemoryRouter>
        <AdminOverview />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getAllByTestId('error-state').length).toBeGreaterThan(0), {
      timeout: 8000,
    });
    expect(screen.queryByTestId('loading-state')).not.toBeInTheDocument();
  });
});