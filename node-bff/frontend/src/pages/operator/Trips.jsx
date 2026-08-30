import { useState } from 'react';
import {
  useCancelTrip,
  useCreateTrip,
  useFleetBuses,
  useFleetRoutes,
  useFleetTripSearch,
  useFleetTrips,
  useUpdateTrip,
} from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import SearchPager from '../../components/SearchPager.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

const PAGE_SIZE = 20;

function seatsBadge(n) {
  if (n <= 0) return 'bg-danger-light text-danger';
  if (n <= 4) return 'bg-warning-light text-warning';
  return 'bg-success-light text-success';
}

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/** <input type="datetime-local"> works in local time with no timezone/seconds - Trip.departureAt/arrivalAt are Instants (UTC ISO). */
function toDatetimeLocal(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
function fromDatetimeLocal(local) {
  return local ? new Date(local).toISOString() : undefined;
}

const emptyForm = { routeId: '', busId: '', departureAt: '', arrivalAt: '', price: '' };

/**
 * operator_admin fleet management: GET/POST/PATCH/DELETE /api/fleet/trips.
 * routeId/busId are only set at creation - PATCH deliberately doesn't allow
 * changing them (see UpdateTripRequest's javadoc: seats are generated from
 * the bus's layout once, at trip creation). DELETE sets status=cancelled,
 * not a row delete - see TripController.cancel.
 *
 * GET /api/fleet/trips returns bare Trip rows (routeId/busId, not
 * denormalized names) - unlike the customer-facing TripSearchResult, so
 * route/bus names for display are looked up client-side from the
 * routes/buses lists this page already loads for the create-trip dropdowns.
 */
export default function OperatorTrips() {
  const { data: trips, isLoading, isError, error, refetch } = useFleetTrips();
  const { data: routes } = useFleetRoutes();
  const { data: buses } = useFleetBuses();
  const createTrip = useCreateTrip();
  const updateTrip = useUpdateTrip();
  const cancelTrip = useCancelTrip();

  const [form, setForm] = useState(emptyForm);
  const [formError, setFormError] = useState(null);
  const [editingId, setEditingId] = useState(null);
  const [editForm, setEditForm] = useState({});
  const [rowError, setRowError] = useState(null);

  // Operator-scoped trip search (GET /api/fleet/trips/search) - own trips
  // only. `search` is null when not searching; the management list shows
  // instead. Read-only results (route / departs / seats-available / price)
  // since seat availability isn't on the bare Trip rows the list renders.
  const [searchRouteId, setSearchRouteId] = useState('');
  const [searchDate, setSearchDate] = useState('');
  const [search, setSearch] = useState(null);
  const [searchPage, setSearchPage] = useState(0);
  const tripSearch = useFleetTripSearch(
    { origin: search?.origin, destination: search?.destination, departureAfter: search?.departureAfter, page: searchPage, size: PAGE_SIZE },
    Boolean(search),
  );

  const routeById = Object.fromEntries((routes || []).map((r) => [r.id, r]));
  const busById = Object.fromEntries((buses || []).map((b) => [b.id, b]));
  const activeRoutes = (routes || []).filter((r) => r.active);
  const activeBuses = (buses || []).filter((b) => b.active);

  function runSearch(event) {
    event.preventDefault();
    const route = routeById[searchRouteId];
    if (!route) return;
    setSearchPage(0);
    setSearch({
      origin: route.origin,
      destination: route.destination,
      departureAfter: searchDate ? new Date(`${searchDate}T00:00:00`).toISOString() : undefined,
    });
  }
  function clearSearch() {
    setSearch(null);
    setSearchRouteId('');
    setSearchDate('');
  }

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.routeId || !form.busId || !form.departureAt || !form.price) {
      setFormError('Route, bus, departure time, and price are all required.');
      return;
    }
    try {
      await createTrip.mutateAsync({
        routeId: form.routeId,
        busId: form.busId,
        departureAt: fromDatetimeLocal(form.departureAt),
        arrivalAt: fromDatetimeLocal(form.arrivalAt),
        price: Number(form.price),
      });
      setForm(emptyForm);
    } catch (err) {
      setFormError(err.message || 'Could not create trip.');
    }
  }

  function startEdit(trip) {
    setRowError(null);
    setEditingId(trip.id);
    setEditForm({
      departureAt: toDatetimeLocal(trip.departureAt),
      arrivalAt: toDatetimeLocal(trip.arrivalAt),
      price: String(trip.price),
    });
  }

  async function saveEdit(tripId) {
    setRowError(null);
    try {
      await updateTrip.mutateAsync({
        tripId,
        departureAt: fromDatetimeLocal(editForm.departureAt),
        arrivalAt: fromDatetimeLocal(editForm.arrivalAt),
        price: Number(editForm.price),
      });
      setEditingId(null);
    } catch (err) {
      setRowError(err.message || 'Could not save changes.');
    }
  }

  async function handleCancel(tripId) {
    setRowError(null);
    try {
      await cancelTrip.mutateAsync(tripId);
    } catch (err) {
      setRowError(err.message || 'Could not cancel this trip.');
    }
  }

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-ink">Trips</h1>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label="Route">
          <select value={form.routeId} onChange={(e) => setForm({ ...form, routeId: e.target.value })} className={`${inputClass} w-56`}>
            <option value="">Select a route</option>
            {activeRoutes.map((r) => (
              <option key={r.id} value={r.id}>{r.origin} → {r.destination}</option>
            ))}
          </select>
        </Field>
        <Field label="Bus">
          <select value={form.busId} onChange={(e) => setForm({ ...form, busId: e.target.value })} className={`${inputClass} w-40`}>
            <option value="">Select a bus</option>
            {activeBuses.map((b) => (
              <option key={b.id} value={b.id}>{b.plateNo} ({b.capacity} seats)</option>
            ))}
          </select>
        </Field>
        <Field label="Departure">
          <input type="datetime-local" value={form.departureAt} onChange={(e) => setForm({ ...form, departureAt: e.target.value })} className={`${inputClass} w-52`} />
        </Field>
        <Field label="Arrival (optional)">
          <input type="datetime-local" value={form.arrivalAt} onChange={(e) => setForm({ ...form, arrivalAt: e.target.value })} className={`${inputClass} w-52`} />
        </Field>
        <Field label="Price / seat">
          <input type="number" min="0" step="0.01" value={form.price} onChange={(e) => setForm({ ...form, price: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <button type="submit" disabled={createTrip.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
          {createTrip.isPending ? 'Adding…' : 'Add trip'}
        </button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
      {rowError && <div className="mb-4"><ErrorBanner message={rowError} /></div>}

      {/* Find trips on one of your own routes - shows seat availability,
          which the management list below doesn't. */}
      <form onSubmit={runSearch} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label="Find trips on route">
          <select value={searchRouteId} onChange={(e) => setSearchRouteId(e.target.value)} className={`${inputClass} w-56`}>
            <option value="">Select a route</option>
            {activeRoutes.map((r) => (
              <option key={r.id} value={r.id}>{r.origin} → {r.destination}</option>
            ))}
          </select>
        </Field>
        <Field label="From date (optional)">
          <input type="date" value={searchDate} onChange={(e) => setSearchDate(e.target.value)} className={`${inputClass} w-44`} />
        </Field>
        <button type="submit" disabled={!searchRouteId} className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark disabled:opacity-50">
          Search
        </button>
        {search && (
          <button type="button" onClick={clearSearch} className="text-sm text-ink-muted hover:underline">
            Clear search
          </button>
        )}
      </form>

      {search && (
        <div>
          {tripSearch.isLoading && <Skeleton className="h-32 w-full" />}
          {tripSearch.isError && <ErrorBanner message={tripSearch.error?.message} onRetry={tripSearch.refetch} />}
          {!tripSearch.isLoading && !tripSearch.isError && (tripSearch.data?.totalCount ?? 0) === 0 && (
            <EmptyState title="No scheduled trips on this route" description="Nothing upcoming for that route and date." />
          )}
          {!tripSearch.isLoading && !tripSearch.isError && (tripSearch.data?.totalCount ?? 0) > 0 && (
            <>
              <div className="flex flex-col gap-2">
                {(tripSearch.data?.data ?? []).map((t) => (
                  <div key={t.tripId} className="flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-4">
                    <div>
                      <p className="text-sm font-semibold text-ink">{t.origin} → {t.destination}</p>
                      <p className="text-xs text-ink-muted">
                        {t.busPlateNo || '—'} · Departs {formatDateTime(t.departureAt)} · {formatCurrency(t.price)} / seat
                      </p>
                    </div>
                    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${seatsBadge(t.availableSeats)}`}>
                      {t.availableSeats} seat{t.availableSeats === 1 ? '' : 's'} left
                    </span>
                  </div>
                ))}
              </div>
              <SearchPager
                page={searchPage}
                pageSize={PAGE_SIZE}
                shown={(tripSearch.data?.data ?? []).length}
                total={tripSearch.data?.totalCount ?? 0}
                onPageChange={setSearchPage}
              />
            </>
          )}
        </div>
      )}

      {!search && isLoading && <Skeleton className="h-32 w-full" />}
      {!search && isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!search && !isLoading && !isError && trips?.length === 0 && (
        <EmptyState title="No trips yet" description="Add your first trip above - you'll need at least one active route and bus first." />
      )}

      {!search && !isLoading && !isError && trips?.length > 0 && (
        <div className="flex flex-col gap-2">
          {[...trips].sort((a, b) => new Date(b.departureAt) - new Date(a.departureAt)).map((trip) => {
            const route = routeById[trip.routeId];
            const bus = busById[trip.busId];
            return (
              <div key={trip.id} className="rounded-xl border border-slate-200 bg-surface p-4">
                {editingId === trip.id ? (
                  <div className="flex flex-wrap items-end gap-3">
                    <Field label="Departure">
                      <input type="datetime-local" value={editForm.departureAt} onChange={(e) => setEditForm({ ...editForm, departureAt: e.target.value })} className={`${inputClass} w-52`} />
                    </Field>
                    <Field label="Arrival">
                      <input type="datetime-local" value={editForm.arrivalAt} onChange={(e) => setEditForm({ ...editForm, arrivalAt: e.target.value })} className={`${inputClass} w-52`} />
                    </Field>
                    <Field label="Price / seat">
                      <input type="number" min="0" step="0.01" value={editForm.price} onChange={(e) => setEditForm({ ...editForm, price: e.target.value })} className={`${inputClass} w-28`} />
                    </Field>
                    <button type="button" onClick={() => saveEdit(trip.id)} disabled={updateTrip.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
                      Save
                    </button>
                    <button type="button" onClick={() => setEditingId(null)} className="text-sm text-ink-muted hover:underline">
                      Cancel
                    </button>
                  </div>
                ) : (
                  <div className="flex items-center justify-between">
                    <div>
                      <div className="mb-1 flex items-center gap-2">
                        <StatusPill status={trip.status} />
                        <span className="text-sm font-semibold text-ink">
                          {route ? `${route.origin} → ${route.destination}` : 'Unknown route'}
                        </span>
                      </div>
                      <p className="text-xs text-ink-muted">
                        {bus?.plateNo || 'Unknown bus'} · Departs {formatDateTime(trip.departureAt)} · {formatCurrency(trip.price)} / seat
                      </p>
                    </div>
                    {trip.status === 'scheduled' && (
                      <div className="flex items-center gap-3">
                        <button type="button" onClick={() => startEdit(trip)} className="text-sm text-brand hover:underline">
                          Edit
                        </button>
                        <button type="button" onClick={() => handleCancel(trip.id)} className="text-sm text-danger hover:underline">
                          Cancel
                        </button>
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function Field({ label, children }) {
  return (
    <label className="block text-left">
      <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</span>
      {children}
    </label>
  );
}
