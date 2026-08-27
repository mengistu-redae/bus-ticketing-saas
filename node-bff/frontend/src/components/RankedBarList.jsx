import EmptyState from './EmptyState.jsx';

/**
 * A ranked list with a proportional fill bar per row - more readable than a
 * bar chart for a short (<=8) leaderboard. `items` is
 * `[{ label, value, sub?, bar? }]`; `format` renders the value; the fill
 * width comes from `bar` when present (use it when `value` is preformatted
 * text), else from `value`.
 */
export default function RankedBarList({
  items = [],
  format = (v) => v,
  emptyTitle = 'Nothing to show',
  color = 'bg-brand',
}) {
  if (items.length === 0) {
    return <EmptyState title={emptyTitle} />;
  }
  const barOf = (it) => (typeof it.bar === 'number' ? it.bar : Number(it.value) || 0);
  const max = Math.max(...items.map(barOf), 1);

  return (
    <div className="flex flex-col gap-2">
      {items.map((it, i) => (
        <div key={it.label + i} className="rounded-xl border border-slate-200 bg-surface p-3 shadow-sm">
          <div className="mb-1.5 flex items-baseline justify-between gap-3">
            <span className="truncate text-sm font-medium text-ink">{it.label}</span>
            <span className="shrink-0 font-mono text-sm tabular-nums text-ink">{format(it.value)}</span>
          </div>
          <div className="h-1.5 w-full overflow-hidden rounded-full bg-slate-100">
            <div
              className={`h-full rounded-full ${color}`}
              style={{ width: `${Math.max((barOf(it) / max) * 100, 2)}%` }}
            />
          </div>
          {it.sub && <p className="mt-1 text-xs text-ink-muted">{it.sub}</p>}
        </div>
      ))}
    </div>
  );
}
