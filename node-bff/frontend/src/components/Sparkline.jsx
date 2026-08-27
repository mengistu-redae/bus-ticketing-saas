import { BRAND } from '../lib/chartTheme.js';

/**
 * Tiny inline trend line for a stat card - hand-rolled SVG rather than a
 * Recharts <ResponsiveContainer> per card (that's heavy to mount 4-6 times).
 * No axes, no labels: it's a glanceable shape, the number beside it carries
 * the value.
 */
export default function Sparkline({ values = [], width = 96, height = 28, color = BRAND }) {
  if (!values || values.length < 2) return null;

  const max = Math.max(...values, 1);
  const min = Math.min(...values, 0);
  const span = max - min || 1;
  const stepX = width / (values.length - 1);
  const y = (v) => height - 2 - ((v - min) / span) * (height - 4);

  const line = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${(i * stepX).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  const area = `${line} L${width},${height} L0,${height} Z`;

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} className="overflow-visible" aria-hidden="true">
      <path d={area} fill={color} fillOpacity={0.1} />
      <path d={line} fill="none" stroke={color} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
