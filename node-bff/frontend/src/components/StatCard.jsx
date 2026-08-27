import TrendBadge from './TrendBadge.jsx';
import Sparkline from './Sparkline.jsx';

/**
 * One KPI tile on a dashboard: a big number with a label, an optional
 * period-over-period delta pill, an optional sparkline, and an optional hint
 * line. Matches the list-row card look (rounded-xl border bg-surface shadow-sm).
 * Pages lay several of these out in their own grid.
 */
export default function StatCard({ label, value, hint, mono = false, delta, spark }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-4 shadow-sm">
      <div className="flex items-start justify-between gap-2">
        <p className="text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</p>
        {delta && (
          <TrendBadge deltaPct={delta.deltaPct} isNew={delta.previous === 0 && delta.current > 0} invert={delta.invert} />
        )}
      </div>
      <div className="mt-1 flex items-end justify-between gap-2">
        <p className={`text-2xl font-bold tabular-nums text-ink ${mono ? 'font-mono' : ''}`}>{value}</p>
        {spark && spark.length > 1 && <Sparkline values={spark} />}
      </div>
      {hint && <p className="mt-1 text-xs text-ink-muted">{hint}</p>}
    </div>
  );
}
