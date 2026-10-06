/**
 * Admin creator-assignment screen.
 *
 * <p>`fetch` is stubbed, so no backend is needed. These pin the flow behind the reported bug: an
 * administrator must be able to pick a creator and a restaurant, see the result, and end or restore
 * an assignment — with a duplicate pair refused rather than silently created twice.
 */
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AdminAssignments } from '@/app/admin/AdminAssignments';
import { setAccessToken } from '@/services/api';

const fetchMock = vi.fn();

// Mirrors the summary records the directory endpoints now return. The former
// `{id, name, subtitle}` picker shape was replaced by these richer records.
const CREATORS = [
  {
    id: 3,
    displayName: 'Asha Rao',
    handle: '@asha',
    city: 'Kochi',
    niche: null,
    followers: 100,
    verified: false,
    status: 'ACTIVE',
    activeRestaurantCount: 1,
    campaignCount: 2,
    userId: 'asha@creator',
  },
  {
    id: 4,
    displayName: 'Ben Diaz',
    handle: '@ben',
    city: null,
    niche: null,
    followers: 10,
    verified: false,
    // The one case that matters: an inactive profile must be visibly marked.
    status: 'SUSPENDED',
    activeRestaurantCount: 0,
    campaignCount: 0,
    userId: 'ben@creator',
  },
];
const RESTAURANTS = [
  {
    id: 7,
    name: 'Copper Kitchen',
    city: 'Kochi',
    region: null,
    status: 'ACTIVE',
    ratingCount: 4,
    averageRating: 4.5,
    subscriptionPlan: 'Growth',
    subscriptionStatus: 'ACTIVE',
    activeCreatorCount: 1,
    campaignCount: 3,
    userId: 'copper@restrnt',
  },
  {
    id: 8,
    name: 'Silver Spoon',
    city: 'Kochi',
    region: null,
    status: 'ACTIVE',
    ratingCount: 0,
    // The backend omits null fields, so a venue with no ratings has no `averageRating` key.
    subscriptionPlan: null,
    subscriptionStatus: null,
    activeCreatorCount: 0,
    campaignCount: 0,
    userId: 'silver@restrnt',
  },
];

function assignment(over: Record<string, unknown> = {}) {
  return {
    id: 1,
    restaurantId: 7,
    creatorId: 3,
    active: true,
    assignedBy: 1,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    restaurantName: 'Copper Kitchen',
    creatorName: 'Asha Rao',
    ...over,
  };
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** Routes each URL to the payload that endpoint returns. */
function stubApi(assignments: unknown[] = []) {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    const path = String(url);
    if (path.includes('/api/admin/creators')) return json(CREATORS);
    if (path.includes('/api/admin/restaurants')) return json(RESTAURANTS);
    if (path.includes('/reactivate')) return json(assignment({ active: true }));
    if (path.endsWith('/api/admin/assignments') && init?.method === 'POST') {
      return json(assignment(), 201);
    }
    if (init?.method === 'DELETE') return json(assignment({ active: false }));
    if (path.includes('/api/admin/assignments')) return json(assignments);
    return json([]);
  });
}

function renderScreen() {
  return render(
    <MemoryRouter>
      <AdminAssignments />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  setAccessToken('test-token');
});
describe('AdminAssignments', () => {
  it('offers every creator and restaurant, flagged when inactive', async () => {
    stubApi();
    renderScreen();

    // Labels are built from handle and city, and an inactive profile is marked inline.
    expect(await screen.findByRole('option', { name: 'Asha Rao — @asha · Kochi' })).toBeInTheDocument();
    expect(
      screen.getByRole('option', { name: 'Ben Diaz — @ben · inactive' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Copper Kitchen — Kochi' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Silver Spoon — Kochi' })).toBeInTheDocument();
  });

  it('assigns a selected pair and confirms the outcome by name', async () => {
    const user = userEvent.setup();
    stubApi();
    renderScreen();

    await user.selectOptions(await screen.findByLabelText('Creator'), '3');
    await user.selectOptions(screen.getByLabelText('Restaurant'), '8');
    await user.click(screen.getByRole('button', { name: /assign restaurant/i }));

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(
        ([url, init]) =>
          String(url).endsWith('/api/admin/assignments') && init?.method === 'POST',
      );
      expect(call).toBeDefined();
      expect(JSON.parse((call![1] as RequestInit).body as string)).toEqual({
        restaurantId: 8,
        creatorId: 3,
      });
    });
    expect(await screen.findByText(/asha rao can now run campaigns/i)).toBeInTheDocument();
  });

  it('refuses to submit a pair that is already active', async () => {
    const user = userEvent.setup();
    stubApi([assignment({ restaurantId: 7, creatorId: 3 })]);
    renderScreen();

    await user.selectOptions(await screen.findByLabelText('Creator'), '3');
    await user.selectOptions(screen.getByLabelText('Restaurant'), '7');

    expect(screen.getByRole('button', { name: /assign restaurant/i })).toBeDisabled();
    expect(screen.getByText(/already active/i)).toBeInTheDocument();
  });

  it('reports a duplicate refused by the server rather than showing a false success', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      const path = String(url);
      if (path.includes('/api/admin/creators')) return json(CREATORS);
      if (path.includes('/api/admin/restaurants')) return json(RESTAURANTS);
      if (path.endsWith('/api/admin/assignments') && init?.method === 'POST') {
        return json({ status: 409, code: 'ASSIGNMENT_EXISTS', message: 'Already assigned.' }, 409);
      }
      return json([]);
    });
    renderScreen();

    await user.selectOptions(await screen.findByLabelText('Creator'), '3');
    await user.selectOptions(screen.getByLabelText('Restaurant'), '8');
    await user.click(screen.getByRole('button', { name: /assign restaurant/i }));

    // The server's message is shown verbatim, and no success notice is invented.
    expect(await screen.findByText(/already assigned/i)).toBeInTheDocument();
    expect(screen.queryByText(/can now run campaigns/i)).not.toBeInTheDocument();
  });

  it('separates active from inactive and offers the matching action for each', async () => {
    stubApi([
      assignment({ id: 1, active: true, creatorId: 3, restaurantId: 7 }),
      assignment({ id: 2, active: false, creatorId: 4, restaurantId: 8 }),
    ]);
    renderScreen();

    // Exact names, because /active/i alone would match the inactive section too.
    const active = await screen.findByRole('region', { name: 'Active assignments' });
    const inactive = screen.getByRole('region', { name: 'Inactive assignments' });

    // Only the active row offers Deactivate, and only the inactive one offers Reactivate.
    expect(within(active).getByRole('button', { name: /deactivate/i })).toBeInTheDocument();
    expect(within(active).queryByRole('button', { name: /reactivate/i })).not.toBeInTheDocument();
    expect(within(inactive).getByRole('button', { name: /reactivate/i })).toBeInTheDocument();
    expect(within(inactive).queryByRole('button', { name: /deactivate/i })).not.toBeInTheDocument();
  });

  it('deactivates and reactivates, confirming each with the server message', async () => {
    const user = userEvent.setup();
    stubApi([
      assignment({ id: 1, active: true }),
      assignment({ id: 2, active: false, creatorId: 4 }),
    ]);
    renderScreen();

    await user.click(await screen.findByRole('button', { name: /deactivate/i }));
    expect(await screen.findByText(/assignment ended/i)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /reactivate/i }));
    expect(await screen.findByText(/assignment restored/i)).toBeInTheDocument();

    const reactivate = fetchMock.mock.calls.find(([url]) =>
      String(url).includes('/api/admin/assignments/2/reactivate'),
    );
    expect(reactivate).toBeDefined();
  });

  it('falls back to ids when a profile has been deleted', async () => {
    stubApi([assignment({ restaurantName: null, creatorName: null })]);
    renderScreen();

    expect(await screen.findByText(/Creator #3 → Restaurant #7/)).toBeInTheDocument();
  });

  it('shows an empty state before anything is assigned', async () => {
    stubApi([]);
    renderScreen();

    expect(await screen.findByText(/no active assignments/i)).toBeInTheDocument();
  });

  it('surfaces a load failure with a working retry', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    renderScreen();

    expect((await screen.findAllByTestId('error-state')).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('button', { name: /retry/i }).length).toBeGreaterThan(0);
  });
});