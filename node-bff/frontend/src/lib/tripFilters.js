/**
 * Pure helpers for the client-side trip-search filter / sort / categorise
 * work in TripSearchView. No React, no I/O - kept separate so the logic is
 * easy to reason about (same spirit as lib/color.js, lib/chartTheme.js).
 *
 * A "trip" here is one TripSearchResult row from GET /api/trips/search:
 * { tripId, operatorId, operatorName, branding, price (number),
 *   departureAt, arrivalAt, availableSeats, ... }.
 */

// Ethiopia runs on EAT (UTC+3) year-round, no DST - so a fixed offset is
// exact, and we never depend on the viewer's own timezone for bucketing.
const EAT_OFFSET_HOURS = 3;

export const TIME_BUCKETS = [
  { key: 'morning', label: 'Morning', hint: 'until 12:00' },
  { key: 'afternoon', label: 'Afternoon', hint: '12:00 – 17:00' },
  { key: 'evening', label: 'Evening', hint: 'from 17:00' },
];

export function eatHour(iso) {
  return (new Date(iso).getUTCHours() + EAT_OFFSET_HOURS) % 24;
}

export function timeBucket(iso) {
  const h = eatHour(iso);
  if (h >= 12 && h < 17) return 'afternoon';
  if (h >= 17 || h < 4) return 'evening';
  return 'morning';
}

export function tripDurationMs(trip) {
  if (!trip.arrivalAt) return Infinity;
  return new Date(trip.arrivalAt).getTime() - new Date(trip.departureAt).getTime();
}

const byDeparture = (a, b) => new Date(a.departureAt).getTime() - new Date(b.departureAt).getTime();

/** Comparators for the Sort control. Every one tie-breaks on departure time. */
export const SORTS = {
  departure: byDeparture,
  price: (a, b) => a.price - b.price || byDeparture(a, b),
  duration: (a, b) => tripDurationMs(a) - tripDurationMs(b) || byDeparture(a, b),
  seats: (a, b) => b.availableSeats - a.availableSeats || byDeparture(a, b),
};

export const SORT_OPTIONS = [
  { value: 'departure', label: 'Departure time' },
  { value: 'price', label: 'Price: low to high' },
  { value: 'duration', label: 'Duration: shortest' },
  { value: 'seats', label: 'Seats available' },
];

/** Quick-preset tabs above the results - each just pins the sort. */
export const CATEGORY_TABS = [
  { key: 'all', label: 'All', sort: 'departure' },
  { key: 'cheapest', label: 'Cheapest', sort: 'price' },
  { key: 'fastest', label: 'Fastest', sort: 'duration' },
];

export function categoryForSort(sort) {
  const tab = CATEGORY_TABS.find((t) => t.sort === sort);
  return tab ? tab.key : null;
}

/**
 * The default filter state. `hideSoldOut` defaults to true - a sold-out trip
 * renders a dead "Select seats" button that dead-ends on a fully-booked seat
 * map, so it's hidden unless the searcher opts back in (same as flight /
 * event booking sites). `deriveFacets().soldOutCount` still reports how many
 * are hidden.
 */
export function emptyFilters() {
  return {
    buckets: new Set(),
    operatorIds: new Set(),
    minPrice: null,
    maxPrice: null,
    hideSoldOut: true,
  };
}

/**
 * What the filter panel needs to render itself: price extent, the operators
 * present (with counts), per-bucket counts, and how many are sold out.
 * Derived from the *unfiltered* lane so the controls don't flicker as the
 * user narrows things.
 */
export function deriveFacets(trips) {
  if (trips.length === 0) {
    return { priceMin: 0, priceMax: 0, operators: [], bucketCounts: {}, soldOutCount: 0 };
  }

  const operators = new Map();
  const bucketCounts = { morning: 0, afternoon: 0, evening: 0 };
  let priceMin = Infinity;
  let priceMax = -Infinity;
  let soldOutCount = 0;

  for (const trip of trips) {
    priceMin = Math.min(priceMin, trip.price);
    priceMax = Math.max(priceMax, trip.price);
    bucketCounts[timeBucket(trip.departureAt)] += 1;
    if (trip.availableSeats <= 0) soldOutCount += 1;

    const name = trip.branding?.displayName || trip.operatorName;
    const entry = operators.get(trip.operatorId) || { id: trip.operatorId, name, count: 0 };
    entry.count += 1;
    operators.set(trip.operatorId, entry);
  }

  return {
    priceMin: Math.floor(priceMin),
    priceMax: Math.ceil(priceMax),
    operators: [...operators.values()].sort((a, b) => a.name.localeCompare(b.name)),
    bucketCounts,
    soldOutCount,
  };
}

/** Apply the active filters (does not sort). */
export function filterTrips(trips, filters) {
  return trips.filter((trip) => {
    if (filters.hideSoldOut && trip.availableSeats <= 0) return false;
    if (filters.buckets.size > 0 && !filters.buckets.has(timeBucket(trip.departureAt))) return false;
    if (filters.operatorIds.size > 0 && !filters.operatorIds.has(trip.operatorId)) return false;
    if (filters.minPrice != null && trip.price < filters.minPrice) return false;
    if (filters.maxPrice != null && trip.price > filters.maxPrice) return false;
    return true;
  });
}

/**
 * How many narrowing choices the searcher has made (mobile "Filters (n)"
 * badge, and whether "Clear all" shows). `hideSoldOut` is deliberately not
 * counted - it's a default preference, not a narrowing the user opted into.
 */
export function activeFilterCount(filters, facets) {
  let n = 0;
  if (filters.buckets.size > 0) n += 1;
  if (filters.operatorIds.size > 0) n += 1;
  if (filters.minPrice != null && filters.minPrice > facets.priceMin) n += 1;
  if (filters.maxPrice != null && filters.maxPrice < facets.priceMax) n += 1;
  return n;
}
