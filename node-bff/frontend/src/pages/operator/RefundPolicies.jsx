import { useState } from 'react';
import {
  useCreateRefundPolicy,
  useDeleteRefundPolicy,
  useFleetRoutes,
  useRefundPolicies,
  useUpdateRefundPolicy,
} from '../../api/queries.js';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/** rules comes back from the API as an escaped JSON string of snake_case tiers (see RefundPolicyController's javadoc) - parse to the camelCase shape this page edits with. */
function parseTiers(rulesJson) {
  try {
    return JSON.parse(rulesJson).map((t) => ({ cutoffHours: t.cutoff_hours, refundPercent: t.refund_percent }));
  } catch {
    return [];
  }
}

/** The reverse: RefundTier's @JsonProperty means the request body needs snake_case, not this page's camelCase editing state. */
function toApiTiers(tiers) {
  return tiers.map((t) => ({ cutoff_hours: Number(t.cutoffHours), refund_percent: Number(t.refundPercent) }));
}

const defaultTiers = [
  { cutoffHours: 24, refundPercent: 100 },
  { cutoffHours: 2, refundPercent: 50 },
  { cutoffHours: 0, refundPercent: 0 },
];

/**
 * operator_admin refund policy management: GET/POST/PATCH/DELETE
 * /api/fleet/refund-policies. DELETE here is a real delete (unlike buses/
 * routes/trips) - see RefundPolicyController's javadoc for why that's safe:
 * RefundCalculator already treats "no policy" as a well-defined 0% refund,
 * not an error.
 */
export default function OperatorRefundPolicies() {
  const { data: policies, isLoading, isError, error, refetch } = useRefundPolicies();
  const { data: routes } = useFleetRoutes();
  const createPolicy = useCreateRefundPolicy();
  const updatePolicy = useUpdateRefundPolicy();
  const deletePolicy = useDeleteRefundPolicy();

  const routeById = Object.fromEntries((routes || []).map((r) => [r.id, r]));

  const [routeId, setRouteId] = useState('');
  const [tiers, setTiers] = useState(defaultTiers);
  const [formError, setFormError] = useState(null);

  const [editingId, setEditingId] = useState(null);
  const [editTiers, setEditTiers] = useState([]);
  const [rowError, setRowError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (tiers.length === 0) {
      setFormError('Add at least one tier.');
      return;
    }
    try {
      await createPolicy.mutateAsync({ routeId: routeId || undefined, tiers: toApiTiers(tiers) });
      setRouteId('');
      setTiers(defaultTiers);
    } catch (err) {
      setFormError(err.message || 'Could not create policy - an operator-wide default or a policy for this route may already exist.');
    }
  }

  function startEdit(policy) {
    setRowError(null);
    setEditingId(policy.id);
    setEditTiers(parseTiers(policy.rules));
  }

  async function saveEdit(policyId) {
    setRowError(null);
    try {
      await updatePolicy.mutateAsync({ policyId, tiers: toApiTiers(editTiers) });
      setEditingId(null);
    } catch (err) {
      setRowError(err.message || 'Could not save changes.');
    }
  }

  async function handleDelete(policyId) {
    setRowError(null);
    try {
      await deletePolicy.mutateAsync(policyId);
    } catch (err) {
      setRowError(err.message || 'Could not delete this policy.');
    }
  }

  return (
    <div>
      <p className="mb-6 text-sm text-ink-muted">
        Tiers are matched by hours-before-departure, highest cutoff first. A route-specific policy overrides the
        operator-wide default for that route only. No policy configured means a 0% refund.
      </p>

      <form onSubmit={handleCreate} className="mb-6 rounded-xl border border-slate-200 bg-surface p-4">
        <div className="mb-4">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Applies to</span>
          <select value={routeId} onChange={(e) => setRouteId(e.target.value)} className={`${inputClass} w-64`}>
            <option value="">Operator-wide default</option>
            {(routes || []).map((r) => (
              <option key={r.id} value={r.id}>{r.origin} → {r.destination}</option>
            ))}
          </select>
        </div>
        <TiersEditor tiers={tiers} onChange={setTiers} />
        <button type="submit" disabled={createPolicy.isPending} className="mt-4 rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
          {createPolicy.isPending ? 'Creating…' : 'Create policy'}
        </button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
      {rowError && <div className="mb-4"><ErrorBanner message={rowError} /></div>}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && policies?.length === 0 && (
        <EmptyState title="No refund policies configured" description="Every cancellation refunds 0% until you add one above." />
      )}

      {!isLoading && !isError && policies?.length > 0 && (
        <div className="flex flex-col gap-3">
          {policies.map((policy) => {
            const route = policy.routeId ? routeById[policy.routeId] : null;
            return (
              <div key={policy.id} className="rounded-xl border border-slate-200 bg-surface p-4">
                <div className="mb-3 flex items-center justify-between">
                  <span className="text-sm font-semibold text-ink">
                    {policy.routeId ? (route ? `${route.origin} → ${route.destination}` : 'Route no longer active') : 'Operator-wide default'}
                  </span>
                  {editingId !== policy.id && (
                    <div className="flex items-center gap-3">
                      <button type="button" onClick={() => startEdit(policy)} className="text-sm text-brand hover:underline">
                        Edit
                      </button>
                      <button type="button" onClick={() => handleDelete(policy.id)} className="text-sm text-danger hover:underline">
                        Delete
                      </button>
                    </div>
                  )}
                </div>

                {editingId === policy.id ? (
                  <>
                    <TiersEditor tiers={editTiers} onChange={setEditTiers} />
                    <div className="mt-3 flex items-center gap-3">
                      <button type="button" onClick={() => saveEdit(policy.id)} disabled={updatePolicy.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
                        Save
                      </button>
                      <button type="button" onClick={() => setEditingId(null)} className="text-sm text-ink-muted hover:underline">
                        Cancel
                      </button>
                    </div>
                  </>
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {parseTiers(policy.rules)
                      .sort((a, b) => b.cutoffHours - a.cutoffHours)
                      .map((tier, i) => (
                        <span key={i} className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-ink">
                          {tier.cutoffHours}h+ &rarr; {tier.refundPercent}%
                        </span>
                      ))}
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

function TiersEditor({ tiers, onChange }) {
  function updateTier(index, field, value) {
    onChange(tiers.map((t, i) => (i === index ? { ...t, [field]: value } : t)));
  }
  function addTier() {
    onChange([...tiers, { cutoffHours: 0, refundPercent: 0 }]);
  }
  function removeTier(index) {
    onChange(tiers.filter((_, i) => i !== index));
  }

  return (
    <div className="flex flex-col gap-2">
      {tiers.map((tier, i) => (
        <div key={i} className="flex items-center gap-2">
          <input
            type="number"
            min="0"
            value={tier.cutoffHours}
            onChange={(e) => updateTier(i, 'cutoffHours', e.target.value)}
            className={`${inputClass} w-20`}
          />
          <span className="text-xs text-ink-muted">hrs before departure &rarr;</span>
          <input
            type="number"
            min="0"
            max="100"
            value={tier.refundPercent}
            onChange={(e) => updateTier(i, 'refundPercent', e.target.value)}
            className={`${inputClass} w-20`}
          />
          <span className="text-xs text-ink-muted">% refund</span>
          <button type="button" onClick={() => removeTier(i)} className="text-xs text-danger hover:underline">
            Remove
          </button>
        </div>
      ))}
      <button type="button" onClick={addTier} className="self-start text-xs font-semibold text-brand hover:underline">
        + Add tier
      </button>
    </div>
  );
}
