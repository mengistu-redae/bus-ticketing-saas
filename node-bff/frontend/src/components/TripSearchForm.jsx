import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import LocationAutocomplete from './LocationAutocomplete.jsx';
import { toDateInputValue } from '../lib/format.js';

const dateInputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * The trip-search entry form (From / To / Date), shared by the customer Home
 * page and the agent Search page - they differ only in where results land
 * (`resultsPath`) and the outer card styling (`className`). State pre-fills
 * from the query string so "Edit search" on the results page returns a
 * populated form.
 */
export default function TripSearchForm({ resultsPath, className = '' }) {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [origin, setOrigin] = useState(() => searchParams.get('origin') || '');
  const [destination, setDestination] = useState(() => searchParams.get('destination') || '');
  const [departureDate, setDepartureDate] = useState(() => {
    const after = searchParams.get('departureAfter');
    return after ? toDateInputValue(new Date(after)) : '';
  });

  function swap() {
    setOrigin(destination);
    setDestination(origin);
  }

  function handleSubmit(event) {
    event.preventDefault();
    const params = new URLSearchParams({ origin: origin.trim(), destination: destination.trim() });
    if (departureDate) {
      // departureAfter takes a full instant - start of the chosen local day.
      params.set('departureAfter', new Date(`${departureDate}T00:00:00`).toISOString());
    }
    navigate(`${resultsPath}?${params.toString()}`);
  }

  return (
    <form
      onSubmit={handleSubmit}
      className={`flex flex-col gap-4 rounded-xl border border-slate-200 bg-surface p-5 sm:flex-row sm:items-end ${className}`}
    >
      <LocationAutocomplete id="origin" label="From" required value={origin} onChange={setOrigin} placeholder="Addis Ababa" />

      <button
        type="button"
        onClick={swap}
        disabled={!origin && !destination}
        aria-label="Swap From and To"
        className="mb-1 shrink-0 self-center rounded-full border border-slate-300 bg-surface p-2 text-ink-muted transition-transform duration-200 hover:rotate-180 hover:text-brand disabled:opacity-40 disabled:hover:rotate-0 sm:self-end"
      >
        <svg
          viewBox="0 0 24 24"
          width="16"
          height="16"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="rotate-90 sm:rotate-0"
          aria-hidden="true"
        >
          <polyline points="16 3 21 8 16 13" />
          <line x1="21" y1="8" x2="8" y2="8" />
          <polyline points="8 21 3 16 8 11" />
          <line x1="3" y1="16" x2="16" y2="16" />
        </svg>
      </button>

      <LocationAutocomplete
        id="destination"
        label="To"
        required
        value={destination}
        onChange={setDestination}
        placeholder="Bahir Dar"
      />

      <label htmlFor="departureDate" className="block flex-1 text-left">
        <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Date</span>
        <input
          id="departureDate"
          type="date"
          value={departureDate}
          onChange={(e) => setDepartureDate(e.target.value)}
          className={dateInputClass}
        />
      </label>

      <button
        type="submit"
        className="w-full shrink-0 rounded-lg bg-accent px-6 py-2 text-sm font-semibold text-white hover:bg-accent-dark sm:w-auto"
      >
        Search
      </button>
    </form>
  );
}
