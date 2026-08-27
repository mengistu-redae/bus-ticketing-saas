/**
 * Period-over-period change pill for a stat card. Green up / red down / grey
 * flat; "New" when there was nothing in the prior window (backend sends
 * deltaPct = 100 with previous = 0 for that case, so pass `isNew`).
 */
export default function TrendBadge({ deltaPct, isNew = false, invert = false }) {
  if (isNew) {
    return (
      <span className="inline-flex items-center rounded-full bg-brand-light px-1.5 py-0.5 text-[11px] font-semibold text-brand">
        New
      </span>
    );
  }
  if (deltaPct === null || deltaPct === undefined) return null;

  const flat = Math.abs(deltaPct) < 0.05;
  const up = deltaPct > 0;
  // "up" is good by default (more bookings/revenue); invert for metrics where
  // a rise is bad (e.g. cancellations).
  const good = flat ? null : invert ? !up : up;

  const cls = flat
    ? 'bg-slate-100 text-ink-muted'
    : good
      ? 'bg-success-light text-success'
      : 'bg-danger-light text-danger';
  const arrow = flat ? '±' : up ? '▲' : '▼';

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-full px-1.5 py-0.5 text-[11px] font-semibold tabular-nums ${cls}`}>
      {arrow} {Math.abs(deltaPct).toFixed(1)}%
    </span>
  );
}
