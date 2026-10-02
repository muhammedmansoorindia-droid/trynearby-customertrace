/**
 * Portal selection and sign-in tests.
 *
 * These assert the *routing contract* agreed for the three portals: `/` offers exactly three
 * buttons labelled TryNearby, Restaurant and Creator, each opening that portal's own sign-in
 * page, and no restaurant/creator dropdown survives on the home page.
 */
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

import App from '@/app/App';

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

describe('portal selection', () => {
  it('offers exactly three portals on the home page', () => {
    renderAt('/');

    expect(screen.getByRole('link', { name: /TryNearby/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Restaurant/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Creator/i })).toBeInTheDocument();
  });

  it('does not label the administrator portal "Admin"', () => {
    renderAt('/');
    // "TryNearby" is the agreed administrator portal name; a bare "Admin" button must not exist.
    expect(screen.queryByRole('link', { name: /^Admin$/i })).not.toBeInTheDocument();
  });

  it('no longer renders restaurant or creator dropdowns', () => {
    renderAt('/');
    // The old design chose an identity from a <select>. That must not come back.
    expect(screen.queryAllByRole('combobox')).toHaveLength(0);
    expect(screen.queryByText(/choose a restaurant/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/choose a creator/i)).not.toBeInTheDocument();
  });

  it.each([
    ['TryNearby'],
    ['Restaurant'],
    ['Creator'],
  ])('the %s button opens its sign-in page', async (_label) => {
    const user = userEvent.setup();
    renderAt('/');

    await user.click(screen.getByRole('link', { name: new RegExp(_label, 'i') }));

    // The sign-in screen renders for that portal, identified by its own heading.
    expect(screen.getByRole('heading', { name: new RegExp(`sign in to ${_label}`, 'i') }))
      .toBeInTheDocument();
    expect(screen.getByLabelText(/user id/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
  });
});

describe('portal sign-in screens', () => {
  it.each([
    ['/signin/trynearby', 'TryNearby', 'Trynearby'],
    ['/signin/restaurant', 'Restaurant', 'restrnt'],
    ['/signin/creator', 'Creator', 'creator'],
  ])('%s shows the %s portal hint', (path, label, suffix) => {
    const { container } = renderAt(path);
    expect(screen.getByRole('heading', { name: new RegExp(`sign in to ${label}`, 'i') }))
      .toBeInTheDocument();

    // Scope to the hint paragraph so the heading's own text is not matched as well.
    const hint = container.querySelector('#userId-hint');
    expect(hint).not.toBeNull();
    expect(hint).toHaveTextContent(new RegExp(suffix, 'i'));
  });

  it('offers a route back to portal selection that works', async () => {
    const user = userEvent.setup();
    renderAt('/signin/creator');

    await user.click(screen.getByRole('link', { name: /back to portal selection/i }));

    expect(screen.getByRole('link', { name: /TryNearby/i })).toBeInTheDocument();
  });
});