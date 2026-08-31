import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useCreateWaybill, useFleetRoutes, useFleetTrips, usePendingCargoRequests, useWaybills } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import WaybillItemsEditor from '../../components/WaybillItemsEditor.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

// Same as inputClass minus `w-full` - for controls that want to stay narrow.
// Tailwind emits `.w-full` after the numbered width utilities, so appending
// `w-44` to a class string that already has `w-full` does nothing.
const narrowInputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const emptyItem = { description: '', quantity: '1', declaredValue: '', grossWeightKg: '' };

const emptyForm = {
  tripId: '',
  consignorName: '',
  consignorPhone: '',
  consignorIdNumber: '',
  consigneeName: '',
  consigneePhone: '',
  consigneeIdNumber: '',
  description: '',
  items: [emptyItem],
};

/**
 * Staff waybill list + create form - AGENT and OPERATOR_ADMIN alike (see
 * com.bustix.cargo.CargoWaybillController: both roles have identical
 * permissions on every waybill endpoint, unlike booking pages), one shared
 * route tree rather than duplicated per role.
 *
 * GET /api/cargo/waybills returns bare CargoWaybill rows (tripId, not a
 * denormalized route/departure) - same shape as GET /api/fleet/trips, so
 * route/departure for display is resolved client-side from the trips/routes
 * lists this page already loads for the create-waybill trip picker, same
 * pattern pages/operator/Trips.jsx uses for route/bus names.
 */
export default function Waybills() {
  const [statusFilter, setStatusFilter] = useState('');
  const { data: waybills, isLoading, isError, error, refetch } = useWaybills({ status: statusFilter || undefined });
  const { data: pendingRequests } = usePendingCargoRequests();
  const { data: trips } = useFleetTrips();
  const { data: routes } = useFleetRoutes();
  const createWaybill = useCreateWaybill();

  const routeById = Object.fromEntries((routes || []).map((r) => [r.id, r]));
  const tripById = Object.fromEntries((trips || []).map((t) => [t.id, t]));
  const scheduledTrips = (trips || []).filter((t) => t.status === 'scheduled');
  const noScheduledTrips = trips != null && scheduledTrips.length === 0;
  // Oldest first - this is a review queue, so the longest-waiting request
  // should be at the top.
  const sortedRequests = [...(pendingRequests || [])].sort(
    (a, b) => new Date(a.waybill.createdAt) - new Date(b.waybill.createdAt),
  );

  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [formError, setFormError] = useState(null);

  function tripLabel(trip) {
    const route = routeById[trip.routeId];
    const routeLabel = route ? `${route.origin} → ${route.destination}` : 'Unknown route';
    return `${routeLabel} · ${formatDateTime(trip.departureAt)}`;
  }

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    const itemsValid = form.items.length > 0 && form.items.every((i) => i.description && Number(i.grossWeightKg) > 0);
    if (!form.tripId || !form.consignorName || !form.consignorPhone || !form.consigneeName || !form.consigneePhone || !form.consigneeIdNumber || !itemsValid) {
      setFormError('Trip, both parties\' name/phone, consignee ID, and every item\'s description/gross weight are all required.');
      return;
    }
    try {
      await createWaybill.mutateAsync({
        tripId: form.tripId,
        consignorName: form.consignorName,
        consignorPhone: form.consignorPhone,
        consignorIdNumber: form.consignorIdNumber || undefined,
        consigneeName: form.consigneeName,
        consigneePhone: form.consigneePhone,
        consigneeIdNumber: form.consigneeIdNumber,
        description: form.description || undefined,
        items: form.items.map((i) => ({
          description: i.description,
          quantity: i.quantity ? Number(i.quantity) : undefined,
          declaredValue: i.declaredValue ? Number(i.declaredValue) : undefined,
          grossWeightKg: Number(i.grossWeightKg),
        })),
      });
      setForm(emptyForm);
    } catch (err) {
      setFormError(err.message || 'Could not create waybill - check no item\'s description is a prohibited item and a cargo rate is configured for this route.');
    }
  }

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-ink">Cargo Waybills</h1>
        <select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
          aria-label="Filter waybills by status"
          className={`${narrowInputClass} w-48`}
        >
          <option value="">All statuses</option>
          <option value="issued">Issued</option>
          <option value="dispatched">Dispatched</option>
          <option value="arrived">Arrived</option>
          <option value="collected">Collected</option>
          <option value="cancelled">Cancelled</option>
        </select>
      </div>

      {sortedRequests.length > 0 && (
        <div className="mb-6 rounded-xl border border-warning/40 bg-warning-light p-4">
          <h2 className="mb-3 text-sm font-semibold text-ink">
            Pending customer requests ({sortedRequests.length})
          </h2>
          <div className="flex flex-col gap-2">
            {sortedRequests.map(({ waybill: wb, items }) => (
              <Link
                key={wb.id}
                to={`/cargo/waybills/${wb.id}`}
                className="flex items-center justify-between rounded-lg border border-slate-200 bg-surface p-3 hover:border-brand/40"
              >
                <div>
                  <span className="font-mono text-xs text-ink-muted">{wb.waybillNumber}</span>
                  <p className="text-sm font-semibold text-ink">
                    {wb.consignorName} → {wb.consigneeName}
                  </p>
                  <p className="text-xs text-ink-muted">
                    {wb.description || `${items.length} item${items.length === 1 ? '' : 's'}`} · {wb.grossWeightKg} kg
                  </p>
                </div>
                <span className="text-sm font-medium text-brand">Review &rsaquo;</span>
              </Link>
            ))}
          </div>
        </div>
      )}

      <div className="mb-6">
        <button
          type="button"
          onClick={() => setShowCreate((v) => !v)}
          className="rounded-lg border border-brand/40 px-3 py-1.5 text-sm font-medium text-brand hover:bg-brand-light/40"
        >
          {showCreate ? 'Close' : '+ New waybill'}
        </button>
      </div>

      {showCreate && (<>
      <form onSubmit={handleCreate} className="mb-4 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label="Trip">
          <select value={form.tripId} onChange={(e) => setForm({ ...form, tripId: e.target.value })} className={`${inputClass} max-w-md`}>
            <option value="">Select a trip</option>
            {scheduledTrips.map((t) => (
              <option key={t.id} value={t.id}>{tripLabel(t)}</option>
            ))}
          </select>
        </Field>
        {noScheduledTrips && (
          <p className="mt-1 text-xs text-ink-muted">
            No scheduled trips yet -{' '}
            <Link to="/operator/trips" className="text-brand hover:underline">create one</Link>{' '}
            before issuing a waybill.
          </p>
        )}

        <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
          <fieldset className="rounded-lg border border-slate-200 p-3">
            <legend className="px-1 text-xs font-semibold uppercase tracking-wide text-ink-muted">Consignor (sender)</legend>
            <div className="flex flex-col gap-2">
              <Field label="Name"><input value={form.consignorName} onChange={(e) => setForm({ ...form, consignorName: e.target.value })} className={inputClass} /></Field>
              <Field label="Phone (+2519xxxxxxxx)"><input value={form.consignorPhone} onChange={(e) => setForm({ ...form, consignorPhone: e.target.value })} className={inputClass} /></Field>
              <Field label="ID number (optional)"><input value={form.consignorIdNumber} onChange={(e) => setForm({ ...form, consignorIdNumber: e.target.value })} className={inputClass} /></Field>
            </div>
          </fieldset>
          <fieldset className="rounded-lg border border-slate-200 p-3">
            <legend className="px-1 text-xs font-semibold uppercase tracking-wide text-ink-muted">Consignee (receiver)</legend>
            <div className="flex flex-col gap-2">
              <Field label="Name"><input value={form.consigneeName} onChange={(e) => setForm({ ...form, consigneeName: e.target.value })} className={inputClass} /></Field>
              <Field label="Phone (+2519xxxxxxxx)"><input value={form.consigneePhone} onChange={(e) => setForm({ ...form, consigneePhone: e.target.value })} className={inputClass} /></Field>
              <Field label="ID number (required - checked at pickup)"><input value={form.consigneeIdNumber} onChange={(e) => setForm({ ...form, consigneeIdNumber: e.target.value })} className={inputClass} /></Field>
            </div>
          </fieldset>
        </div>

        <div className="mt-4">
          <Field label="Shipment summary (optional)">
            <input value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} className={`${inputClass} w-full max-w-md`} placeholder="e.g. 3 boxes of textiles + 1 crate of electronics" />
          </Field>
        </div>

        <div className="mt-4">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Items</span>
          <WaybillItemsEditor items={form.items} onChange={(items) => setForm({ ...form, items })} />
        </div>

        <div className="mt-4">
          <button type="submit" disabled={createWaybill.isPending || noScheduledTrips} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
            {createWaybill.isPending ? 'Creating…' : 'Create waybill'}
          </button>
        </div>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
      </>)}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && waybills?.length === 0 && (
        <EmptyState title="No waybills yet" description="Use “+ New waybill” above to create one - you'll need at least one scheduled trip and a cargo rate configured for its route." />
      )}

      {!isLoading && !isError && waybills?.length > 0 && (
        <div className="flex flex-col gap-2">
          {[...waybills].sort((a, b) => new Date(b.waybill.createdAt) - new Date(a.waybill.createdAt)).map(({ waybill: wb, items }) => {
            const trip = tripById[wb.tripId];
            const route = trip ? routeById[trip.routeId] : null;
            const summary = wb.description || `${items.length} item${items.length === 1 ? '' : 's'}`;
            return (
              <Link
                key={wb.id}
                to={`/cargo/waybills/${wb.id}`}
                className="flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-4 hover:border-brand/40 hover:bg-brand-light/30"
              >
                <div>
                  <div className="mb-1 flex items-center gap-2">
                    <StatusPill status={wb.status} />
                    <span className="font-mono text-xs text-ink-muted">{wb.waybillNumber}</span>
                  </div>
                  <p className="text-sm font-semibold text-ink">
                    {route ? `${route.origin} → ${route.destination}` : 'Unknown route'} · {wb.consignorName} → {wb.consigneeName}
                  </p>
                  <p className="text-xs text-ink-muted">{summary} · {wb.grossWeightKg} kg</p>
                </div>
                <span className="font-mono text-sm font-semibold text-ink">{formatCurrency(wb.totalCargoCost)}</span>
              </Link>
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
