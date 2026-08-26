const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * Add/remove-row editor for a shipment's line items (CargoWaybillItem on
 * the backend) - shared by the create form (Waybills.jsx) and the
 * pre-dispatch edit form (WaybillDetail.jsx), and reused as-is by the
 * customer shipment-request form, since all three need the identical
 * "at least one item" add/remove UI rather than three separate
 * implementations of it.
 *
 * `items` is a plain array of {description, quantity, declaredValue,
 * grossWeightKg} string-valued form rows (not yet coerced to numbers -
 * the caller does that at submit time, same as every other numeric field
 * in this app's forms).
 */
export default function WaybillItemsEditor({ items, onChange }) {
  function updateItem(index, field, value) {
    const next = items.slice();
    next[index] = { ...next[index], [field]: value };
    onChange(next);
  }

  function addItem() {
    onChange([...items, { description: '', quantity: '1', declaredValue: '', grossWeightKg: '' }]);
  }

  function removeItem(index) {
    onChange(items.filter((_, i) => i !== index));
  }

  return (
    <div className="flex flex-col gap-3">
      {items.map((item, index) => (
        <div key={index} className="flex flex-wrap items-end gap-3 rounded-lg border border-slate-200 p-3">
          <Field label="Description">
            <input
              value={item.description}
              onChange={(e) => updateItem(index, 'description', e.target.value)}
              className={`${inputClass} w-56`}
            />
          </Field>
          <Field label="Quantity">
            <input
              type="number"
              min="1"
              value={item.quantity}
              onChange={(e) => updateItem(index, 'quantity', e.target.value)}
              className={`${inputClass} w-20`}
            />
          </Field>
          <Field label="Declared value (optional)">
            <input
              type="number"
              min="0"
              step="0.01"
              value={item.declaredValue}
              onChange={(e) => updateItem(index, 'declaredValue', e.target.value)}
              className={`${inputClass} w-32`}
            />
          </Field>
          <Field label="Gross weight (kg)">
            <input
              type="number"
              min="0.01"
              step="0.01"
              value={item.grossWeightKg}
              onChange={(e) => updateItem(index, 'grossWeightKg', e.target.value)}
              className={`${inputClass} w-28`}
            />
          </Field>
          <button
            type="button"
            onClick={() => removeItem(index)}
            disabled={items.length <= 1}
            className="rounded-lg border border-danger/40 px-3 py-2 text-sm font-medium text-danger hover:bg-danger-light disabled:cursor-not-allowed disabled:opacity-40"
          >
            Remove
          </button>
        </div>
      ))}
      <button
        type="button"
        onClick={addItem}
        className="self-start rounded-lg border border-brand/40 px-3 py-1.5 text-sm font-medium text-brand hover:bg-brand-light/40"
      >
        + Add item
      </button>
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
