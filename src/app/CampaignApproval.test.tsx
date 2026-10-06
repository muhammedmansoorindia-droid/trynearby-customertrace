/**
 * Campaign approval screens: admin queue and restaurant requests.
 *
 * <p>`fetch` is stubbed, so no backend is needed. The behaviours pinned here are the ones that make
 * an approval screen trustworthy: a decision is confirmed before it is sent, a rejection reason is
 * required, success is only claimed once the backend has confirmed, and a refusal is shown rather
 * than swallowed.
 */
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AdminCampaigns } from '@/app/admin/AdminCampaigns';
import { RestaurantCampaigns } from '@/app/restaurant/RestaurantCampaigns';
import { setAccessToken } from '@/services/api';

const fetchMock = vi.fn();

function campaign(over: Record<string, unknown> = {}) {
  return {
    id: 7,
    creatorId: 1,
    creatorName: 'Pooja Ramachandran',
    restaurantId: 2,
    restaurantName: 'Copper Chimney',
    title: 'AB5010E',
    description: null,
    offerText: 'Flat 5% Off',
    discountPercent: 5,
    minBillPaise: null,
    targetCustomers: 50,
    durationDays: 10,
    startDate: '2026-09-01',
    endDate: '2026-09-30',
    status: 'PENDING',
    adminDecision: null,
    restaurantDecision: null,
    adminReason: null,
    restaurantReason: null,
    promoCode: 'AB5010E',
    resubmissionCount: 0,
    canSubmit: false,
    // The backend's own flag; the screens must not second-guess it.
    canApprove: true,
    submittedAt: '2026-09-01T10:00:00Z',
    activatedAt: null,
    createdAt: '2026-09-01T09:00:00Z',
    ...over,
  };
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/**
 * Serves a campaign list, and decides what a decision write answers.
 *
 * `onDecision` is what lets a test prove the UI only claims success when the backend agrees.
 */
function stubApi(rows: unknown[], onDecision?: (decision: string) => Response) {
  fetchMock.mockImplementation(async (url: string) => {
    const path = String(url);
    if (path.includes('/approve') || path.includes('/reject')) {
      const decision = path.endsWith('/approve') ? 'approve' : 'reject';
      if (onDecision) return onDecision(decision);
      return json(campaign({ status: decision === 'approve' ? 'PENDING' : 'REJECTED' }));
    }
    if (path.includes('/history')) return json([]);
    if (path.includes('/campaigns')) return json(rows);
    if (path.includes('/reports')) return json([]);
    return json([]);
  });
}

function renderAdmin(entry = '/trynearby/campaigns') {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route path="/trynearby/campaigns" element={<AdminCampaigns />} />
      </Routes>
    </MemoryRouter>,
  );
}

function renderRestaurant() {
  return render(
    <MemoryRouter initialEntries={['/restaurant/campaigns']}>
      <Routes>
        <Route path="/restaurant/campaigns" element={<RestaurantCampaigns />} />
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * Whether any request so far carried this query fragment.
 *
 * <p>Two resources load in parallel — the filtered list and the unfiltered one behind the tab
 * counts — so an assertion names a fragment rather than a position in the call list.
 */
function requested(fragment: string): boolean {
  return fetchMock.mock.calls.some(([url]) => String(url).includes(fragment));
}


/** The campaign card heading. The code also appears in the promo-code field, so anchor on the role. */
async function findCampaign() {
  return screen.findByRole('heading', { name: 'AB5010E' });
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  setAccessToken('test-token');
});

describe('AdminCampaigns', () => {
  it('sends the search term to the server rather than filtering the page in hand', async () => {
    stubApi([campaign()]);
    const user = userEvent.setup();
    renderAdmin();

    await findCampaign();
    await user.type(screen.getByLabelText('Search campaigns'), 'Copper');

    await waitFor(() => expect(requested('q=Copper')).toBe(true));
  });

  it('sends the chosen order to the server', async () => {
    stubApi([campaign()]);
    const user = userEvent.setup();
    renderAdmin();

    await findCampaign();
    await user.selectOptions(screen.getByLabelText('Sort campaigns'), 'oldest');

    await waitFor(() => expect(requested('sort=oldest')).toBe(true));
  });

  it('opens on the pending queue when the URL asks for it', async () => {
    stubApi([campaign()]);
    renderAdmin('/trynearby/campaigns?status=PENDING');

    await findCampaign();
    expect(requested('status=PENDING')).toBe(true);
  });

  it('confirms before approving, showing the campaign being approved', async () => {
    stubApi([campaign()]);
    const user = userEvent.setup();
    renderAdmin();

    await findCampaign();
    await user.click(screen.getByRole('button', { name: 'Approve' }));

    // A dialog naming the campaign, not an immediate request.
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Approve this campaign?')).toBeInTheDocument();
    expect(within(dialog).getByText('Copper Chimney')).toBeInTheDocument();
    expect(within(dialog).getByText('Pooja Ramachandran')).toBeInTheDocument();
    expect(within(dialog).getByText('5%')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/approve'))).toBe(false);

    await user.click(within(dialog).getByRole('button', { name: 'Approve campaign' }));
    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/approve'))).toBe(true),
    );
  });

  it('cancelling the confirmation sends nothing', async () => {
    stubApi([campaign()]);
    const user = userEvent.setup();
    renderAdmin();

    await findCampaign();
    await user.click(screen.getByRole('button', { name: 'Approve' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/approve'))).toBe(false);
  });

  it('reports success and refetches after an approval', async () => {
    stubApi([campaign()]);
    const user = userEvent.setup();
    renderAdmin();

    await findCampaign();
    const before = fetchMock.mock.calls.length;
    await user.click(screen.getByRole('button', { name: 'Approve' }));
    await user.click(await screen.findByRole('button', { name: 'Approve campaign' }));

    expect(
      await screen.findByText(/AB5010E approved\. It now waits on the restaurant\./),
    ).toBeInTheDocument();
    // Refetched rather than patched optimistically.
    await waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThan(before));
  });

  it('reports success and refetches after an approval', async () => {
    stubApi([campaign()]);
    const user = userEvent.setup();
    renderAdmin();

    await findCampaign();
    const before = fetchMock.mock.calls.length;
    await user.click(screen.getByRole('button', { name: 'Approve' }));
    await user.click(await screen.findByRole('button', { name: 'Approve campaign' }));

    expect(
      await screen.findByText(/AB5010E approved\. It now waits on the restaurant\./),
    ).toBeInTheDocument();
    // Refetched rather than patched optimistically.
    await waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThan(before));
  });

  it('shows the refusal and claims no success when the backend declines', async () => {
    stubApi([campaign()], () =>
      json({ code: 'INVALID_TRANSITION', message: 'Already decided.' }, 409),
    );
    const user = userEvent.setup();
    renderAdmin();

    await findCampaign();
    await user.click(screen.getByRole('button', { name: 'Approve' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Approve campaign' }));

    // Shown both on the page and inside the still-open dialog, so the approver reads the refusal
    // in context instead of the dialog vanishing.
    expect((await screen.findAllByText('Already decided.')).length).toBeGreaterThan(0);
    expect(screen.queryByText(/approved\. It now waits/)).not.toBeInTheDocument();
  });

  it('offers no decision on a campaign the backend says is not decidable', async () => {
    stubApi([campaign({ status: 'ACTIVE', canApprove: false })]);
    renderAdmin();

    await findCampaign();
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
  });

  it('shows a clear empty state when nothing awaits review', async () => {
    stubApi([]);
    renderAdmin();

    expect(await screen.findByText('No campaigns awaiting review')).toBeInTheDocument();
  });

  it('distinguishes an empty queue from an empty search', async () => {
    stubApi([]);
    const user = userEvent.setup();
    renderAdmin();

    await screen.findByText('No campaigns awaiting review');
    await user.type(screen.getByLabelText('Search campaigns'), 'zzz');

    expect(await screen.findByText('No campaigns match that search')).toBeInTheDocument();
  });
});

describe('RestaurantCampaigns', () => {
  it('shows the code, creator, discount and both approval states', async () => {
    stubApi([campaign({ adminDecision: 'APPROVED' })]);
    renderRestaurant();

    expect(await findCampaign()).toBeInTheDocument();
    expect(screen.getByText(/by Pooja Ramachandran/)).toBeInTheDocument();
    expect(screen.getByText(/5% off · 50 customers · 10 days/)).toBeInTheDocument();
    // Both parties named separately, so it is clear the venue's own decision is outstanding.
    expect(screen.getByText('Your approval')).toBeInTheDocument();
    expect(screen.getByText('TryNearby approved — awaiting the restaurant.')).toBeInTheDocument();
  });

  it('approves through a confirmation and reports the outcome', async () => {
    stubApi([campaign({ adminDecision: 'APPROVED' })]);
    const user = userEvent.setup();
    renderRestaurant();

    await findCampaign();
    await user.click(screen.getByRole('button', { name: 'Approve' }));
    await user.click(await screen.findByRole('button', { name: 'Approve campaign' }));

    expect(await screen.findByText(/AB5010E approved\. It is now live\./)).toBeInTheDocument();
  });

  it('requires a rejection reason before it will send one', async () => {
    stubApi([campaign({ adminDecision: 'APPROVED' })]);
    const user = userEvent.setup();
    renderRestaurant();

    await findCampaign();
    await user.click(screen.getByRole('button', { name: 'Reject' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('button', { name: 'Reject campaign' })).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Reason for rejection'), 'Peak hours only.');
    await user.click(within(dialog).getByRole('button', { name: 'Reject campaign' }));

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([url]) => String(url).includes('/reject'));
      expect(JSON.parse(String(call?.[1]?.body)).reason).toBe('Peak hours only.');
    });
    expect(await screen.findByText(/AB5010E rejected\./)).toBeInTheDocument();
  });

  it('hides the decision buttons when the restaurant may not decide', async () => {
    stubApi([campaign({ status: 'ACTIVE', canApprove: false })]);
    renderRestaurant();

    await findCampaign();
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
  });

  it('filters by status', async () => {
    stubApi([campaign({ adminDecision: 'APPROVED' })]);
    const user = userEvent.setup();
    renderRestaurant();

    await findCampaign();
    expect(requested('status=PENDING')).toBe(true);

    await user.click(screen.getByRole('tab', { name: 'Live' }));
    await waitFor(() => expect(requested('status=ACTIVE')).toBe(true));
  });

  it('searches and sorts', async () => {
    stubApi([campaign({ adminDecision: 'APPROVED' })]);
    const user = userEvent.setup();
    renderRestaurant();

    await findCampaign();
    await user.type(screen.getByLabelText('Search campaign requests'), 'Pooja');
    await user.selectOptions(screen.getByLabelText('Sort campaign requests'), 'oldest');

    await waitFor(() => {
      expect(requested('q=Pooja')).toBe(true);
      expect(requested('sort=oldest')).toBe(true);
    });
  });

  it('explains an empty queue', async () => {
    stubApi([]);
    renderRestaurant();

    expect(await screen.findByText('No campaigns awaiting your approval')).toBeInTheDocument();
  });
});

// ── The detail modal is where the review actually happens ───────────────────

describe('campaign detail modal', () => {
  /** Opens the modal from the admin queue by following the card's view action. */
  async function openAdminDetail(user: ReturnType<typeof userEvent.setup>) {
    await findCampaign();
    await user.click(screen.getByRole('button', { name: /view (& approve|details)/i }));
    return screen.findByRole('dialog', { name: 'AB5010E' });
  }

  it('offers TryNearby both decisions while the campaign awaits review', async () => {
    stubApi([campaign()]);
    const user = userEvent.setup();
    renderAdmin();

    const modal = await openAdminDetail(user);
    expect(within(modal).getByRole('button', { name: /approve campaign/i })).toBeInTheDocument();
    expect(within(modal).getByRole('button', { name: /reject campaign/i })).toBeInTheDocument();
  });

  it('approves from the modal through the real endpoint and refreshes', async () => {
    stubApi([campaign()], () => json(campaign({ adminDecision: 'APPROVED', canApprove: false })));
    const user = userEvent.setup();
    renderAdmin();

    const modal = await openAdminDetail(user);
    await user.click(within(modal).getByRole('button', { name: /approve campaign/i }));

    // The confirmation step still guards the decision; nothing is sent on the first click.
    const confirm = await screen.findByRole('dialog', { name: /approve this campaign/i });
    expect(fetchMock.mock.calls.some(([u]) => String(u).includes('/approve'))).toBe(false);

    await user.click(within(confirm).getByRole('button', { name: /^approve campaign$/i }));
    await waitFor(() => {
      expect(fetchMock.mock.calls.some(([u]) => String(u).includes('/approve'))).toBe(true);
    });
    expect(await screen.findByText(/approved/i)).toBeInTheDocument();
  });

  it('requires a rejection reason before the rejection can be sent', async () => {
    stubApi([campaign()]);
    const user = userEvent.setup();
    renderAdmin();

    const modal = await openAdminDetail(user);
    await user.click(within(modal).getByRole('button', { name: /reject campaign/i }));

    const confirm = await screen.findByRole('dialog', { name: /reject this campaign/i });
    const send = within(confirm).getByRole('button', { name: /^reject campaign$/i });
    expect(send).toBeDisabled();
    expect(within(confirm).getByText('A reason is required.')).toBeInTheDocument();

    // Whitespace is not an explanation.
    await user.type(within(confirm).getByLabelText('Reason for rejection'), '   ');
    expect(send).toBeDisabled();

    await user.type(within(confirm).getByLabelText('Reason for rejection'), 'Offer unclear');
    expect(send).toBeEnabled();
    await user.click(send);
    await waitFor(() => {
      expect(fetchMock.mock.calls.some(([u]) => String(u).includes('/reject'))).toBe(true);
    });
  });

  it('shows both decisions to an admin when the restaurant has already approved', async () => {
    // The reported failure case: TryNearby still PENDING while the restaurant has approved. The
    // restaurant's decision must NOT suppress the admin's controls — this is exactly when the
    // admin's approval is what is missing to take the campaign live.
    stubApi([campaign({ adminDecision: null, restaurantDecision: 'APPROVED', canApprove: true })]);
    const user = userEvent.setup();
    renderAdmin();

    const modal = await openAdminDetail(user);
    expect(within(modal).getByRole('button', { name: /approve campaign/i })).toBeInTheDocument();
    expect(within(modal).getByRole('button', { name: /reject campaign/i })).toBeInTheDocument();
    // Both parties' states are visible, so the reviewer knows one approval is already in.
    const decisions = within(modal).getAllByText('PENDING').length;
    expect(decisions).toBeGreaterThan(0);
    expect(within(modal).getAllByText('APPROVED').length).toBeGreaterThan(0);
  });

  it('hides the controls once TryNearby has decided, however the restaurant answered', async () => {
    // Both sub-cases: the restaurant approved and it rejected. Either way it is not the admin's
    // turn, so no decision buttons may appear.
    for (const restaurantDecision of ['APPROVED', 'REJECTED'] as const) {
      stubApi([
        campaign({
          adminDecision: 'APPROVED',
          restaurantDecision,
          status: restaurantDecision === 'REJECTED' ? 'REJECTED' : 'ACTIVE',
          canApprove: false,
        }),
      ]);
      const view = renderAdmin();
      const user = userEvent.setup();
      await openAdminDetail(user);
      expect(screen.queryByRole('button', { name: /^approve campaign$/i })).toBeNull();
      expect(screen.queryByRole('button', { name: /^reject campaign$/i })).toBeNull();
      view.unmount();
    }
  });

  it('offers no buttons once the campaign is decided, and states the outcome', async () => {
    stubApi([campaign({ adminDecision: 'APPROVED', canApprove: false })]);
    const user = userEvent.setup();
    renderAdmin();

    const modal = await openAdminDetail(user);
    expect(within(modal).queryByRole('button', { name: /approve campaign/i })).toBeNull();
    expect(within(modal).queryByRole('button', { name: /reject campaign/i })).toBeNull();
    expect(within(modal).getByText('TryNearby approved this campaign.')).toBeInTheDocument();
  });

  it('reports a rejection outcome rather than offering to approve again', async () => {
    stubApi([campaign({
      status: 'REJECTED',
      adminDecision: 'REJECTED',
      canApprove: false,
      adminReason: 'Offer unclear',
    })]);
    const user = userEvent.setup();
    renderAdmin();

    const modal = await openAdminDetail(user);
    expect(within(modal).queryByRole('button', { name: /approve campaign/i })).toBeNull();
    expect(within(modal).getByText('TryNearby rejected this campaign.')).toBeInTheDocument();
    // The creator's reason is surfaced verbatim.
    expect(within(modal).getByText(/Offer unclear/)).toBeInTheDocument();
  });

  it('lets the restaurant decide from the modal through the restaurant route only', async () => {
    stubApi([campaign({ adminDecision: 'APPROVED' })], () =>
      json(campaign({ adminDecision: 'APPROVED', restaurantDecision: 'APPROVED', status: 'ACTIVE', canApprove: false })),
    );
    const user = userEvent.setup();
    renderRestaurant();

    await findCampaign();
    await user.click(screen.getByRole('button', { name: /view & approve|view details/i }));
    const modal = await screen.findByRole('dialog', { name: 'AB5010E' });

    // TryNearby's approval is shown as context; the decision offered is the restaurant's own.
    await user.click(within(modal).getByRole('button', { name: /approve campaign/i }));
    const confirm = await screen.findByRole('dialog', { name: /approve this campaign/i });
    await user.click(within(confirm).getByRole('button', { name: /^approve campaign$/i }));

    await waitFor(() => {
      const decided = fetchMock.mock.calls.find(([u]) => String(u).includes('/approve'));
      // The deciding party is fixed by the endpoint, so this must be the restaurant route.
      expect(String(decided?.[0])).toContain('/api/restaurants/campaigns/');
      expect(String(decided?.[0])).not.toContain('/api/admin/');
    });
  });

  it('offers a restaurant no decision on a campaign it does not own', async () => {
    stubApi([campaign({ adminDecision: 'APPROVED', canApprove: false })]);
    const user = userEvent.setup();
    renderRestaurant();

    await findCampaign();
    await user.click(screen.getByRole('button', { name: /view details/i }));
    const modal = await screen.findByRole('dialog', { name: 'AB5010E' });

    expect(within(modal).queryByRole('button', { name: /approve campaign/i })).toBeNull();
    expect(within(modal).queryByRole('button', { name: /reject campaign/i })).toBeNull();
  });
});
