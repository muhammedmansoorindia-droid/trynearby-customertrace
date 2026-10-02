/**
 * Routing regression tests.
 *
 * The blank-page bug these guard against: `App` used to render its own <BrowserRouter>
 * inside the one `main.tsx` already mounts. React Router throws on a nested Router, React
 * unmounts the tree, and the user gets an empty document — with no TypeScript or build error
 * to explain it. Asserting real content on `/` is the only reliable guard.
 */

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

import App from '@/app/App';
import { useAppStore } from '@/app/store';

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  useAppStore.getState().signOut();
});

describe('App routing', () => {
  it('renders the role-selection screen on the default route', () => {
    renderAt('/');

    // RoleSelect renders three portal cards; assert on its landmarks.
    expect(screen.getByRole('main')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /admin/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /restaurant/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /creator/i })).toBeInTheDocument();
  });

  it('renders the role-selection screen at any unmatched path while signed out', () => {
    // Signed out, every route falls through to RoleSelect via <Route path="*" />.
    renderAt('/does-not-exist');
    expect(screen.getByRole('button', { name: /creator/i })).toBeInTheDocument();
  });

  it('mounts the portal shell on an active portal route', () => {
    useAppStore.getState().setPortal('admin');
    renderAt('/admin');

    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: /primary/i })).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
  });

  it('shows NotFound for an unknown route inside an active portal', () => {
    useAppStore.getState().setPortal('admin');
    renderAt('/admin/nope');
    expect(screen.getByText(/that screen does not exist/i)).toBeInTheDocument();
  });

  it('does not nest a second router (the blank-page regression)', () => {
    // A nested <BrowserRouter> throws during render. If that regresses, this fails loudly
    // instead of silently producing a blank page.
    expect(() => renderAt('/')).not.toThrow();
  });
});

describe('portal-selection navigation', () => {
  // Regression: with a portal active, "/" was not registered, so it matched the catch-all
  // and rendered NotFound. NotFound's "Back to portal selection" link points at "/", so
  // clicking it re-rendered NotFound — an inescapable dead end.
  it('shows the portal-selection screen at "/" while a portal is active', () => {
    useAppStore.getState().setPortal('admin');
    renderAt('/');

    expect(screen.getByRole('button', { name: /admin/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /restaurant/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /creator/i })).toBeInTheDocument();
    expect(screen.queryByText(/that screen does not exist/i)).not.toBeInTheDocument();
  });

  it('"Back to portal selection" returns from NotFound to the role screen', async () => {
    const user = userEvent.setup();
    useAppStore.getState().setPortal('admin');
    renderAt('/admin/nope');

    expect(screen.getByText(/that screen does not exist/i)).toBeInTheDocument();

    await user.click(screen.getByRole('link', { name: /back to portal selection/i }));

    expect(screen.queryByText(/that screen does not exist/i)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /creator/i })).toBeInTheDocument();
  });

  it('"Back to portal selection" works from a stale route in every portal', async () => {
    const user = userEvent.setup();

    for (const portal of ['restaurant', 'creator'] as const) {
      // Activate the portal BEFORE rendering, otherwise the signed-out branch renders
      // RoleSelect for the stale path and there is no NotFound link to click.
      useAppStore.getState().setPortal(portal);
      const view = renderAt(`/${portal}/nope`);

      expect(screen.getByText(/that screen does not exist/i)).toBeInTheDocument();
      await user.click(screen.getByRole('link', { name: /back to portal selection/i }));

      expect(screen.queryByText(/that screen does not exist/i)).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: /admin/i })).toBeInTheDocument();
      view.unmount();
    }
  });
});