import { useState } from 'react';
import { useCreateRoute, useFleetRoutes, useUpdateRoute } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const emptyForm = { origin: '', destination: '', distanceKm: '', originTerminal: '', destinationTerminal: '' };

/** operator_admin fleet management: GET/POST/PATCH/DELETE /api/fleet/routes - DELETE soft-deactivates. */
export default function OperatorRoutes() {
  const { data: routes, isLoading, isError, error, refetch } = useFleetRoutes();
  const createRoute = useCreateRoute();
  const updateRoute = useUpdateRoute();

  const [form, setForm] = useState(emptyForm);
  const [formError, setFormError] = useState(null);
  const [editingId, setEditingId] = useState(null);
  const [editForm, setEditForm] = useState({});
  const [rowError, setRowError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.origin.trim() || !form.destination.trim()) {
      setFormError('Origin and destination are required.');
      return;
    }
    try {
      await createRoute.mutateAsync({
        origin: form.origin.trim(),
        destination: form.destination.trim(),
        distanceKm: form.distanceKm ? Number(form.distanceKm) : undefined,
        originTerminal: form.originTerminal.trim() || undefined,
        destinationTerminal: form.destinationTerminal.trim() || undefined,
      });
      setForm(emptyForm);
    } catch (err) {
      setFormError(err.message || 'Could not create route.');
    }
  }

  function startEdit(route) {
    setRowError(null);
    setEditingId(route.id);
    setEditForm({
      origin: route.origin,
      destination: route.destination,
      distanceKm: route.distanceKm != null ? String(route.distanceKm) : '',
      originTerminal: route.originTerminal || '',
      destinationTerminal: route.destinationTerminal || '',
    });
  }

  async function saveEdit(routeId) {
    setRowError(null);
    try {
      await updateRoute.mutateAsync({
        routeId,
        origin: editForm.origin.trim(),
        destination: editForm.destination.trim(),
        distanceKm: editForm.distanceKm ? Number(editForm.distanceKm) : undefined,
        originTerminal: editForm.originTerminal.trim(),
        destinationTerminal: editForm.destinationTerminal.trim(),
      });
      setEditingId(null);
    } catch (err) {
      setRowError(err.message || 'Could not save changes.');
    }
  }

  async function toggleActive(route) {
    setRowError(null);
    try {
      await updateRoute.mutateAsync({ routeId: route.id, active: !route.active });
    } catch (err) {
      setRowError(err.message || 'Could not update this route.');
    }
  }

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-ink">Routes</h1>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label="Origin">
          <input value={form.origin} onChange={(e) => setForm({ ...form, origin: e.target.value })} placeholder="Addis Ababa" className={`${inputClass} w-36`} />
        </Field>
        <Field label="Destination">
          <input value={form.destination} onChange={(e) => setForm({ ...form, destination: e.target.value })} placeholder="Bahir Dar" className={`${inputClass} w-36`} />
        </Field>
        <Field label="Distance (km, optional)">
          <input type="number" min="0" step="0.1" value={form.distanceKm} onChange={(e) => setForm({ ...form, distanceKm: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <Field label="Origin terminal (optional)">
          <input value={form.originTerminal} onChange={(e) => setForm({ ...form, originTerminal: e.target.value })} placeholder="Meskel Square Terminal" className={`${inputClass} w-48`} />
        </Field>
        <Field label="Destination terminal (optional)">
          <input value={form.destinationTerminal} onChange={(e) => setForm({ ...form, destinationTerminal: e.target.value })} className={`${inputClass} w-48`} />
        </Field>
        <button type="submit" disabled={createRoute.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
          {createRoute.isPending ? 'Adding…' : 'Add route'}
        </button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
      {rowError && <div className="mb-4"><ErrorBanner message={rowError} /></div>}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && routes?.length === 0 && (
        <EmptyState title="No routes yet" description="Add your first route above." />
      )}

      {!isLoading && !isError && routes?.length > 0 && (
        <div className="flex flex-col gap-2">
          {routes.map((route) => (
            <div key={route.id} className="rounded-xl border border-slate-200 bg-surface p-4">
              {editingId === route.id ? (
                <div className="flex flex-wrap items-end gap-3">
                  <Field label="Origin">
                    <input value={editForm.origin} onChange={(e) => setEditForm({ ...editForm, origin: e.target.value })} className={`${inputClass} w-36`} />
                  </Field>
                  <Field label="Destination">
                    <input value={editForm.destination} onChange={(e) => setEditForm({ ...editForm, destination: e.target.value })} className={`${inputClass} w-36`} />
                  </Field>
                  <Field label="Distance (km)">
                    <input type="number" min="0" step="0.1" value={editForm.distanceKm} onChange={(e) => setEditForm({ ...editForm, distanceKm: e.target.value })} className={`${inputClass} w-28`} />
                  </Field>
                  <Field label="Origin terminal">
                    <input value={editForm.originTerminal} onChange={(e) => setEditForm({ ...editForm, originTerminal: e.target.value })} className={`${inputClass} w-48`} />
                  </Field>
                  <Field label="Destination terminal">
                    <input value={editForm.destinationTerminal} onChange={(e) => setEditForm({ ...editForm, destinationTerminal: e.target.value })} className={`${inputClass} w-48`} />
                  </Field>
                  <button type="button" onClick={() => saveEdit(route.id)} disabled={updateRoute.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
                    Save
                  </button>
                  <button type="button" onClick={() => setEditingId(null)} className="text-sm text-ink-muted hover:underline">
                    Cancel
                  </button>
                </div>
              ) : (
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <StatusPill status={route.active ? 'active' : 'inactive'} />
                    <span className="text-sm font-semibold text-ink">
                      {route.origin} <span className="text-ink-muted">&rarr;</span> {route.destination}
                    </span>
                    {route.distanceKm != null && <span className="text-sm text-ink-muted">{route.distanceKm} km</span>}
                  </div>
                  <div className="flex items-center gap-3">
                    <button type="button" onClick={() => startEdit(route)} className="text-sm text-brand hover:underline">
                      Edit
                    </button>
                    <button type="button" onClick={() => toggleActive(route)} className="text-sm text-ink-muted hover:underline">
                      {route.active ? 'Deactivate' : 'Reactivate'}
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
