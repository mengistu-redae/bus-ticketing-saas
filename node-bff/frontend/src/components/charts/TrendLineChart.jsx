import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { AXIS, GRID, tooltipStyle } from '../../lib/chartTheme.js';
import { formatCurrency } from '../../lib/format.js';

/**
 * One-or-two-series daily area chart over the dashboard's selected window.
 * `series` is `[{ key, label, values: number[], color }]` aligned to `days`
 * (both come gap-filled from the backend, see DashboardService). Single axis
 * only - two series here are always the same unit (counts).
 */
export default function TrendLineChart({ days = [], series = [], height = 200, money = false }) {
  const data = days.map((day, i) => {
    const row = { day };
    series.forEach((s) => {
      row[s.key] = s.values[i] ?? 0;
    });
    return row;
  });

  const fmtValue = money ? (v) => formatCurrency(v) : (v) => v;
  const fmtAxis = money
    ? (v) => (v >= 1000 ? `${Math.round(v / 1000)}k` : v)
    : (v) => v;
  const fmtDay = (d) => {
    const parts = String(d).split('-');
    return parts.length === 3 ? `${Number(parts[1])}/${Number(parts[2])}` : d;
  };

  return (
    <ResponsiveContainer width="100%" height={height}>
      <AreaChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
        <defs>
          {series.map((s) => (
            <linearGradient key={s.key} id={`grad-${s.key}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={s.color} stopOpacity={0.25} />
              <stop offset="100%" stopColor={s.color} stopOpacity={0.02} />
            </linearGradient>
          ))}
        </defs>
        <CartesianGrid stroke={GRID} strokeDasharray="2 4" vertical={false} />
        <XAxis
          dataKey="day"
          tickFormatter={fmtDay}
          tick={{ fill: AXIS, fontSize: 11 }}
          tickLine={false}
          axisLine={{ stroke: GRID }}
          minTickGap={24}
        />
        <YAxis
          tickFormatter={fmtAxis}
          tick={{ fill: AXIS, fontSize: 11 }}
          tickLine={false}
          axisLine={false}
          width={40}
          allowDecimals={false}
        />
        <Tooltip
          {...tooltipStyle}
          labelFormatter={fmtDay}
          formatter={(v, name) => [fmtValue(v), series.find((s) => s.key === name)?.label ?? name]}
        />
        {series.map((s) => (
          <Area
            key={s.key}
            type="monotone"
            dataKey={s.key}
            name={s.key}
            stroke={s.color}
            strokeWidth={2}
            fill={`url(#grad-${s.key})`}
            dot={false}
            activeDot={{ r: 4 }}
          />
        ))}
      </AreaChart>
    </ResponsiveContainer>
  );
}
