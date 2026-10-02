import { Link } from 'react-router-dom';

import { EmptyState } from '@/app/ui';

export function NotFound() {
  return (
    <main className="min-h-screen bg-background flex items-center justify-center px-gutter">
      <div className="max-w-md w-full text-center">
        <EmptyState
          title="That screen does not exist"
          hint="The link may be stale, or you may have switched portals."
        />
        <Link
          to="/"
          className="mt-gutter inline-block font-label-md text-label-md text-primary hover:underline"
        >
          Back to portal selection
        </Link>
      </div>
    </main>
  );
}
