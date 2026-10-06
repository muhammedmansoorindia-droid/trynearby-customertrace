/**
 * The generated campaign code and the discount-letter rule.
 *
 * <p>These pin the two rules a creator depends on: the code always states the real target,
 * duration and discount, and only the two leading letters are ever random. `fetch` is stubbed,
 * so no backend is required.
 */
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CreatorWizard } from '@/app/creator/CreatorWizard';
import { setAccessToken } from '@/services/api';

const fetchMock = vi.fn();

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const ASSIGNMENT = {
  id: 1,
  restaurantId: 7,
  creatorId: 3,
  active: true,
  assignedBy: 1,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  restaurantName: 'Copper Kitchen',
  creatorName: 'Asha Rao',
};

/** The creator has one assigned venue, so the wizard can get past step 0. */
function stubApi(assignments: unknown[] = [ASSIGNMENT]) {
  fetchMock.mockImplementation(async (url: string) => {
    if (String(url).includes('/api/creator/assignments')) return json(assignments);
    return json([]);
  });
}

function renderWizard() {
  return render(
    <MemoryRouter>
      <CreatorWizard />
    </MemoryRouter>,
  );
}

/** Walks from step 0 to the Offer step, where the code preview lives. */
async function reachOfferStep(user: ReturnType<typeof userEvent.setup>) {
  await user.selectOptions(await screen.findByLabelText('Restaurant'), '7');
  await user.click(screen.getByRole('button', { name: /continue/i }));
  await screen.findByText(/campaign code/i);
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  setAccessToken('test-token');
});

describe('restaurant picker', () => {
  it('asks the backend which venues this creator is assigned to', async () => {
    stubApi();
    renderWizard();

    await screen.findByLabelText('Restaurant');

    // The list comes from the creator's own assignments, never from a hardcoded list.
    const call = fetchMock.mock.calls.find(([url]) =>
      String(url).includes('/api/creator/assignments'),
    );
    expect(call).toBeDefined();
  });

  it('offers only assigned restaurants, by name', async () => {
    stubApi([
      ASSIGNMENT,
      { ...ASSIGNMENT, id: 2, restaurantId: 8, restaurantName: 'Silver Spoon' },
    ]);
    renderWizard();

    expect(await screen.findByRole('option', { name: 'Copper Kitchen' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Silver Spoon' })).toBeInTheDocument();
    // The only other option is the placeholder; no unassigned venue leaks in.
    expect(screen.getAllByRole('option')).toHaveLength(3);
  });

  it('omits a restaurant whose assignment has been deactivated', async () => {
    // An admin ended this one, so it must disappear from the wizard.
    stubApi([{ ...ASSIGNMENT, active: false }]);
    renderWizard();

    await screen.findByLabelText('Restaurant');
    expect(screen.queryByRole('option', { name: 'Copper Kitchen' })).not.toBeInTheDocument();
  });

  it('explains the empty state when the creator has no active assignment', async () => {
    stubApi([]);
    renderWizard();

    expect(
      await screen.findByText(/no active restaurant assignments yet/i),
    ).toBeInTheDocument();
    // And names who has to act on it, rather than just showing an empty dropdown.
    expect(screen.getByText(/ask trynearby to assign you/i)).toBeInTheDocument();
  });
});

describe('generated campaign code', () => {
  it('offers no campaign-name field', async () => {
    const user = userEvent.setup();
    stubApi();
    renderWizard();

    await reachOfferStep(user);

    // The name is derived, so there is nothing to type and nothing to get inconsistent.
    expect(screen.queryByLabelText(/campaign title/i)).not.toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/monsoon|biryani bonanza/i)).not.toBeInTheDocument();
  });

  it('previews the code from the target, duration and selected discount', async () => {
    const user = userEvent.setup();
    stubApi();
    renderWizard();

    await reachOfferStep(user);

    // Defaults are 25 customers, 7 days and 5%, which is letter E: ??25 07 E.
    expect(await screen.findByText(/^[A-Z]{2}2507E$/)).toBeInTheDocument();
  });

  it('offers exactly the nine B-J discounts', async () => {
    const user = userEvent.setup();
    stubApi();
    renderWizard();

    await reachOfferStep(user);

    const select = screen.getByLabelText('Discount');
    const values = Array.from(select.querySelectorAll('option')).map((o) => o.value);
    // B=2% … J=10%, and nothing outside that range.
    expect(values).toEqual(['2', '3', '4', '5', '6', '7', '8', '9', '10']);
  });

  it('re-draws only the letters, leaving the offer figures untouched', async () => {
    const user = userEvent.setup();
    stubApi();
    renderWizard();

    await reachOfferStep(user);
    const before = (await screen.findByText(/^[A-Z]{2}2507E$/)).textContent ?? '';

    // Click a few times; at least one draw must differ, and no draw may touch 25/07/E.
    const seen = new Set<string>([before]);
    for (let i = 0; i < 6; i++) {
      await user.click(screen.getByRole('button', { name: /new letters/i }));
      seen.add(screen.getByText(/^[A-Z]{2}2507E$/).textContent ?? '');
    }

    expect(seen.size).toBeGreaterThan(1);
    // Every candidate still carries the same target, duration and discount letter.
    for (const code of seen) {
      expect(code).toMatch(/^[A-Z]{2}2507E$/);
    }
  });

  it('changes the discount letter when the discount changes', async () => {
    const user = userEvent.setup();
    stubApi();
    renderWizard();

    await reachOfferStep(user);
    await screen.findByText(/^[A-Z]{2}2507E$/);

    await user.selectOptions(screen.getByLabelText('Discount'), '9');

    // 9% is letter I; the figures are unchanged.
    expect(await screen.findByText(/^[A-Z]{2}2507I$/)).toBeInTheDocument();
  });

  it('marks the high discounts as limited to one per restaurant per month', async () => {
    const user = userEvent.setup();
    stubApi();
    renderWizard();

    await reachOfferStep(user);

    // The creator should learn the rule before submitting, not be refused afterwards.
    expect(screen.getByRole('option', { name: /6% \(F\)$/ })).toBeInTheDocument();
    expect(
      screen.getByRole('option', { name: /7% \(G\).*once per restaurant per month/i }),
    ).toBeInTheDocument();
  });
});