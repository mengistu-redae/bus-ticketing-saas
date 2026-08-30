import { formatCurrency } from '../lib/format.js';
import { TIME_BUCKETS, SORT_OPTIONS, activeFilterCount } from '../lib/tripFilters.js';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

function Section({ title, children }) {
  return (
    <div className="border-t border-slate-200 pt-4 first:border-t-0 first:pt-0">
      {title && (
        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-muted">{title}</p>
      )}
      {children}
    </div>
  );
}

function Check({ label, count, checked, onChange }) {
  return (
    <label className="flex cursor-pointer items-center gap-2 py-1 text-sm text-ink">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="h-4 w-4 rounded border-slate-300 text-brand focus:ring-brand/30"
      />
      <span className="flex-1">{label}</span>
      {count != null && <span className="text-xs text-ink-muted">{count}</span>}
    </label>
  );
}

/**
 * Controlled filter controls for the trip-search results page. Holds no
 * state of its own - `filters` in, `onChange(next)` / `onClear()` out.
 * `sort` is here too (not a "filter" but it lives in the same panel and
 * stays in sync with the category tabs above the results).
 */
export default function TripFilterPanel({ facets, filters, sort, onChange, onSortChange, onClear }) {
  const activeCount = activeFilterCount(filters, facets);

  function toggleSetItem(key, value, on) {
    const next = new Set(filters[key]);
    if (on) next.add(value);
    else next.delete(value);
    onChange({ ...filters, [key]: next });
  }

  function setPrice(field, raw) {
    const value = raw === '' ? null : Number(raw);
    onChange({ ...filters, [field]: Number.isFinite(value) ? value : null });
  }

  return (
    <div className="flex flex-col gap-4 rounded-xl border border-slate-200 bg-surface p-4 shadow-sm">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-ink">Filters</h2>
        {activeCount > 0 && (
          <button type="button" onClick={onClear} className="text-xs font-medium text-brand hover:underline">
            Clear all
          </button>
        )}
      </div>

      <Section title="Sort by">
        <select value={sort} onChange={(e) => onSortChange(e.target.value)} className={inputClass}>
          {SORT_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </Section>

      {/* A one-click "show only what's bookable" toggle - the most common
          filter, so it sits at the top with Sort, not below the operator list. */}
      <Section>
        <Check
          label="Hide sold-out"
          count={facets.soldOutCount || null}
          checked={filters.hideSoldOut}
          onChange={(on) => onChange({ ...filters, hideSoldOut: on })}
        />
      </Section>

      <Section title="Departs">
        {TIME_BUCKETS.map((b) => (
          <Check
            key={b.key}
            label={
              <>
                {b.label} <span className="text-xs text-ink-muted">({b.hint})</span>
              </>
            }
            count={facets.bucketCounts[b.key] || 0}
            checked={filters.buckets.has(b.key)}
            onChange={(on) => toggleSetItem('buckets', b.key, on)}
          />
        ))}
      </Section>

      <Section title="Price (ETB)">
        <div className="flex items-center gap-2">
          <input
            type="number"
            inputMode="numeric"
            min={facets.priceMin}
            max={facets.priceMax}
            placeholder={String(facets.priceMin)}
            value={filters.minPrice ?? ''}
            onChange={(e) => setPrice('minPrice', e.target.value)}
            className={inputClass}
            aria-label="Minimum price"
          />
          <span className="text-ink-muted">–</span>
          <input
            type="number"
            inputMode="numeric"
            min={facets.priceMin}
            max={facets.priceMax}
            placeholder={String(facets.priceMax)}
            value={filters.maxPrice ?? ''}
            onChange={(e) => setPrice('maxPrice', e.target.value)}
            className={inputClass}
            aria-label="Maximum price"
          />
        </div>
        <p className="mt-1 text-xs text-ink-muted">
          {formatCurrency(facets.priceMin)} – {formatCurrency(facets.priceMax)} on this route
        </p>
      </Section>

      {/* Operator is last: it's the only variable-length section (0 rows for
          a single-operator agent, up to ~14 on the marketplace), so nothing
          below it gets pushed around and the searcher never scrolls past it
          to reach another control. */}
      {facets.operators.length > 1 && (
        <Section title="Operator">
          {facets.operators.map((op) => (
            <Check
              key={op.id}
              label={op.name}
              count={op.count}
              checked={filters.operatorIds.has(op.id)}
              onChange={(on) => toggleSetItem('operatorIds', op.id, on)}
            />
          ))}
        </Section>
      )}
    </div>
  );
}
