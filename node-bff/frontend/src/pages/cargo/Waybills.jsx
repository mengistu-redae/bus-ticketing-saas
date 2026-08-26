import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useCreateWaybill, useFleetRoutes, useFleetTrips, useWaybills } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const emptyForm = {
  tripId: '',
  consignorName: '',
  consignorPhone: '',
  consignorIdNumber: '',
  consigneeName: '',
  consigneePhone: '',
  consigneeIdNumber: '',
  description: '',
  quantity: '1',
  declaredValue: '',
  grossWeightKg: '',
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
  const { data: trips } = useFleetTrips();
  const { data: routes } = useFleetRoutes();
  const createWaybill = useCreateWaybill();

  const routeById = Object.fromEntries((routes || []).map((r) => [r.id, r]));
  const tripById = Object.fromEntries((trips || []).map((t) => [t.id, t]));
  const scheduledTrips = (trips || []).filter((t) => t.status === 'scheduled');

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
    if (!form.tripId || !form.consignorName || !form.consignorPhone || !form.consigneeName || !form.consigneePhone || !form.consigneeIdNumber || !form.description || !form.grossWeightKg) {
      setFormError('Trip, both parties\' name/phone, consignee ID, description, and gross weight are all required.');
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
        description: form.description,
        quantity: form.quantity ? Number(form.quantity) : undefined,
        declaredValue: form.declaredValue ? Number(form.declaredValue) : undefined,
        grossWeightKg: Number(form.grossWeightKg),
      });
      setForm(emptyForm);
    } catch (err) {
      setFormError(err.message || 'Could not create waybill - check the description isn\'t a prohibited item and a cargo rate is configured for this route.');
    }
  }

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-ink">Cargo Waybills</h1>
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} className={`${inputClass} w-44`}>
          <option value="">All statuses</option>
          <option value="issued">Issued</option>
          <option value="dispatched">Dispatched</option>
          <option value="arrived">Arrived</option>
          <option value="collected">Collected</option>
          <option value="cancelled">Cancelled</option>
        </select>
      </div>

      <form onSubmit={handleCreate} className="mb-6 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label="Trip">
          <select value={form.tripId} onChange={(e) => setForm({ ...form, tripId: e.target.value })} className={`${inputClass} max-w-md`}>
            <option value="">Select a trip</option>
            {scheduledTrips.map((t) => (
              <option key={t.id} value={t.id}>{tripLabel(t)}</option>
            ))}
          </select>
        </Field>

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

        <div className="mt-4 flex flex-wrap items-end gap-3">
          <Field label="Description">
            <input value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} className={`${inputClass} w-64`} />
          </Field>
          <Field label="Quantity">
            <input type="number" min="1" value={form.quantity} onChange={(e) => setForm({ ...form, quantity: e.target.value })} className={`${inputClass} w-20`} />
          </Field>
          <Field label="Declared value (optional)">
            <input type="number" min="0" step="0.01" value={form.declaredValue} onChange={(e) => setForm({ ...form, declaredValue: e.target.value })} className={`${inputClass} w-32`} />
          </Field>
          <Field label="Gross weight (kg)">
            <input type="number" min="0.01" step="0.01" value={form.grossWeightKg} onChange={(e) => setForm({ ...form, grossWeightKg: e.target.value })} className={`${inputClass} w-28`} />
          </Field>
          <button type="submit" disabled={createWaybill.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
            {createWaybill.isPending ? 'Creating…' : 'Create waybill'}
          </button>
        </div>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && waybills?.length === 0 && (
        <EmptyState title="No waybills yet" description="Create your first waybill above - you'll need at least one scheduled trip and a cargo rate configured for its route." />
      )}

      {!isLoading && !isError && waybills?.length > 0 && (
        <div className="flex flex-col gap-2">
          {[...waybills].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt)).map((wb) => {
            const trip = tripById[wb.tripId];
            const route = trip ? routeById[trip.routeId] : null;
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
                  <p className="text-xs text-ink-muted">{wb.description} · {wb.grossWeightKg} kg</p>
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
