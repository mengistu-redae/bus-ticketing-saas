import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext.jsx';
import { useRequestShipment, useOperators } from '../../api/queries.js';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import WaybillItemsEditor from '../../components/WaybillItemsEditor.jsx';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const emptyItem = { description: '', quantity: '1', declaredValue: '', grossWeightKg: '' };

/**
 * Customer self-service shipment request - POST /api/my-shipments,
 * CargoWaybillService.requestShipment. No trip picker here (unlike the
 * staff create form in pages/cargo/Waybills.jsx) - a customer may not
 * know which bus they're taking yet; staff assigns the real trip and
 * prices the shipment at confirm-and-issue time, after physically
 * weighing it at the counter. consigneeIdNumber is optional here, unlike
 * the staff form, for the same reason.
 */
export default function RequestShipment() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const requestShipment = useRequestShipment();
  const { data: operators = [], isLoading: operatorsLoading } = useOperators();

  const [form, setForm] = useState({
    operatorId: '',
    consignorName: user?.name || user?.preferred_username || '',
    consignorPhone: '',
    consigneeName: '',
    consigneePhone: '',
    consigneeIdNumber: '',
    description: '',
    items: [emptyItem],
  });
  const [formError, setFormError] = useState(null);

  async function handleSubmit(event) {
    event.preventDefault();
    setFormError(null);
    const itemsValid = form.items.length > 0 && form.items.every((i) => i.description && i.grossWeightKg);
    if (!form.operatorId || !form.consignorName || !form.consignorPhone || !form.consigneeName || !form.consigneePhone || !itemsValid) {
      setFormError('An operator, your name/phone, the recipient\'s name/phone, and every item\'s description/estimated weight are all required.');
      return;
    }
    try {
      const created = await requestShipment.mutateAsync({
        operatorId: form.operatorId,
        consignorName: form.consignorName,
        consignorPhone: form.consignorPhone,
        consigneeName: form.consigneeName,
        consigneePhone: form.consigneePhone,
        consigneeIdNumber: form.consigneeIdNumber || undefined,
        description: form.description || undefined,
        items: form.items.map((i) => ({
          description: i.description,
          quantity: i.quantity ? Number(i.quantity) : undefined,
          declaredValue: i.declaredValue ? Number(i.declaredValue) : undefined,
          grossWeightKg: Number(i.grossWeightKg),
        })),
      });
      navigate(`/my-shipments/${created.waybill.id}`);
    } catch (err) {
      setFormError(err.message || 'Could not submit your shipment request. Please try again.');
    }
  }

  return (
    <div className="max-w-2xl">
      <h1 className="mb-2 text-2xl font-bold text-ink">Request a shipment</h1>
      <p className="mb-6 text-sm text-ink-muted">
        Tell us what you're sending - a staff member will weigh it, assign it to a bus, and confirm the final price
        when you drop it off at the counter.
      </p>

      <form onSubmit={handleSubmit} className="rounded-xl border border-slate-200 bg-surface p-4">
        <div className="mb-4">
          <Field label="Operator">
            <select
              value={form.operatorId}
              onChange={(e) => setForm({ ...form, operatorId: e.target.value })}
              className={`${inputClass} max-w-md`}
              disabled={operatorsLoading}
            >
              <option value="">{operatorsLoading ? 'Loading operators…' : 'Choose an operator…'}</option>
              {operators.map((o) => (
                <option key={o.id} value={o.id}>
                  {o.name}
                </option>
              ))}
            </select>
          </Field>
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <fieldset className="rounded-lg border border-slate-200 p-3">
            <legend className="px-1 text-xs font-semibold uppercase tracking-wide text-ink-muted">You (sender)</legend>
            <div className="flex flex-col gap-2">
              <Field label="Name">
                <input value={form.consignorName} onChange={(e) => setForm({ ...form, consignorName: e.target.value })} className={inputClass} />
              </Field>
              <Field label="Phone (+2519xxxxxxxx)">
                <input value={form.consignorPhone} onChange={(e) => setForm({ ...form, consignorPhone: e.target.value })} className={inputClass} />
              </Field>
            </div>
          </fieldset>
          <fieldset className="rounded-lg border border-slate-200 p-3">
            <legend className="px-1 text-xs font-semibold uppercase tracking-wide text-ink-muted">Recipient</legend>
            <div className="flex flex-col gap-2">
              <Field label="Name">
                <input value={form.consigneeName} onChange={(e) => setForm({ ...form, consigneeName: e.target.value })} className={inputClass} />
              </Field>
              <Field label="Phone (+2519xxxxxxxx)">
                <input value={form.consigneePhone} onChange={(e) => setForm({ ...form, consigneePhone: e.target.value })} className={inputClass} />
              </Field>
              <Field label="ID number (optional - can be given at drop-off)">
                <input value={form.consigneeIdNumber} onChange={(e) => setForm({ ...form, consigneeIdNumber: e.target.value })} className={inputClass} />
              </Field>
            </div>
          </fieldset>
        </div>

        <div className="mt-4">
          <Field label="Shipment summary (optional)">
            <input
              value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
              className={`${inputClass} w-full max-w-md`}
              placeholder="e.g. 2 suitcases and a box of books"
            />
          </Field>
        </div>

        <div className="mt-4">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">
            Items (estimated - staff will re-weigh at drop-off)
          </span>
          <WaybillItemsEditor items={form.items} onChange={(items) => setForm({ ...form, items })} />
        </div>

        <div className="mt-4">
          <button
            type="submit"
            disabled={requestShipment.isPending}
            className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50"
          >
            {requestShipment.isPending ? 'Submitting…' : 'Submit request'}
          </button>
        </div>
      </form>
      {formError && <div className="mt-4"><ErrorBanner message={formError} /></div>}
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
