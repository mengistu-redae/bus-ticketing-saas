import { useEffect, useState } from 'react';
import {
  useCancelTrip,
  useCreateTrip,
  useFleetBuses,
  useFleetRoutes,
  useFleetTripsManage,
  useUpdateTrip,
} from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import SearchPager from '../../components/SearchPager.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';
import { seatFillClass } from '../../lib/seats.js';

const PAGE_SIZE = 20;

const STATUS_TABS = [
  { key: 'upcoming', label: 'Upcoming' },
  { key: 'all', label: 'All' },
  { key: 'cancelled', label: 'Cancelled' },
];

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/** <input type="datetime-local"> works in local time with no timezone/seconds - Trip times are Instants (UTC ISO). */
function toDatetimeLocal(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
function fromDatetimeLocal(local) {
  return local ? new Date(local).toISOString() : undefined;
}
const nowLocal = () => toDatetimeLocal(new Date().toISOString());

/** Shared client-side checks for both the create and edit forms. Returns an error string or null. */
function validateTimesAndPrice({ departureAt, arrivalAt, price }) {
  if (Number(price) <= 0) return 'Price must be greater than zero.';
  if (arrivalAt && departureAt && new Date(arrivalAt) <= new Date(departureAt)) {
    return 'Arrival must be after departure.';
  }
  return null;
}

const emptyForm = { routeId: '', busId: '', departureAt: '', arrivalAt: '', price: '' };

/**
 * operator_admin fleet management: GET /api/fleet/trips/manage (denormalized,
 * filtered, paged) for the list; POST/PATCH/DELETE /api/fleet/trips for
 * mutations. routeId/busId are only set at creation - PATCH deliberately
 * doesn't allow changing them (seats are generated from the bus's layout once,
 * at trip creation). DELETE sets status=cancelled, not a row delete, and there
 * is no un-cancel - hence the inline confirm.
 */
export default function OperatorTrips() {
  const { data: routes } = useFleetRoutes();
  const { data: buses } = useFleetBuses();
  const createTrip = useCreateTrip();
  const updateTrip = useUpdateTrip();
  const cancelTrip = useCancelTrip();

  const [statusFilter, setStatusFilter] = useState('upcoming');
  const [routeFilter, setRouteFilter] = useState('');
  const [page, setPage] = useState(0);
  useEffect(() => setPage(0), [statusFilter, routeFilter]);

  const { data, isLoading, isError, error, refetch } = useFleetTripsManage({
    status: statusFilter,
    routeId: routeFilter || undefined,
    page,
    size: PAGE_SIZE,
  });
  const trips = data?.data ?? [];
  const total = data?.totalCount ?? 0;

  const [form, setForm] = useState(emptyForm);
  const [formError, setFormError] = useState(null);
  const [editingId, setEditingId] = useState(null);
  const [editForm, setEditForm] = useState({});
  const [rowError, setRowError] = useState(null); // { tripId, message }
  const [confirmingCancelId, setConfirmingCancelId] = useState(null);

  const activeRoutes = (routes || []).filter((r) => r.active);
  const activeBuses = (buses || []).filter((b) => b.active);
  const departureInPast = form.departureAt && new Date(form.departureAt) < new Date();

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.routeId || !form.busId || !form.departureAt || !form.price) {
      setFormError('Route, bus, departure time, and price are all required.');
      return;
    }
    const problem = validateTimesAndPrice(form);
    if (problem) {
      setFormError(problem);
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
    setConfirmingCancelId(null);
    setEditingId(trip.tripId);
    setEditForm({
      departureAt: toDatetimeLocal(trip.departureAt),
      arrivalAt: toDatetimeLocal(trip.arrivalAt),
      price: String(trip.price),
    });
  }

  async function saveEdit(tripId) {
    setRowError(null);
    const problem = validateTimesAndPrice(editForm);
    if (problem) {
      setRowError({ tripId, message: problem });
      return;
    }
    try {
      await updateTrip.mutateAsync({
        tripId,
        departureAt: fromDatetimeLocal(editForm.departureAt),
        arrivalAt: fromDatetimeLocal(editForm.arrivalAt),
        price: Number(editForm.price),
      });
      setEditingId(null);
    } catch (err) {
      setRowError({ tripId, message: err.message || 'Could not save changes.' });
    }
  }

  async function confirmCancel(tripId) {
    setRowError(null);
    try {
      await cancelTrip.mutateAsync(tripId);
      setConfirmingCancelId(null);
    } catch (err) {
      setRowError({ tripId, message: err.message || 'Could not cancel this trip.' });
    }
  }

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-ink">Trips</h1>

      <form onSubmit={handleCreate} className="mb-4 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
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
          <input type="datetime-local" min={nowLocal()} value={form.departureAt} onChange={(e) => setForm({ ...form, departureAt: e.target.value })} className={`${inputClass} w-52`} />
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
      {departureInPast && (
        <p className="mb-4 text-xs text-warning">This departure is in the past — the trip won't appear in customer search.</p>
      )}
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      {/* Filter bar */}
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div className="inline-flex rounded-lg border border-slate-200 bg-surface p-0.5 text-sm">
          {STATUS_TABS.map((t) => (
            <button
              key={t.key}
              type="button"
              onClick={() => setStatusFilter(t.key)}
              className={`rounded-md px-3 py-1 font-medium transition-colors ${
                statusFilter === t.key ? 'bg-brand text-white' : 'text-ink-muted hover:bg-slate-100'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>
        <select value={routeFilter} onChange={(e) => setRouteFilter(e.target.value)} className={`${inputClass} w-56`}>
          <option value="">All routes</option>
          {(routes || []).map((r) => (
            <option key={r.id} value={r.id}>{r.origin} → {r.destination}</option>
          ))}
        </select>
      </div>

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}

      {!isLoading && !isError && total === 0 && (
        <EmptyState
          title={
            statusFilter === 'cancelled'
              ? 'No cancelled trips'
              : statusFilter === 'all'
                ? 'No trips yet'
                : 'No upcoming trips'
          }
          description={
            statusFilter === 'upcoming'
              ? 'Add a trip above — you need at least one active route and bus first.'
              : routeFilter
                ? 'Nothing here for this route.'
                : 'Nothing to show for this filter.'
          }
        />
      )}

      {!isLoading && !isError && total > 0 && (
        <>
          <div className="flex flex-col gap-2">
            {trips.map((trip) => (
              <div key={trip.tripId} className="rounded-xl border border-slate-200 bg-surface p-4">
                {editingId === trip.tripId ? (
                  <div>
                    <p className="mb-3 text-sm font-semibold text-ink">
                      {trip.origin} → {trip.destination}
                      <span className="ml-2 font-normal text-ink-muted">{trip.busPlateNo || 'Unknown bus'}</span>
                    </p>
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
                      <button type="button" onClick={() => saveEdit(trip.tripId)} disabled={updateTrip.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
                        Save
                      </button>
                      <button type="button" onClick={() => setEditingId(null)} className="text-sm text-ink-muted hover:underline">
                        Cancel
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="flex items-center justify-between gap-4">
                    <div className="min-w-0">
                      <div className="mb-1 flex items-center gap-2">
                        <StatusPill status={trip.status} />
                        <span className="text-sm font-semibold text-ink">{trip.origin} → {trip.destination}</span>
                      </div>
                      <p className="text-xs text-ink-muted">
                        {trip.busPlateNo || 'Unknown bus'} · Departs {formatDateTime(trip.departureAt)} · {formatCurrency(trip.price)} / seat
                      </p>
                    </div>
                    <div className="flex shrink-0 items-center gap-3">
                      {trip.busCapacity > 0 && (
                        <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${seatFillClass(trip.availableSeats)}`}>
                          {trip.bookedSeats}/{trip.busCapacity} seats
                        </span>
                      )}
                      {trip.status === 'scheduled' && confirmingCancelId !== trip.tripId && (
                        <>
                          <button type="button" onClick={() => startEdit(trip)} className="text-sm text-brand hover:underline">
                            Edit
                          </button>
                          <button type="button" onClick={() => { setRowError(null); setConfirmingCancelId(trip.tripId); }} className="text-sm text-danger hover:underline">
                            Cancel
                          </button>
                        </>
                      )}
                    </div>
                  </div>
                )}

                {confirmingCancelId === trip.tripId && (
                  <div className="mt-3 flex flex-wrap items-center gap-3 rounded-lg bg-danger-light px-3 py-2 text-sm text-danger">
                    <span>Cancel this trip? Existing bookings are <strong>not</strong> refunded or notified.</span>
                    <button type="button" onClick={() => confirmCancel(trip.tripId)} disabled={cancelTrip.isPending} className="rounded-lg bg-danger px-3 py-1 font-semibold text-white disabled:opacity-50">
                      Cancel trip
                    </button>
                    <button type="button" onClick={() => setConfirmingCancelId(null)} className="font-medium hover:underline">
                      Keep
                    </button>
                  </div>
                )}

                {rowError?.tripId === trip.tripId && (
                  <div className="mt-3"><ErrorBanner message={rowError.message} /></div>
                )}
              </div>
            ))}
          </div>
          <SearchPager page={page} pageSize={PAGE_SIZE} shown={trips.length} total={total} onPageChange={setPage} />
        </>
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
