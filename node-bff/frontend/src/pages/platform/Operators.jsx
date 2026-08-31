import { useState } from 'react';
import { useCreateOperator, useDeactivateOperator, usePlatformOperators, useUpdateOperator } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatDateTime } from '../../lib/format.js';

const inputClass =
  // No `w-full`: every field sets its own width (w-48/w-40/w-32), and Tailwind
  // emits `.w-full` after the numbered width utilities, so `w-full w-32`
  // would render full-width and defeat the fixed size.
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const emptyForm = { name: '', orgAlias: '', domain: '', tin: '' };

/**
 * platform_admin operator onboarding: GET/POST/PATCH/DELETE
 * /api/platform/operators. POST provisions a real Keycloak Organization
 * before inserting the local row (OperatorProvisioningService) - this is
 * the only page in the app whose "create" call reaches out to Keycloak,
 * not just Postgres, so it's slower and has more ways to fail (a taken
 * orgAlias, Keycloak unreachable) than every other create form here.
 * `orgAlias`/`keycloakOrgId` is fixed at creation - not editable via PATCH
 * (see UpdateOperatorRequest's javadoc: it's what TenantContextFilter
 * matches a staff token's organization claim against, so changing it would
 * silently break tenant resolution for every existing staff login at that
 * operator). DELETE soft-deactivates with no reactivate endpoint - see
 * PlatformController.deactivate's javadoc - so an inactive operator has no
 * action available here beyond Edit; reactivating needs direct DB access.
 */
export default function PlatformOperators() {
  const { data: operators, isLoading, isError, error, refetch } = usePlatformOperators();
  const createOperator = useCreateOperator();
  const updateOperator = useUpdateOperator();
  const deactivateOperator = useDeactivateOperator();

  const [form, setForm] = useState(emptyForm);
  const [formError, setFormError] = useState(null);
  const [editingId, setEditingId] = useState(null);
  const [editForm, setEditForm] = useState({});
  const [rowError, setRowError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.name.trim() || !form.orgAlias.trim() || !form.domain.trim()) {
      setFormError('Name, org alias, and domain are all required.');
      return;
    }
    try {
      await createOperator.mutateAsync({
        name: form.name.trim(),
        orgAlias: form.orgAlias.trim(),
        domain: form.domain.trim(),
        tin: form.tin.trim() || undefined,
      });
      setForm(emptyForm);
    } catch (err) {
      setFormError(err.message || 'Could not create operator - the org alias may already be taken.');
    }
  }

  function startEdit(operator) {
    setRowError(null);
    setEditingId(operator.id);
    setEditForm({ name: operator.name, tin: operator.tin || '' });
  }

  async function saveEdit(operatorId) {
    setRowError(null);
    try {
      await updateOperator.mutateAsync({ operatorId, name: editForm.name.trim(), tin: editForm.tin.trim() });
      setEditingId(null);
    } catch (err) {
      setRowError(err.message || 'Could not save changes.');
    }
  }

  async function handleDeactivate(operatorId) {
    setRowError(null);
    try {
      await deactivateOperator.mutateAsync(operatorId);
    } catch (err) {
      setRowError(err.message || 'Could not deactivate this operator.');
    }
  }

  return (
    <div>
      <h1 className="mb-1 text-2xl font-bold text-ink">Operators</h1>
      <p className="mb-6 text-sm text-ink-muted">
        Onboarding a new operator creates a real Keycloak Organization for their staff, then this platform's own
        record of them.
      </p>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label="Name">
          <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Golden Bus" className={`${inputClass} w-48`} />
        </Field>
        <Field label="Org alias">
          <input value={form.orgAlias} onChange={(e) => setForm({ ...form, orgAlias: e.target.value })} placeholder="golden-bus" className={`${inputClass} w-40`} />
        </Field>
        <Field label="Domain">
          <input value={form.domain} onChange={(e) => setForm({ ...form, domain: e.target.value })} placeholder="goldenbus.example" className={`${inputClass} w-48`} />
        </Field>
        <Field label="TIN (optional)">
          <input value={form.tin} onChange={(e) => setForm({ ...form, tin: e.target.value })} className={`${inputClass} w-32`} />
        </Field>
        <button type="submit" disabled={createOperator.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
          {createOperator.isPending ? 'Provisioning…' : 'Add operator'}
        </button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
      {rowError && <div className="mb-4"><ErrorBanner message={rowError} /></div>}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && operators?.length === 0 && (
        <EmptyState title="No operators yet" description="Onboard your first bus operator above." />
      )}

      {!isLoading && !isError && operators?.length > 0 && (
        <div className="flex flex-col gap-2">
          {operators.map((operator) => (
            <div key={operator.id} className="rounded-xl border border-slate-200 bg-surface p-4">
              {editingId === operator.id ? (
                <div className="flex flex-wrap items-end gap-3">
                  <Field label="Name">
                    <input value={editForm.name} onChange={(e) => setEditForm({ ...editForm, name: e.target.value })} className={`${inputClass} w-48`} />
                  </Field>
                  <Field label="TIN">
                    <input value={editForm.tin} onChange={(e) => setEditForm({ ...editForm, tin: e.target.value })} className={`${inputClass} w-32`} />
                  </Field>
                  <button type="button" onClick={() => saveEdit(operator.id)} disabled={updateOperator.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
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
                      <StatusPill status={operator.status === 'active' ? 'active' : 'inactive'} />
                      <span className="text-sm font-semibold text-ink">{operator.name}</span>
                      <span className="font-mono text-xs text-ink-muted">{operator.keycloakOrgId}</span>
                    </div>
                    <p className="text-xs text-ink-muted">
                      {operator.tin ? `TIN ${operator.tin} · ` : ''}Onboarded {formatDateTime(operator.createdAt)}
                    </p>
                  </div>
                  <div className="flex items-center gap-3">
                    <button type="button" onClick={() => startEdit(operator)} className="text-sm text-brand hover:underline">
                      Edit
                    </button>
                    {operator.status === 'active' && (
                      <button type="button" onClick={() => handleDeactivate(operator.id)} className="text-sm text-danger hover:underline">
                        Deactivate
                      </button>
                    )}
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
