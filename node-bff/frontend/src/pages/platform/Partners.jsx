import { useState } from 'react';
import {
  useCreatePartner,
  usePlatformOperators,
  usePlatformPartners,
  useRevokePartner,
} from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatDateTime } from '../../lib/format.js';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

// Must match com.bustix.partner.PartnerScopes.ALLOWED.
const ALL_SCOPES = [
  { id: 'trips:read', label: 'Read trips' },
  { id: 'bookings:read', label: 'Read bookings' },
  { id: 'bookings:write', label: 'Create / cancel bookings' },
  { id: 'waybills:read', label: 'Read waybills' },
  { id: 'waybills:write', label: 'Create / update waybills' },
  { id: 'webhooks:manage', label: 'Manage webhooks' },
];

const emptyForm = { name: '', operatorId: '', scopes: ['trips:read'], rateTier: 'default' };

/**
 * platform_admin management of third-party API integration credentials
 * (Partner API WS-1). Creating a partner provisions a real confidential
 * Keycloak client that authenticates with the OAuth2 client-credentials
 * grant and acts as a headless agent for one operator. The client secret is
 * returned exactly once - shown here and never again.
 */
export default function PlatformPartners() {
  const { data: partners, isLoading, isError, error, refetch } = usePlatformPartners();
  const { data: operators } = usePlatformOperators();
  const createPartner = useCreatePartner();
  const revokePartner = useRevokePartner();

  const [form, setForm] = useState(emptyForm);
  const [formError, setFormError] = useState(null);
  const [rowError, setRowError] = useState(null);
  const [newCredential, setNewCredential] = useState(null);

  const activeOperators = (operators || []).filter((o) => o.status === 'active');

  function toggleScope(scope) {
    setForm((f) => ({
      ...f,
      scopes: f.scopes.includes(scope) ? f.scopes.filter((s) => s !== scope) : [...f.scopes, scope],
    }));
  }

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    setNewCredential(null);
    if (!form.name.trim() || !form.operatorId) {
      setFormError('A name and an operator are both required.');
      return;
    }
    try {
      const created = await createPartner.mutateAsync({
        name: form.name.trim(),
        operatorId: form.operatorId,
        scopes: form.scopes,
        rateTier: form.rateTier,
      });
      setNewCredential(created);
      setForm(emptyForm);
    } catch (err) {
      setFormError(err.message || 'Could not create the partner. Keycloak may be unreachable.');
    }
  }

  async function handleRevoke(partnerId) {
    setRowError(null);
    try {
      await revokePartner.mutateAsync(partnerId);
    } catch (err) {
      setRowError(err.message || 'Could not revoke this partner.');
    }
  }

  const operatorName = (tenantId) => operators?.find((o) => o.id === tenantId)?.name || tenantId;

  return (
    <div>
      <h1 className="mb-1 text-2xl font-bold text-ink">API Partners</h1>
      <p className="mb-6 text-sm text-ink-muted">
        A partner integrates server-to-server with the versioned <code className="font-mono">/v1</code> API on behalf
        of one operator, using the OAuth2 client-credentials grant. Creating one provisions a real Keycloak client.
      </p>

      <form
        onSubmit={handleCreate}
        className="mb-6 flex flex-col gap-4 rounded-xl border border-slate-200 bg-surface p-4"
      >
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Name">
            <input
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="Acme Travel"
              className={`${inputClass} w-48`}
            />
          </Field>
          <Field label="Operator">
            <select
              value={form.operatorId}
              onChange={(e) => setForm({ ...form, operatorId: e.target.value })}
              className={`${inputClass} w-56`}
            >
              <option value="">Select operator…</option>
              {activeOperators.map((o) => (
                <option key={o.id} value={o.id}>
                  {o.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Rate tier">
            <select
              value={form.rateTier}
              onChange={(e) => setForm({ ...form, rateTier: e.target.value })}
              className={`${inputClass} w-36`}
            >
              <option value="default">default</option>
              <option value="trusted">trusted</option>
              <option value="internal">internal</option>
            </select>
          </Field>
          <button
            type="submit"
            disabled={createPartner.isPending}
            className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50"
          >
            {createPartner.isPending ? 'Provisioning…' : 'Create partner'}
          </button>
        </div>
        <div>
          <span className="mb-2 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Scopes</span>
          <div className="flex flex-wrap gap-x-5 gap-y-2">
            {ALL_SCOPES.map((s) => (
              <label key={s.id} className="flex items-center gap-2 text-sm text-ink">
                <input type="checkbox" checked={form.scopes.includes(s.id)} onChange={() => toggleScope(s.id)} />
                <span>
                  {s.label} <code className="font-mono text-xs text-ink-muted">{s.id}</code>
                </span>
              </label>
            ))}
          </div>
        </div>
      </form>

      {formError && (
        <div className="mb-4">
          <ErrorBanner message={formError} />
        </div>
      )}
      {rowError && (
        <div className="mb-4">
          <ErrorBanner message={rowError} />
        </div>
      )}

      {newCredential && (
        <div className="mb-6 rounded-xl border border-warning/40 bg-warning/10 p-4">
          <p className="mb-2 text-sm font-semibold text-ink">
            Partner “{newCredential.name}” created. Copy the secret now — it is not shown again.
          </p>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            <dt className="text-ink-muted">Client ID</dt>
            <dd className="break-all font-mono">{newCredential.keycloakClientId}</dd>
            <dt className="text-ink-muted">Client secret</dt>
            <dd className="break-all font-mono">{newCredential.clientSecret}</dd>
            <dt className="text-ink-muted">Token endpoint</dt>
            <dd className="break-all font-mono">
              http://localhost:8080/realms/bustix/protocol/openid-connect/token
            </dd>
            <dt className="text-ink-muted">Scopes</dt>
            <dd className="font-mono">{newCredential.scopes || '(none)'}</dd>
          </dl>
          <button
            type="button"
            onClick={() => setNewCredential(null)}
            className="mt-3 text-sm text-ink-muted hover:underline"
          >
            Dismiss
          </button>
        </div>
      )}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && partners?.length === 0 && (
        <EmptyState title="No API partners yet" description="Create your first integration credential above." />
      )}

      {!isLoading && !isError && partners?.length > 0 && (
        <div className="flex flex-col gap-2">
          {partners.map((partner) => (
            <div key={partner.id} className="rounded-xl border border-slate-200 bg-surface p-4">
              <div className="flex items-center justify-between">
                <div>
                  <div className="mb-1 flex items-center gap-2">
                    <StatusPill status={partner.status === 'active' ? 'active' : 'inactive'} />
                    <span className="text-sm font-semibold text-ink">{partner.name}</span>
                    <span className="font-mono text-xs text-ink-muted">{partner.keycloakClientId}</span>
                  </div>
                  <p className="text-xs text-ink-muted">
                    {operatorName(partner.tenantId)} · scopes: {partner.scopes || '(none)'} · tier {partner.rateTier} ·
                    created {formatDateTime(partner.createdAt)}
                    {partner.revokedAt ? ` · revoked ${formatDateTime(partner.revokedAt)}` : ''}
                  </p>
                </div>
                {partner.status === 'active' && (
                  <button
                    type="button"
                    onClick={() => handleRevoke(partner.id)}
                    className="text-sm text-danger hover:underline"
                  >
                    Revoke
                  </button>
                )}
              </div>
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
