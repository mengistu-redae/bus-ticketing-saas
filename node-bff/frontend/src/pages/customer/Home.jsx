import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import LocationAutocomplete from '../../components/LocationAutocomplete.jsx';
import StatusPill from '../../components/StatusPill.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import { useAuth } from '../../auth/AuthContext.jsx';
import { useMyDashboard } from '../../api/queries.js';
import { formatDateTime, toDateInputValue } from '../../lib/format.js';

export default function Home() {
  const navigate = useNavigate();
  const { authenticated, hasRole } = useAuth();
  // Pre-fill from the query string so "Edit search" on the results page
  // comes back with the previous entries intact, not a blank form.
  const [searchParams] = useSearchParams();
  const [origin, setOrigin] = useState(() => searchParams.get('origin') || '');
  const [destination, setDestination] = useState(() => searchParams.get('destination') || '');
  const [departureDate, setDepartureDate] = useState(() => {
    const after = searchParams.get('departureAfter');
    return after ? toDateInputValue(new Date(after)) : '';
  });

  function handleSubmit(event) {
    event.preventDefault();
    const params = new URLSearchParams({ origin: origin.trim(), destination: destination.trim() });
    if (departureDate) {
      // departureAfter takes a full instant - start of the chosen local day.
      params.set('departureAfter', new Date(`${departureDate}T00:00:00`).toISOString());
    }
    const target = `/search?${params.toString()}`;

    // Search is public - no login required, same as the rest of the guest
    // booking flow (see SeatSelection.jsx and spring-boot-api's
    // TripController).
    navigate(target);
  }

  return (
    <div>
      <div className="rounded-2xl bg-brand px-6 py-14 text-center sm:px-10">
        <h1 className="text-3xl font-bold text-white sm:text-4xl">Find your next trip</h1>
        <p className="mx-auto mt-2 max-w-md text-brand-light/90">
          Search buses across every operator on the platform.
        </p>
      </div>

      <form
        onSubmit={handleSubmit}
        className="relative -mt-8 mx-auto flex max-w-3xl flex-col gap-4 rounded-xl border border-slate-200 bg-surface p-5 shadow-md sm:flex-row sm:items-end"
      >
        <LocationAutocomplete id="origin" label="From" required value={origin} onChange={setOrigin} placeholder="Addis Ababa" />
        <LocationAutocomplete
          id="destination"
          label="To"
          required
          value={destination}
          onChange={setDestination}
          placeholder="Bahir Dar"
        />
        <Field label="Date" htmlFor="departureDate">
          <input
            id="departureDate"
            type="date"
            value={departureDate}
            onChange={(e) => setDepartureDate(e.target.value)}
            className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
          />
        </Field>
        <button
          type="submit"
          className="w-full shrink-0 rounded-lg bg-accent px-6 py-2 text-sm font-semibold text-white hover:bg-accent-dark sm:w-auto"
        >
          Search
        </button>
      </form>

      {authenticated && hasRole('customer') && <CustomerDashboard />}
    </div>
  );
}

/**
 * Compact personal overview for a signed-in customer, shown below the search
 * box (GET /api/my-dashboard). Guests never see it - the block is only
 * mounted when authenticated. Deliberately not its own route: the customer's
 * home is still "search", this just adds context above the fold.
 */
function CustomerDashboard() {
  const { data, isLoading, isError } = useMyDashboard();

  if (isLoading || isError || !data) {
    return null;
  }

  const { counts, upcomingTrips, activeShipments } = data;
  const nothing = upcomingTrips.length === 0 && activeShipments.length === 0;

  return (
    <div className="mx-auto mt-10 max-w-3xl">
      <div className="mb-5 grid grid-cols-3 gap-3">
        <MiniStat label="Upcoming" value={counts.upcoming} accent="text-brand" />
        <MiniStat label="Past" value={counts.past} accent="text-ink" />
        <MiniStat label="Cancelled" value={counts.cancelled} accent="text-ink-muted" />
      </div>

      {nothing ? (
        <EmptyState title="No upcoming trips" description="Book a trip above and it will show up here." />
      ) : (
        <div className="grid gap-6 sm:grid-cols-2">
          {upcomingTrips.length > 0 && (
            <section>
              <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-ink-muted">Upcoming trips</h2>
              <div className="flex flex-col gap-2">
                {upcomingTrips.map((t) => (
                  <Link
                    key={t.bookingId}
                    to={`/bookings/${t.bookingId}`}
                    className="rounded-xl border border-slate-200 bg-surface p-3 shadow-sm transition-shadow hover:shadow-md"
                  >
                    <p className="text-sm font-medium text-ink">{t.routeName || 'Trip'}</p>
                    <p className="text-xs text-ink-muted">{formatDateTime(t.departureAt)}</p>
                    <p className="mt-1 font-mono text-xs text-ink-muted">{t.bookingRef}</p>
                  </Link>
                ))}
              </div>
            </section>
          )}
          {activeShipments.length > 0 && (
            <section>
              <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-ink-muted">Active shipments</h2>
              <div className="flex flex-col gap-2">
                {activeShipments.map((s) => (
                  <Link
                    key={s.waybillId}
                    to={`/my-shipments/${s.waybillId}`}
                    className="flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-3 shadow-sm transition-shadow hover:shadow-md"
                  >
                    <div>
                      <p className="font-mono text-xs text-ink-muted">{s.waybillNumber}</p>
                      {s.departureAt && <p className="text-xs text-ink-muted">{formatDateTime(s.departureAt)}</p>}
                    </div>
                    <StatusPill status={s.status} />
                  </Link>
                ))}
              </div>
            </section>
          )}
        </div>
      )}
    </div>
  );
}

function MiniStat({ label, value, accent = 'text-ink' }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-3 text-center shadow-sm">
      <p className={`text-2xl font-bold tabular-nums ${accent}`}>{value}</p>
      <p className="mt-0.5 text-xs font-medium uppercase tracking-wide text-ink-muted">{label}</p>
    </div>
  );
}

function Field({ label, htmlFor, children }) {
  return (
    <label htmlFor={htmlFor} className="block flex-1 text-left">
      <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</span>
      {children}
    </label>
  );
}
