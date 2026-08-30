import { useEffect, useMemo, useState } from 'react';
import { useSearchParams, Link } from 'react-router-dom';
import { useFleetLaneTripSearch, useLaneTripSearch } from '../api/queries.js';
import TripCard from './TripCard.jsx';
import TripFilterPanel from './TripFilterPanel.jsx';
import { TripCardSkeleton } from './Skeleton.jsx';
import EmptyState from './EmptyState.jsx';
import ErrorBanner from './ErrorBanner.jsx';
import SearchPager from './SearchPager.jsx';
import { formatDayLabel, startOfLocalDayIso, toDateInputValue } from '../lib/format.js';
import {
  CATEGORY_TABS,
  SORTS,
  TIME_BUCKETS,
  activeFilterCount,
  categoryForSort,
  deriveFacets,
  emptyFilters,
  filterTrips,
  timeBucket,
} from '../lib/tripFilters.js';

const PAGE_SIZE = 20;

/**
 * Shared trip-search results experience for the customer (/search) and
 * agent (/agent/search) pages - they differ only in where a picked trip
 * links to, where "Edit search" goes, and whether the search is
 * cross-operator (`scoped={false}`, the marketplace - customers/guests) or
 * limited to the caller's own operator (`scoped`, agents - a counter agent
 * can only sell their own operator's trips). Fetches the whole lane once
 * and does all filtering / sorting / categorising / paging client-side.
 */
export default function TripSearchView({ tripLinkBase, editSearchTo, scoped = false }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const origin = searchParams.get('origin') || '';
  const destination = searchParams.get('destination') || '';
  const departureAfter = searchParams.get('departureAfter') || undefined;

  // "Edit search" goes back to the form carrying the current query, so the
  // customer/agent can tweak one field instead of retyping everything.
  const editSearchLink = { pathname: editSearchTo, search: searchParams.toString() };

  // Quick-date buttons: today and the next two days. Clicking one re-runs the
  // search from local midnight of that day (same shape the form produces).
  const dateOptions = [0, 1, 2].map((offset) => {
    const now = new Date();
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset);
    return {
      key: toDateInputValue(d),
      iso: startOfLocalDayIso(d),
      label: offset === 0 ? 'Today' : offset === 1 ? 'Tomorrow' : formatDayLabel(d),
    };
  });
  const activeDateKey = departureAfter
    ? toDateInputValue(new Date(departureAfter))
    : dateOptions[0].key;

  function jumpToDate(iso) {
    const next = new URLSearchParams(searchParams);
    next.set('departureAfter', iso);
    setSearchParams(next);
  }

  // Both hooks are always called (stable hook order); only the one matching
  // `scoped` is enabled, so exactly one network request goes out.
  const marketplaceQuery = useLaneTripSearch({ origin, destination, departureAfter }, !scoped);
  const fleetQuery = useFleetLaneTripSearch({ origin, destination, departureAfter }, scoped);
  const { data, isLoading, isError, error, refetch } = scoped ? fleetQuery : marketplaceQuery;
  const allTrips = data?.data ?? [];
  const totalCount = data?.totalCount ?? 0;
  const truncated = totalCount > allTrips.length;

  const [filters, setFilters] = useState(emptyFilters);
  const [sort, setSort] = useState('departure');
  const [page, setPage] = useState(0);
  const [showFilters, setShowFilters] = useState(false);

  // A new search (different origin/destination/date) clears everything.
  useEffect(() => {
    setFilters(emptyFilters());
    setSort('departure');
  }, [origin, destination, departureAfter]);

  // Any filter or sort change returns to the first page.
  useEffect(() => setPage(0), [filters, sort]);

  const facets = useMemo(() => deriveFacets(allTrips), [allTrips]);
  const visible = useMemo(
    () => [...filterTrips(allTrips, filters)].sort(SORTS[sort]),
    [allTrips, filters, sort],
  );
  const pageSlice = visible.slice(page * PAGE_SIZE, page * PAGE_SIZE + PAGE_SIZE);
  const category = categoryForSort(sort);
  const activeCount = activeFilterCount(filters, facets);

  // When the result list is empty only because sold-out trips are hidden,
  // the fix is "show sold-out", not "clear filters" (which re-hides them).
  const emptyOnlyDueToSoldOut =
    visible.length === 0 &&
    filters.hideSoldOut &&
    filterTrips(allTrips, { ...filters, hideSoldOut: false }).length > 0;

  function showSoldOut() {
    setFilters({ ...filters, hideSoldOut: false });
  }

  function clearFilters() {
    setFilters(emptyFilters());
  }

  return (
    <div>
      <div className="mb-4 flex items-baseline gap-2">
        <h1 className="text-2xl font-bold text-ink">
          {origin} <span className="text-ink-muted">&rarr;</span> {destination}
        </h1>
        <Link to={editSearchLink} className="text-sm text-brand hover:underline">
          Edit search
        </Link>
      </div>

      <div className="mb-5 flex flex-wrap gap-2">
        {dateOptions.map((opt) => (
          <button
            key={opt.key}
            type="button"
            onClick={() => jumpToDate(opt.iso)}
            className={`rounded-lg border px-3 py-1.5 text-sm font-medium transition-colors ${
              activeDateKey === opt.key
                ? 'border-brand bg-brand text-white'
                : 'border-slate-200 text-ink hover:bg-slate-50'
            }`}
          >
            {opt.label}
          </button>
        ))}
      </div>

      {isLoading && (
        <div className="flex flex-col gap-3">
          <TripCardSkeleton />
          <TripCardSkeleton />
          <TripCardSkeleton />
        </div>
      )}

      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}

      {!isLoading && !isError && allTrips.length === 0 && (
        <EmptyState
          title="No trips found"
          description="Try one of the dates above, or edit the origin and destination."
          action={
            <Link
              to={editSearchLink}
              className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark"
            >
              Edit search
            </Link>
          }
        />
      )}

      {!isLoading && !isError && allTrips.length > 0 && (
        <>
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            {/* category quick-presets - same segmented-control recipe as PeriodSelector */}
            <div className="inline-flex rounded-lg border border-slate-200 bg-surface p-0.5 text-sm">
              {CATEGORY_TABS.map((tab) => (
                <button
                  key={tab.key}
                  type="button"
                  onClick={() => setSort(tab.sort)}
                  className={`rounded-md px-3 py-1 font-medium transition-colors ${
                    category === tab.key ? 'bg-brand text-white' : 'text-ink-muted hover:bg-slate-100'
                  }`}
                >
                  {tab.label}
                </button>
              ))}
            </div>

            {/* The one filter worth promoting out of the panel - shown only
                when there's actually sold-out inventory to hide. Same
                `filters.hideSoldOut` state as the panel checkbox. */}
            {facets.soldOutCount > 0 && (
              <label className="flex cursor-pointer items-center gap-2 text-sm text-ink">
                <input
                  type="checkbox"
                  checked={filters.hideSoldOut}
                  onChange={(e) => setFilters({ ...filters, hideSoldOut: e.target.checked })}
                  className="h-4 w-4 rounded border-slate-300 text-brand focus:ring-brand/30"
                />
                Hide sold-out <span className="text-ink-muted">({facets.soldOutCount})</span>
              </label>
            )}
          </div>

          {truncated && (
            <p className="mb-3 text-xs text-ink-muted">
              Showing the first {allTrips.length} trips on this route.
            </p>
          )}

          <button
            type="button"
            onClick={() => setShowFilters((v) => !v)}
            className="mb-3 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm font-medium text-ink hover:bg-slate-50 md:hidden"
          >
            {showFilters ? 'Hide filters' : `Filters${activeCount > 0 ? ` (${activeCount})` : ''}`}
          </button>

          <div className="md:grid md:grid-cols-[16rem_1fr] md:gap-6">
            {/* On desktop the panel is a self-contained scroll area (its own
                scrollbar, capped to the viewport) so a long operator list
                never forces the whole page to scroll and the results column
                stays put. On mobile it just expands inline. */}
            <aside
              className={`${showFilters ? 'block' : 'hidden'} mb-4 md:mb-0 md:block md:sticky md:top-4 md:self-start md:max-h-[calc(100vh-2rem)] md:overflow-y-auto md:overscroll-contain md:pr-1 md:[scrollbar-gutter:stable]`}
            >
              <TripFilterPanel
                facets={facets}
                filters={filters}
                sort={sort}
                onChange={setFilters}
                onSortChange={setSort}
                onClear={clearFilters}
              />
            </aside>

            <div>
              {visible.length === 0 ? (
                <EmptyState
                  title={emptyOnlyDueToSoldOut ? 'Every matching trip is sold out' : 'No trips match your filters'}
                  description={
                    emptyOnlyDueToSoldOut
                      ? 'Show sold-out departures, pick another date above, or loosen a filter.'
                      : 'Loosen or clear a filter to see more of this route’s trips.'
                  }
                  action={
                    <button
                      type="button"
                      onClick={emptyOnlyDueToSoldOut ? showSoldOut : clearFilters}
                      className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark"
                    >
                      {emptyOnlyDueToSoldOut ? 'Show sold-out trips' : 'Clear filters'}
                    </button>
                  }
                />
              ) : (
                <>
                  {category === 'all'
                    ? <GroupedByTimeOfDay trips={pageSlice} tripLinkBase={tripLinkBase} />
                    : (
                      <div className="flex flex-col gap-3">
                        {pageSlice.map((trip) => (
                          <TripCard key={trip.tripId} trip={trip} to={`${tripLinkBase}/${trip.tripId}`} />
                        ))}
                      </div>
                    )}
                  <SearchPager
                    page={page}
                    pageSize={PAGE_SIZE}
                    shown={pageSlice.length}
                    total={visible.length}
                    onPageChange={setPage}
                  />
                </>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

/** Renders a departure-sorted page under Morning / Afternoon / Evening subheaders. */
function GroupedByTimeOfDay({ trips, tripLinkBase }) {
  return (
    <div className="flex flex-col gap-6">
      {TIME_BUCKETS.map((bucket) => {
        const inBucket = trips.filter((t) => timeBucket(t.departureAt) === bucket.key);
        if (inBucket.length === 0) return null;
        return (
          <div key={bucket.key}>
            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-muted">
              {bucket.label} <span className="text-ink-muted/70">· {inBucket.length}</span>
            </p>
            <div className="flex flex-col gap-3">
              {inBucket.map((trip) => (
                <TripCard key={trip.tripId} trip={trip} to={`${tripLinkBase}/${trip.tripId}`} />
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}
