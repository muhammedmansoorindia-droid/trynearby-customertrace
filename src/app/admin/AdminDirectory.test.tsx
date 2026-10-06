/**
 * Admin directory and subscription screens.
 *
 * <p>`fetch` is stubbed, so no backend is needed. These pin the behaviour that matters most on a
 * destructive control: a status change is only reported as done once the backend has confirmed it,
 * and a refusal is shown rather than swallowed.
 */
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AdminDirectory } from '@/app/admin/AdminDirectory';
import { AdminSubscriptions } from '@/app/admin/AdminSubscriptions';
import { setAccessToken } from '@/services/api';

const fetchMock = vi.fn();

const RESTAURANT = {
  id: 7,
  name: 'Copper Kitchen',
  city: 'Kochi',
  region: 'Kerala',
  status: 'ACTIVE',
  ratingCount: 4,
  averageRating: 4.5,
  subscriptionPlan: 'Growth',
  subscriptionStatus: 'ACTIVE',
  activeCreatorCount: 2,
  campaignCount: 3,
  userId: 'copper@restrnt',
};

const CREATOR = {
  id: 3,
  displayName: 'Asha Rao',
  handle: '@asha',
  city: 'Kochi',
  niche: 'Food',
  followers: 1200,
  verified: true,
  status: 'ACTIVE',
  activeRestaurantCount: 1,
  campaignCount: 2,
  userId: 'asha@creator',
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** Serves the directory lists, and answers status writes from `onStatus`. */
function stubDirectory(onStatus?: (init?: RequestInit) => Response) {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    const path = String(url);
    if (init?.method === 'PATCH') {
      const status = JSON.parse(String(init.body)).status;
      if (onStatus) return onStatus(init);
      return json(path.includes('/restaurants/') ? { ...RESTAURANT, status } : { ...CREATOR, status });
    }
    if (path.includes('/api/admin/restaurants')) return json([RESTAURANT]);
    if (path.includes('/api/admin/creators')) return json([CREATOR]);
    return json([]);
  });
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  setAccessToken('test-token');
});

describe('AdminDirectory', () => {
  it('lists restaurants with status and workload from the backend', async () => {
    stubDirectory();
    render(<AdminDirectory />);

    expect(await screen.findByText('Copper Kitchen')).toBeInTheDocument();
    expect(screen.getByText(/2 active creators · 3 campaigns/)).toBeInTheDocument();
    expect(screen.getByText(/4\.5★ from 4/)).toBeInTheDocument();
    expect(screen.getByText('Active')).toBeInTheDocument();
  });

  it('says "no ratings yet" rather than showing a zero score', async () => {
    // The backend omits null fields, so an unrated venue has no `averageRating` key at all.
    const { averageRating, ...unrated } = RESTAURANT;
    fetchMock.mockImplementation(async (url: string) =>
      json(String(url).includes('/restaurants') ? [unrated] : [CREATOR]),
    );
    render(<AdminDirectory />);

    expect(await screen.findByText(/no ratings yet/)).toBeInTheDocument();
    expect(screen.queryByText(/0\.0★/)).not.toBeInTheDocument();
  });

  it('suspends a profile and reports it only after the backend confirms', async () => {
    stubDirectory();
    const user = userEvent.setup();
    render(<AdminDirectory />);

    await screen.findByText('Copper Kitchen');
    await user.click(screen.getByRole('button', { name: 'Suspend' }));

    expect(await screen.findByText('Copper Kitchen is now suspended.')).toBeInTheDocument();
    const call = fetchMock.mock.calls.find(([, init]) => init?.method === 'PATCH');
    expect(call?.[0]).toContain('/api/admin/restaurants/7/status');
    expect(JSON.parse(String(call?.[1]?.body))).toEqual({ status: 'SUSPENDED' });
  });

  it('surfaces a refused status change instead of claiming success', async () => {
    stubDirectory(() => json({ code: 'UNKNOWN_STATUS', message: 'Nope.' }, 400));
    const user = userEvent.setup();
    render(<AdminDirectory />);

    await screen.findByText('Copper Kitchen');
    await user.click(screen.getByRole('button', { name: 'Suspend' }));

    // The backend's message is shown, and no success notice is invented.
    expect(await screen.findByText('Nope.')).toBeInTheDocument();
    expect(screen.queryByText(/is now suspended/)).not.toBeInTheDocument();
  });

  it('switches to creators and lists their workload', async () => {
    stubDirectory();
    const user = userEvent.setup();
    render(<AdminDirectory />);

    await screen.findByText('Copper Kitchen');
    await user.click(screen.getByRole('tab', { name: /Creators/ }));

    expect(await screen.findByText('Asha Rao · verified')).toBeInTheDocument();
    expect(screen.getByText(/1,200 followers/)).toBeInTheDocument();
  });

  it('explains an empty directory rather than showing a bare table', async () => {
    fetchMock.mockImplementation(async () => json([]));
    render(<AdminDirectory />);

    expect(await screen.findByText('No restaurants yet')).toBeInTheDocument();
  });
describe('AdminSubscriptions', () => {
  const SUBSCRIPTION = {
    id: 55,
    restaurantId: 7,
    restaurantName: 'Copper Kitchen',
    planCode: 'GROWTH',
    planName: 'Growth',
    status: 'ACTIVE',
    billingCycle: 'MONTHLY',
    amountPaise: 249900,
    entitled: true,
    renewsAt: '2026-11-01T00:00:00Z',
  };

  /** Serves one subscription, echoing any write back with the status the client asked for. */
  function stubSubscriptions(row: Record<string, unknown> = SUBSCRIPTION) {
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      const path = String(url);
      const isWrite = init?.method === 'PATCH' || init?.method === 'PUT';
      if (path.includes('/api/admin/subscriptions') && isWrite) {
        const status = JSON.parse(String(init.body)).status;
        return json({ ...row, status });
      }
      if (path.includes('/api/admin/subscriptions')) return json([row]);
      if (path.includes('/api/plans')) {
        return json([{ id: 1, code: 'STARTER', name: 'Starter' }]);
      }
      return json([]);
    });
  }

  it('lists subscriptions with the restaurant name and entitlement', async () => {
    stubSubscriptions();
    render(<AdminSubscriptions />);

    expect(await screen.findByText('Copper Kitchen')).toBeInTheDocument();
    // Entitled is called out, because it is what decides what the venue can actually do.
    expect(screen.getByText('ACTIVE · entitled')).toBeInTheDocument();
  });

  it('offers only transitions the backend accepts from the current status', async () => {
    stubSubscriptions();
    render(<AdminSubscriptions />);

    await screen.findByText('Copper Kitchen');
    // ACTIVE may become PAST_DUE or CANCELLED, and nothing else.
    expect(screen.getByRole('button', { name: 'Mark past_due' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Mark cancelled' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mark active' })).not.toBeInTheDocument();
  });

  it('treats a cancelled subscription as closed', async () => {
    stubSubscriptions({ ...SUBSCRIPTION, status: 'CANCELLED', entitled: false });
    render(<AdminSubscriptions />);

    expect(await screen.findByText(/it cannot be changed further/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mark active' })).not.toBeInTheDocument();
  });

  it('sends a plan change for the chosen subscription', async () => {
    stubSubscriptions();
    const user = userEvent.setup();
    render(<AdminSubscriptions />);

    await screen.findByText('Copper Kitchen');
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Plan for Copper Kitchen' }),
      '1',
    );
    await user.click(screen.getByRole('button', { name: 'Apply plan' }));

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([url]) =>
        String(url).includes('/api/admin/subscriptions/55/plan'),
      );
      expect(call).toBeDefined();
    });
    expect(await screen.findByText(/Plan updated for Copper Kitchen/)).toBeInTheDocument();
  });

  it('explains when there is nothing to manage', async () => {
    fetchMock.mockImplementation(async () => json([]));
    render(<AdminSubscriptions />);

    expect(await screen.findByText('No subscriptions on record')).toBeInTheDocument();
  });
});
});