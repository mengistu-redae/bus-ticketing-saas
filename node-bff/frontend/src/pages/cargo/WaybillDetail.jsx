import { useState } from 'react';
import { useLocation, useParams } from 'react-router-dom';
import {
  useArriveWaybill,
  useCancelWaybill,
  useCollectWaybill,
  useDispatchWaybill,
  useFleetRoutes,
  useFleetTrips,
  useUpdateWaybill,
  useWaybill,
} from '../../api/queries.js';
import { ApiError } from '../../api/client.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * One waybill - shared by AGENT and OPERATOR_ADMIN (identical backend
 * permissions on every com.bustix.cargo.CargoWaybillController endpoint).
 * Route/departure resolved client-side from the tenant-scoped fleet
 * trips/routes lists (same pattern Waybills.jsx uses), not GET
 * /api/trips/{tripId} - that endpoint is CUSTOMER/AGENT only, not
 * OPERATOR_ADMIN, and this page has to work for both roles.
 */
export default function WaybillDetail() {
  const { waybillId } = useParams();
  const location = useLocation();

  const stateWaybill = location.state?.waybill;
  const waybillQuery = useWaybill(waybillId);
  const waybill = waybillQuery.data || stateWaybill;

  const { data: trips } = useFleetTrips();
  const { data: routes } = useFleetRoutes();
  const trip = waybill ? (trips || []).find((t) => t.id === waybill.tripId) : null;
  const route = trip ? (routes || []).find((r) => r.id === trip.routeId) : null;

  const dispatchWaybill = useDispatchWaybill(waybillId);
  const arriveWaybill = useArriveWaybill(waybillId);
  const collectWaybill = useCollectWaybill(waybillId);
  const cancelWaybill = useCancelWaybill(waybillId);
  const updateWaybill = useUpdateWaybill(waybillId);

  const [actionError, setActionError] = useState(null);
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [presentedId, setPresentedId] = useState('');
  const [editing, setEditing] = useState(false);
  const [editForm, setEditForm] = useState(null);

  async function handleDispatch() {
    setActionError(null);
    try {
      await dispatchWaybill.mutateAsync();
    } catch (err) {
      setActionError(err.message || 'Could not mark this waybill dispatched.');
    }
  }

  async function handleArrive() {
    setActionError(null);
    try {
      await arriveWaybill.mutateAsync();
    } catch (err) {
      setActionError(err.message || 'Could not mark this waybill arrived.');
    }
  }

  async function handleCollect() {
    setActionError(null);
    try {
      await collectWaybill.mutateAsync({ presentedIdNumber: presentedId.trim() });
      setPresentedId('');
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setActionError(err.message || "Presented ID doesn't match the ID on file.");
      } else {
        setActionError(err.message || 'Could not collect this waybill.');
      }
    }
  }

  async function handleCancel() {
    setActionError(null);
    try {
      await cancelWaybill.mutateAsync();
      setConfirmingCancel(false);
    } catch (err) {
      setActionError(err.message || 'Could not cancel this waybill.');
      setConfirmingCancel(false);
    }
  }

  function startEdit() {
    setActionError(null);
    setEditForm({
      consignorName: waybill.consignorName,
      consignorPhone: waybill.consignorPhone,
      consignorIdNumber: waybill.consignorIdNumber || '',
      consigneeName: waybill.consigneeName,
      consigneePhone: waybill.consigneePhone,
      consigneeIdNumber: waybill.consigneeIdNumber,
      description: waybill.description,
      quantity: String(waybill.quantity),
      declaredValue: waybill.declaredValue != null ? String(waybill.declaredValue) : '',
      grossWeightKg: String(waybill.grossWeightKg),
    });
    setEditing(true);
  }

  async function saveEdit() {
    setActionError(null);
    try {
      await updateWaybill.mutateAsync({
        ...editForm,
        quantity: Number(editForm.quantity),
        declaredValue: editForm.declaredValue ? Number(editForm.declaredValue) : undefined,
        grossWeightKg: Number(editForm.grossWeightKg),
      });
      setEditing(false);
    } catch (err) {
      setActionError(err.message || 'Could not save changes.');
    }
  }

  async function handlePaymentStatusChange(event) {
    setActionError(null);
    try {
      await updateWaybill.mutateAsync({ paymentStatus: event.target.value });
    } catch (err) {
      setActionError(err.message || 'Could not update payment status.');
    }
  }

  if (waybillQuery.isLoading && !stateWaybill) {
    return <Skeleton className="h-48 w-full max-w-xl" />;
  }
  if (waybillQuery.isError && !stateWaybill) {
    return <ErrorBanner message={waybillQuery.error?.message} onRetry={waybillQuery.refetch} />;
  }
  if (!waybill) {
    return <ErrorBanner message="Waybill not found." />;
  }

  const status = waybill.status;
  const refundAmount = cancelWaybill.data?.refundAmount;

  return (
    <div className="max-w-2xl">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-ink">Waybill</h1>
          <p className="font-mono text-xs text-ink-muted">{waybill.waybillNumber}</p>
        </div>
        <StatusPill status={status} />
      </div>

      <div className="rounded-xl border border-slate-200 bg-surface p-5">
        {route ? (
          <>
            <p className="text-lg font-semibold text-ink">
              {route.origin} <span className="text-ink-muted">&rarr;</span> {route.destination}
            </p>
            {trip && <p className="text-sm text-ink-muted">Departs {formatDateTime(trip.departureAt)}</p>}
          </>
        ) : (
          <p className="text-sm italic text-ink-muted">Trip details unavailable.</p>
        )}

        {editing ? (
          <div className="mt-4 border-t border-slate-100 pt-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <fieldset className="rounded-lg border border-slate-200 p-3">
                <legend className="px-1 text-xs font-semibold uppercase tracking-wide text-ink-muted">Consignor</legend>
                <div className="flex flex-col gap-2">
                  <Field label="Name"><input value={editForm.consignorName} onChange={(e) => setEditForm({ ...editForm, consignorName: e.target.value })} className={inputClass} /></Field>
                  <Field label="Phone"><input value={editForm.consignorPhone} onChange={(e) => setEditForm({ ...editForm, consignorPhone: e.target.value })} className={inputClass} /></Field>
                  <Field label="ID number"><input value={editForm.consignorIdNumber} onChange={(e) => setEditForm({ ...editForm, consignorIdNumber: e.target.value })} className={inputClass} /></Field>
                </div>
              </fieldset>
              <fieldset className="rounded-lg border border-slate-200 p-3">
                <legend className="px-1 text-xs font-semibold uppercase tracking-wide text-ink-muted">Consignee</legend>
                <div className="flex flex-col gap-2">
                  <Field label="Name"><input value={editForm.consigneeName} onChange={(e) => setEditForm({ ...editForm, consigneeName: e.target.value })} className={inputClass} /></Field>
                  <Field label="Phone"><input value={editForm.consigneePhone} onChange={(e) => setEditForm({ ...editForm, consigneePhone: e.target.value })} className={inputClass} /></Field>
                  <Field label="ID number"><input value={editForm.consigneeIdNumber} onChange={(e) => setEditForm({ ...editForm, consigneeIdNumber: e.target.value })} className={inputClass} /></Field>
                </div>
              </fieldset>
            </div>
            <div className="mt-4 flex flex-wrap items-end gap-3">
              <Field label="Description"><input value={editForm.description} onChange={(e) => setEditForm({ ...editForm, description: e.target.value })} className={`${inputClass} w-64`} /></Field>
              <Field label="Quantity"><input type="number" min="1" value={editForm.quantity} onChange={(e) => setEditForm({ ...editForm, quantity: e.target.value })} className={`${inputClass} w-20`} /></Field>
              <Field label="Declared value"><input type="number" min="0" step="0.01" value={editForm.declaredValue} onChange={(e) => setEditForm({ ...editForm, declaredValue: e.target.value })} className={`${inputClass} w-32`} /></Field>
              <Field label="Gross weight (kg)"><input type="number" min="0.01" step="0.01" value={editForm.grossWeightKg} onChange={(e) => setEditForm({ ...editForm, grossWeightKg: e.target.value })} className={`${inputClass} w-28`} /></Field>
              <button type="button" onClick={saveEdit} disabled={updateWaybill.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
                {updateWaybill.isPending ? 'Saving…' : 'Save'}
              </button>
              <button type="button" onClick={() => setEditing(false)} className="text-sm text-ink-muted hover:underline">
                Cancel
              </button>
            </div>
          </div>
        ) : (
          <>
            <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-slate-100 pt-4 text-sm">
              <div>
                <dt className="text-ink-muted">Consignor</dt>
                <dd className="text-ink">{waybill.consignorName} · {waybill.consignorPhone}</dd>
              </div>
              <div>
                <dt className="text-ink-muted">Consignee</dt>
                <dd className="text-ink">
                  {waybill.consigneeName} · {waybill.consigneePhone}
                  {status === 'collected' && waybill.consigneeIdVerified && (
                    <span className="ml-1 text-xs text-success">(ID verified)</span>
                  )}
                </dd>
              </div>
              <div>
                <dt className="text-ink-muted">Description</dt>
                <dd className="text-ink">{waybill.description} · qty {waybill.quantity}</dd>
              </div>
              <div>
                <dt className="text-ink-muted">Weight</dt>
                <dd className="text-ink">{waybill.grossWeightKg} kg ({waybill.excessWeightKg} kg over threshold)</dd>
              </div>
              <div>
                <dt className="text-ink-muted">Freight + surcharge + handling</dt>
                <dd className="font-mono text-ink">
                  {formatCurrency(waybill.baseFreightCharge)} + {formatCurrency(waybill.weightSurcharge)} + {formatCurrency(waybill.handlingServiceFee)}
                </dd>
              </div>
              <div>
                <dt className="text-ink-muted">Total</dt>
                <dd className="font-mono font-semibold text-ink">{formatCurrency(waybill.totalCargoCost)}</dd>
              </div>
              <div>
                <dt className="text-ink-muted">Issued</dt>
                <dd className="text-ink">{formatDateTime(waybill.createdAt)}</dd>
              </div>
              {waybill.dispatchedAt && (
                <div>
                  <dt className="text-ink-muted">Dispatched</dt>
                  <dd className="text-ink">{formatDateTime(waybill.dispatchedAt)}</dd>
                </div>
              )}
              {waybill.arrivedAt && (
                <div>
                  <dt className="text-ink-muted">Arrived</dt>
                  <dd className="text-ink">{formatDateTime(waybill.arrivedAt)}</dd>
                </div>
              )}
              {waybill.collectedAt && (
                <div>
                  <dt className="text-ink-muted">Collected</dt>
                  <dd className="text-ink">{formatDateTime(waybill.collectedAt)}</dd>
                </div>
              )}
            </dl>

            {status === 'issued' && (
              <button type="button" onClick={startEdit} className="mt-4 text-sm text-brand hover:underline">
                Edit shipment details
              </button>
            )}
          </>
        )}

        {refundAmount !== undefined && (
          <div className="mt-4 rounded-lg bg-success-light px-3 py-2 text-sm text-success">
            Refunded {formatCurrency(refundAmount)}
          </div>
        )}

        <div className="mt-4 flex items-center gap-3 border-t border-slate-100 pt-4 text-sm">
          <span className="text-ink-muted">Payment</span>
          <select
            value={waybill.paymentStatus}
            onChange={handlePaymentStatusChange}
            disabled={updateWaybill.isPending}
            className="rounded-lg border border-slate-300 px-2 py-1 text-sm capitalize focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
          >
            <option value="unpaid">Unpaid</option>
            <option value="paid">Paid</option>
            <option value="collect_on_delivery">Collect on delivery</option>
          </select>
        </div>
      </div>

      {actionError && (
        <div className="mt-4">
          <ErrorBanner message={actionError} />
        </div>
      )}

      <div className="mt-5 flex flex-wrap items-center gap-3">
        {status === 'issued' && (
          <button type="button" onClick={handleDispatch} disabled={dispatchWaybill.isPending} className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark disabled:opacity-50">
            {dispatchWaybill.isPending ? 'Marking…' : 'Mark dispatched'}
          </button>
        )}
        {status === 'dispatched' && (
          <button type="button" onClick={handleArrive} disabled={arriveWaybill.isPending} className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark disabled:opacity-50">
            {arriveWaybill.isPending ? 'Marking…' : 'Mark arrived'}
          </button>
        )}
        {status === 'arrived' && (
          <div className="flex items-center gap-2 rounded-lg border border-slate-200 p-2">
            <input
              value={presentedId}
              onChange={(e) => setPresentedId(e.target.value)}
              placeholder="ID presented at pickup"
              className="w-48 rounded-lg border border-slate-300 px-2 py-1 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
            />
            <button type="button" onClick={handleCollect} disabled={collectWaybill.isPending || !presentedId.trim()} className="rounded-lg bg-success px-3 py-1.5 text-sm font-semibold text-white hover:bg-success/90 disabled:opacity-50">
              {collectWaybill.isPending ? 'Collecting…' : 'Collect'}
            </button>
          </div>
        )}

        {status === 'issued' && !confirmingCancel && (
          <button type="button" onClick={() => setConfirmingCancel(true)} className="rounded-lg border border-danger/40 px-4 py-2 text-sm font-medium text-danger hover:bg-danger-light">
            Cancel waybill
          </button>
        )}
        {confirmingCancel && (
          <div className="flex items-center gap-3 rounded-lg border border-danger/30 bg-danger-light p-3">
            <p className="text-sm text-danger">Cancel this waybill? This can't be undone.</p>
            <button type="button" disabled={cancelWaybill.isPending} onClick={handleCancel} className="shrink-0 rounded-lg bg-danger px-3 py-1.5 text-sm font-semibold text-white hover:bg-danger/90 disabled:opacity-50">
              {cancelWaybill.isPending ? 'Cancelling…' : 'Yes, cancel'}
            </button>
            <button type="button" onClick={() => setConfirmingCancel(false)} className="shrink-0 text-sm text-ink-muted hover:underline">
              Never mind
            </button>
          </div>
        )}
      </div>
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
