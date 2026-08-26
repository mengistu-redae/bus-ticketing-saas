import { useState } from 'react';
import { useCreateBus, useFleetBuses, useUpdateBus } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * operator_admin fleet management: GET/POST/PATCH/DELETE /api/fleet/buses -
 * DELETE soft-deactivates (see BusController's javadoc), so "deactivate" and
 * "reactivate" are both just PATCH {active} here, not two different calls.
 */
export default function OperatorBuses() {
  const { data: buses, isLoading, isError, error, refetch } = useFleetBuses();
  const createBus = useCreateBus();
  const updateBus = useUpdateBus();

  const [form, setForm] = useState({ plateNo: '', capacity: '', seatLayout: '2x2' });
  const [formError, setFormError] = useState(null);
  const [editingId, setEditingId] = useState(null);
  const [editForm, setEditForm] = useState({});
  const [rowError, setRowError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    const capacity = Number(form.capacity);
    if (!form.plateNo.trim() || !capacity || capacity < 1) {
      setFormError('Plate number and a capacity of at least 1 are required.');
      return;
    }
    try {
      await createBus.mutateAsync({
        plateNo: form.plateNo.trim(),
        capacity,
        seatLayout: form.seatLayout.trim() || undefined,
      });
      setForm({ plateNo: '', capacity: '', seatLayout: '2x2' });
    } catch (err) {
      setFormError(err.message || 'Could not create bus.');
    }
  }

  function startEdit(bus) {
    setRowError(null);
    setEditingId(bus.id);
    setEditForm({ plateNo: bus.plateNo, capacity: String(bus.capacity), seatLayout: bus.seatLayout });
  }

  async function saveEdit(busId) {
    setRowError(null);
    try {
      await updateBus.mutateAsync({
        busId,
        plateNo: editForm.plateNo.trim(),
        capacity: Number(editForm.capacity),
        seatLayout: editForm.seatLayout.trim(),
      });
      setEditingId(null);
    } catch (err) {
      setRowError(err.message || 'Could not save changes.');
    }
  }

  async function toggleActive(bus) {
    setRowError(null);
    try {
      await updateBus.mutateAsync({ busId: bus.id, active: !bus.active });
    } catch (err) {
      setRowError(err.message || 'Could not update this bus.');
    }
  }

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-ink">Buses</h1>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label="Plate number">
          <input value={form.plateNo} onChange={(e) => setForm({ ...form, plateNo: e.target.value })} placeholder="ET-3-A12345" className={`${inputClass} w-40`} />
        </Field>
        <Field label="Capacity">
          <input type="number" min="1" value={form.capacity} onChange={(e) => setForm({ ...form, capacity: e.target.value })} className={`${inputClass} w-24`} />
        </Field>
        <Field label="Seat layout">
          <input value={form.seatLayout} onChange={(e) => setForm({ ...form, seatLayout: e.target.value })} placeholder="2x2" className={`${inputClass} w-24`} />
        </Field>
        <button type="submit" disabled={createBus.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
          {createBus.isPending ? 'Adding…' : 'Add bus'}
        </button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
      {rowError && <div className="mb-4"><ErrorBanner message={rowError} /></div>}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && buses?.length === 0 && (
        <EmptyState title="No buses yet" description="Add your first bus above." />
      )}

      {!isLoading && !isError && buses?.length > 0 && (
        <div className="flex flex-col gap-2">
          {buses.map((bus) => (
            <div key={bus.id} className="rounded-xl border border-slate-200 bg-surface p-4">
              {editingId === bus.id ? (
                <div className="flex flex-wrap items-end gap-3">
                  <Field label="Plate number">
                    <input value={editForm.plateNo} onChange={(e) => setEditForm({ ...editForm, plateNo: e.target.value })} className={`${inputClass} w-40`} />
                  </Field>
                  <Field label="Capacity">
                    <input type="number" min="1" value={editForm.capacity} onChange={(e) => setEditForm({ ...editForm, capacity: e.target.value })} className={`${inputClass} w-24`} />
                  </Field>
                  <Field label="Seat layout">
                    <input value={editForm.seatLayout} onChange={(e) => setEditForm({ ...editForm, seatLayout: e.target.value })} className={`${inputClass} w-24`} />
                  </Field>
                  <button type="button" onClick={() => saveEdit(bus.id)} disabled={updateBus.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
                    Save
                  </button>
                  <button type="button" onClick={() => setEditingId(null)} className="text-sm text-ink-muted hover:underline">
                    Cancel
                  </button>
                </div>
              ) : (
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <StatusPill status={bus.active ? 'active' : 'inactive'} />
                    <span className="font-mono text-sm font-semibold text-ink">{bus.plateNo}</span>
                    <span className="text-sm text-ink-muted">{bus.capacity} seats · {bus.seatLayout}</span>
                  </div>
                  <div className="flex items-center gap-3">
                    <button type="button" onClick={() => startEdit(bus)} className="text-sm text-brand hover:underline">
                      Edit
                    </button>
                    <button type="button" onClick={() => toggleActive(bus)} className="text-sm text-ink-muted hover:underline">
                      {bus.active ? 'Deactivate' : 'Reactivate'}
                    </button>
                  </div>
                </div>
              )}
            </div>
          ))}
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
