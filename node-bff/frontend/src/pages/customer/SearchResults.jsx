import { useEffect, useState } from 'react';
import { useSearchParams, Link } from 'react-router-dom';
import { useTripSearch } from '../../api/queries.js';
import TripCard from '../../components/TripCard.jsx';
import { TripCardSkeleton } from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import SearchPager from '../../components/SearchPager.jsx';

const PAGE_SIZE = 20;

export default function SearchResults() {
  const [searchParams] = useSearchParams();
  const origin = searchParams.get('origin') || '';
  const destination = searchParams.get('destination') || '';
  const departureAfter = searchParams.get('departureAfter') || undefined;

  const [page, setPage] = useState(0);
  // A new search (different origin/destination/date) restarts at page 1.
  useEffect(() => setPage(0), [origin, destination, departureAfter]);

  const { data, isLoading, isError, error, refetch } = useTripSearch(
    { origin, destination, departureAfter, page, size: PAGE_SIZE },
    true,
  );
  const trips = data?.data ?? [];
  const total = data?.totalCount ?? 0;

  return (
    <div>
      <div className="mb-6 flex items-baseline gap-2">
        <h1 className="text-2xl font-bold text-ink">
          {origin} <span className="text-ink-muted">&rarr;</span> {destination}
        </h1>
        <Link to="/" className="text-sm text-brand hover:underline">
          Edit search
        </Link>
      </div>

      {isLoading && (
        <div className="flex flex-col gap-3">
          <TripCardSkeleton />
          <TripCardSkeleton />
          <TripCardSkeleton />
        </div>
      )}

      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}

      {!isLoading && !isError && total === 0 && (
        <EmptyState
          title="No trips found"
          description="Try a different date, or double-check the origin and destination."
          action={
            <Link to="/" className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark">
              New search
            </Link>
          }
        />
      )}

      {!isLoading && !isError && total > 0 && (
        <>
          <div className="flex flex-col gap-3">
            {trips.map((trip) => (
              <TripCard key={trip.tripId} trip={trip} to={`/trips/${trip.tripId}`} />
            ))}
          </div>
          <SearchPager page={page} pageSize={PAGE_SIZE} shown={trips.length} total={total} onPageChange={setPage} />
        </>
      )}
    </div>
  );
}
