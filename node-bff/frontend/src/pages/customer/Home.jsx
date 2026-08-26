import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import LocationAutocomplete from '../../components/LocationAutocomplete.jsx';

export default function Home() {
  const navigate = useNavigate();
  const [origin, setOrigin] = useState('');
  const [destination, setDestination] = useState('');
  const [departureDate, setDepartureDate] = useState('');

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
