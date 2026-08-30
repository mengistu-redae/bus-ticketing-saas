import TripSearchView from '../../components/TripSearchView.jsx';

/**
 * The agent/counter mirror of the customer SearchResults page - the shared
 * TripSearchView, but `scoped` (GET /api/fleet/trips/search, the agent's
 * own operator only - a counter agent can't sell another operator's trips)
 * and the link destinations point into the counter flow (/agent/trips/:id,
 * /agent).
 */
export default function AgentSearchResults() {
  return <TripSearchView tripLinkBase="/agent/trips" editSearchTo="/agent" scoped />;
}
