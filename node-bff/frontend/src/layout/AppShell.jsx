import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';

const navLinkClass = ({ isActive }) =>
  `rounded-lg px-3 py-1.5 text-sm font-medium transition-colors ${
    isActive ? 'bg-brand-light text-brand' : 'text-ink-muted hover:bg-slate-100 hover:text-ink'
  }`;

/**
 * Role-aware nav - customer links from Phase 1, agent/counter and
 * operator_admin fleet-management links added Phase 2 (2026-08-24/25),
 * platform_admin operator onboarding added Phase 4 (2026-08-25).
 */
export default function AppShell() {
  const { user, hasRole } = useAuth();

  return (
    <div className="min-h-screen bg-slate-50">
      <header className="border-b border-slate-200 bg-surface">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3 sm:px-6">
          <div className="flex items-center gap-8">
            <NavLink to="/" className="text-lg font-bold text-brand">
              Bustix
            </NavLink>
            <nav className="flex items-center gap-1">
              {hasRole('customer') && (
                <>
                  <NavLink to="/" end className={navLinkClass}>
                    Search
                  </NavLink>
                  <NavLink to="/my-bookings" className={navLinkClass}>
                    My Bookings
                  </NavLink>
                  <NavLink to="/my-shipments" className={navLinkClass}>
                    My Shipments
                  </NavLink>
                </>
              )}
              {hasRole('agent') && (
                <>
                  <NavLink to="/agent/dashboard" className={navLinkClass}>
                    Dashboard
                  </NavLink>
                  <NavLink to="/agent" end className={navLinkClass}>
                    New Booking
                  </NavLink>
                  <NavLink to="/agent/bookings" className={navLinkClass}>
                    Bookings
                  </NavLink>
                </>
              )}
              {hasRole('operator_admin') && (
                <>
                  <NavLink to="/operator/dashboard" className={navLinkClass}>
                    Dashboard
                  </NavLink>
                  <NavLink to="/operator/buses" className={navLinkClass}>
                    Buses
                  </NavLink>
                  <NavLink to="/operator/routes" className={navLinkClass}>
                    Routes
                  </NavLink>
                  <NavLink to="/operator/trips" className={navLinkClass}>
                    Trips
                  </NavLink>
                  <NavLink to="/operator/refund-policies" className={navLinkClass}>
                    Refund Policies
                  </NavLink>
                  <NavLink to="/operator/cargo-rates" className={navLinkClass}>
                    Cargo Rates
                  </NavLink>
                  <NavLink to="/operator/settings" className={navLinkClass}>
                    Settings
                  </NavLink>
                </>
              )}
              {/* Cargo waybill management is usable by AGENT and
                  OPERATOR_ADMIN alike (identical backend permissions on
                  every waybill endpoint) - a combined condition rather
                  than duplicating this link inside both blocks above. */}
              {(hasRole('agent') || hasRole('operator_admin')) && (
                <NavLink to="/cargo" className={navLinkClass}>
                  Cargo
                </NavLink>
              )}
              {hasRole('platform_admin') && (
                <>
                  <NavLink to="/platform/dashboard" className={navLinkClass}>
                    Dashboard
                  </NavLink>
                  <NavLink to="/platform/operators" className={navLinkClass}>
                    Operators
                  </NavLink>
                </>
              )}
            </nav>
          </div>
          <div className="flex items-center gap-3">
            <div className="text-right leading-tight">
              <p className="text-sm font-medium text-ink">{user?.preferred_username}</p>
              {user?.email && <p className="text-xs text-ink-muted">{user.email}</p>}
            </div>
            <form method="post" action="/auth/logout">
              <button
                type="submit"
                className="rounded-lg border border-slate-200 px-3 py-1.5 text-sm font-medium text-ink-muted hover:bg-slate-100"
              >
                Log out
              </button>
            </form>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
        <Outlet />
      </main>
    </div>
  );
}
