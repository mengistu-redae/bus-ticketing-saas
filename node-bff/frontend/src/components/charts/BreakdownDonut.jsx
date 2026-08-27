import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts';
import { tooltipStyle } from '../../lib/chartTheme.js';
import { formatCurrency } from '../../lib/format.js';

const PRETTY = {
  self_service: 'Self-service',
  counter: 'Counter',
  guest: 'Guest',
  cbe_birr: 'CBE Birr',
};

function label(key) {
  return PRETTY[key] || key.charAt(0).toUpperCase() + key.slice(1).replace(/_/g, ' ');
}

/**
 * A categorical breakdown as a donut + legend. `data` is
 * `[{ key, value, color }]`; `metric` picks whether value/tooltip read as a
 * count or money. Legend is always rendered (identity is never colour-alone -
 * the "relief rule", since some slot colours are sub-3:1 on white).
 */
export default function BreakdownDonut({ title, data = [], metric = 'count' }) {
  const rows = data.filter((d) => d.value > 0);
  const total = rows.reduce((sum, d) => sum + d.value, 0);
  const fmt = metric === 'money' ? (v) => formatCurrency(v) : (v) => String(v);

  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-4 shadow-sm">
      <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-muted">{title}</p>
      {rows.length === 0 ? (
        <p className="py-8 text-center text-sm text-ink-muted">No data for this period</p>
      ) : (
        <div className="flex items-center gap-4">
          <div className="relative h-28 w-28 shrink-0">
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie
                  data={rows}
                  dataKey="value"
                  nameKey="key"
                  innerRadius="62%"
                  outerRadius="100%"
                  paddingAngle={2}
                  stroke="none"
                >
                  {rows.map((d) => (
                    <Cell key={d.key} fill={d.color} />
                  ))}
                </Pie>
                <Tooltip
                  {...tooltipStyle}
                  formatter={(v, key) => [fmt(v), label(key)]}
                />
              </PieChart>
            </ResponsiveContainer>
            <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
              <span className="font-mono text-sm font-bold tabular-nums text-ink">
                {metric === 'money' ? formatCurrency(total) : total}
              </span>
            </div>
          </div>
          <ul className="flex-1 space-y-1 text-xs">
            {rows.map((d) => (
              <li key={d.key} className="flex items-center justify-between gap-2">
                <span className="flex items-center gap-1.5 text-ink-muted">
                  <span className="h-2 w-2 rounded-full" style={{ backgroundColor: d.color }} />
                  {label(d.key)}
                </span>
                <span className="font-mono tabular-nums text-ink">{fmt(d.value)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
