import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import LocationAutocomplete from '../../components/LocationAutocomplete.jsx';
import { toDateInputValue } from '../../lib/format.js';

/**
 * The agent/counter portal's entry point - the same search form as the
 * customer Home page, landing an agent in the counter booking flow
 * (/agent/...) rather than the customer one, so the walk-in booking they
 * create is attributed as channel=counter (decided server-side from the
 * caller's JWT role, not anything sent from here - see BookingController).
 * The results page (AgentSearchResults) queries GET /api/fleet/trips/search
 * - the agent's OWN operator only, since a counter agent can't sell another
 * operator's trips. The From/To autocomplete still uses the cross-operator
 * /api/trips/locations (there's no tenant-scoped city list); a city only
 * another operator serves just yields "No trips found".
 */
export default function AgentSearch() {
  const navigate = useNavigate();
  // Pre-fill from the query string so "Edit search" on the results page
  // keeps the previous entries instead of clearing the form.
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
      params.set('departureAfter', new Date(`${departureDate}T00:00:00`).toISOString());
    }
    navigate(`/agent/search?${params.toString()}`);
  }

  return (
    <div>
      <h1 className="mb-1 text-2xl font-bold text-ink">Book for a walk-in customer</h1>
      <p className="mb-6 text-sm text-ink-muted">Search a trip, pick a seat, and take payment at the counter.</p>

      <form
        onSubmit={handleSubmit}
        className="flex flex-col gap-4 rounded-xl border border-slate-200 bg-surface p-5 shadow-sm sm:flex-row sm:items-end"
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
