import { useState } from 'react';
import { useCargoRates, useCreateCargoRate, useDeleteCargoRate, useFleetRoutes, useUpdateCargoRate } from '../../api/queries.js';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency } from '../../lib/format.js';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const emptyForm = { routeId: '', freeWeightThresholdKg: '30', baseFreightCharge: '', surchargePerKg: '10', handlingFee: '50' };

/**
 * operator_admin cargo freight pricing: GET/POST/PATCH/DELETE
 * /api/fleet/cargo-rates - same shape as RefundPolicies.jsx (route_id
 * nullable = operator-wide default), simpler fields (plain numbers, not a
 * tiers array). Real delete, same reasoning as refund policies (see
 * CargoRateController's javadoc) - unlike a missing refund policy though,
 * a missing cargo rate blocks waybill creation rather than defaulting to
 * free, so deleting the only rate for a route isn't fully consequence-free
 * the way deleting a refund policy is.
 */
export default function OperatorCargoRates() {
  const { data: rates, isLoading, isError, error, refetch } = useCargoRates();
  const { data: routes } = useFleetRoutes();
  const createRate = useCreateCargoRate();
  const updateRate = useUpdateCargoRate();
  const deleteRate = useDeleteCargoRate();

  const routeById = Object.fromEntries((routes || []).map((r) => [r.id, r]));

  const [form, setForm] = useState(emptyForm);
  const [formError, setFormError] = useState(null);
  const [editingId, setEditingId] = useState(null);
  const [editForm, setEditForm] = useState(null);
  const [rowError, setRowError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.baseFreightCharge) {
      setFormError('Base freight charge is required.');
      return;
    }
    try {
      await createRate.mutateAsync({
        routeId: form.routeId || undefined,
        freeWeightThresholdKg: Number(form.freeWeightThresholdKg),
        baseFreightCharge: Number(form.baseFreightCharge),
        surchargePerKg: Number(form.surchargePerKg),
        handlingFee: Number(form.handlingFee),
      });
      setForm(emptyForm);
    } catch (err) {
      setFormError(err.message || 'Could not create rate - an operator-wide default or a rate for this route may already exist.');
    }
  }

  function startEdit(rate) {
    setRowError(null);
    setEditingId(rate.id);
    setEditForm({
      freeWeightThresholdKg: String(rate.freeWeightThresholdKg),
      baseFreightCharge: String(rate.baseFreightCharge),
      surchargePerKg: String(rate.surchargePerKg),
      handlingFee: String(rate.handlingFee),
    });
  }

  async function saveEdit(rateId) {
    setRowError(null);
    try {
      await updateRate.mutateAsync({
        rateId,
        freeWeightThresholdKg: Number(editForm.freeWeightThresholdKg),
        baseFreightCharge: Number(editForm.baseFreightCharge),
        surchargePerKg: Number(editForm.surchargePerKg),
        handlingFee: Number(editForm.handlingFee),
      });
      setEditingId(null);
    } catch (err) {
      setRowError(err.message || 'Could not save changes.');
    }
  }

  async function handleDelete(rateId) {
    setRowError(null);
    try {
      await deleteRate.mutateAsync(rateId);
    } catch (err) {
      setRowError(err.message || 'Could not delete this rate.');
    }
  }

  return (
    <div>
      <p className="mb-6 text-sm text-ink-muted">
        A route-specific rate overrides the operator-wide default for that route only. Unlike refund policies, a
        missing rate blocks waybill creation on that route rather than defaulting to free.
      </p>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label="Applies to">
          <select value={form.routeId} onChange={(e) => setForm({ ...form, routeId: e.target.value })} className={`${inputClass} w-56`}>
            <option value="">Operator-wide default</option>
            {(routes || []).map((r) => (
              <option key={r.id} value={r.id}>{r.origin} → {r.destination}</option>
            ))}
          </select>
        </Field>
        <Field label="Free weight (kg)">
          <input type="number" min="0" step="0.01" value={form.freeWeightThresholdKg} onChange={(e) => setForm({ ...form, freeWeightThresholdKg: e.target.value })} className={`${inputClass} w-24`} />
        </Field>
        <Field label="Base freight charge">
          <input type="number" min="0" step="0.01" value={form.baseFreightCharge} onChange={(e) => setForm({ ...form, baseFreightCharge: e.target.value })} className={`${inputClass} w-32`} />
        </Field>
        <Field label="Surcharge / kg over">
          <input type="number" min="0" step="0.01" value={form.surchargePerKg} onChange={(e) => setForm({ ...form, surchargePerKg: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <Field label="Handling fee">
          <input type="number" min="0" step="0.01" value={form.handlingFee} onChange={(e) => setForm({ ...form, handlingFee: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <button type="submit" disabled={createRate.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
          {createRate.isPending ? 'Creating…' : 'Create rate'}
        </button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
      {rowError && <div className="mb-4"><ErrorBanner message={rowError} /></div>}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && rates?.length === 0 && (
        <EmptyState title="No cargo rates configured" description="Every waybill creation is blocked until you add at least an operator-wide default above." />
      )}

      {!isLoading && !isError && rates?.length > 0 && (
        <div className="flex flex-col gap-2">
          {rates.map((rate) => {
            const route = rate.routeId ? routeById[rate.routeId] : null;
            return (
              <div key={rate.id} className="rounded-xl border border-slate-200 bg-surface p-4">
                <div className="mb-2 flex items-center justify-between">
                  <span className="text-sm font-semibold text-ink">
                    {rate.routeId ? (route ? `${route.origin} → ${route.destination}` : 'Route no longer active') : 'Operator-wide default'}
                  </span>
                  {editingId !== rate.id && (
                    <div className="flex items-center gap-3">
                      <button type="button" onClick={() => startEdit(rate)} className="text-sm text-brand hover:underline">
                        Edit
                      </button>
                      <button type="button" onClick={() => handleDelete(rate.id)} className="text-sm text-danger hover:underline">
                        Delete
                      </button>
                    </div>
                  )}
                </div>

                {editingId === rate.id ? (
                  <div className="flex flex-wrap items-end gap-3">
                    <Field label="Free weight (kg)">
                      <input type="number" min="0" step="0.01" value={editForm.freeWeightThresholdKg} onChange={(e) => setEditForm({ ...editForm, freeWeightThresholdKg: e.target.value })} className={`${inputClass} w-24`} />
                    </Field>
                    <Field label="Base freight charge">
                      <input type="number" min="0" step="0.01" value={editForm.baseFreightCharge} onChange={(e) => setEditForm({ ...editForm, baseFreightCharge: e.target.value })} className={`${inputClass} w-32`} />
                    </Field>
                    <Field label="Surcharge / kg over">
                      <input type="number" min="0" step="0.01" value={editForm.surchargePerKg} onChange={(e) => setEditForm({ ...editForm, surchargePerKg: e.target.value })} className={`${inputClass} w-28`} />
                    </Field>
                    <Field label="Handling fee">
                      <input type="number" min="0" step="0.01" value={editForm.handlingFee} onChange={(e) => setEditForm({ ...editForm, handlingFee: e.target.value })} className={`${inputClass} w-28`} />
                    </Field>
                    <button type="button" onClick={() => saveEdit(rate.id)} disabled={updateRate.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
                      Save
                    </button>
                    <button type="button" onClick={() => setEditingId(null)} className="text-sm text-ink-muted hover:underline">
                      Cancel
                    </button>
                  </div>
                ) : (
                  <p className="text-sm text-ink-muted">
                    First {rate.freeWeightThresholdKg}kg free, then {formatCurrency(rate.surchargePerKg)}/kg · base {formatCurrency(rate.baseFreightCharge)} · handling {formatCurrency(rate.handlingFee)}
                  </p>
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
