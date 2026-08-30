import { Link, Outlet } from 'react-router-dom';

export default function PublicShell() {
  return (
    <div className="min-h-screen bg-slate-50">
      <header className="border-b border-slate-200 bg-surface">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-y-2 px-4 py-3 sm:px-6">
          <div className="flex flex-wrap items-center gap-x-6 gap-y-1">
            {/* "/" is the marketplace search for a logged-out visitor -
                without this, /track and /track-booking are dead ends (no way
                back to search but the browser back button). */}
            <Link to="/" className="text-lg font-bold text-brand">
              Bustix
            </Link>
            <Link to="/" className="text-sm font-medium text-ink-muted hover:text-ink">
              Search trips
            </Link>
          </div>
          <div className="flex flex-wrap items-center gap-4">
            {/* /track works with no login at all (see Track.jsx /
                node-bff's public-tracking carve-out) - a real router Link,
                unlike the login button below, since it stays inside the
                SPA's own routing. */}
            <Link to="/track-booking" className="text-sm font-medium text-ink-muted hover:text-ink">
              Track a booking
            </Link>
            <Link to="/track" className="text-sm font-medium text-ink-muted hover:text-ink">
              Track a shipment
            </Link>
            {/* Full-page navigation, not a router Link - this hands off to
                Keycloak's hosted login form, outside the SPA's own routing. */}
            <a
              href="/auth/login"
              className="rounded-lg bg-brand px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-dark"
            >
              Log in
            </a>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
        <Outlet />
      </main>
    </div>
  );
}
