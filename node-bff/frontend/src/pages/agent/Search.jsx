import TripSearchForm from '../../components/TripSearchForm.jsx';

/**
 * The agent/counter portal's entry point - the same TripSearchForm as the
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
  return (
    <div>
      <h1 className="mb-1 text-2xl font-bold text-ink">Book for a walk-in customer</h1>
      <p className="mb-6 text-sm text-ink-muted">Search a trip, pick a seat, and take payment at the counter.</p>
      <TripSearchForm resultsPath="/agent/search" className="shadow-sm" />
    </div>
  );
}
