/**
 * Chart colours as literal hex strings - Recharts takes colour strings, not
 * Tailwind classes. The categorical slots are the data-viz skill's validated
 * default palette (light mode, white surface): worst adjacent CVD ΔE 9.1,
 * normal-vision ΔE 19.6 - assign in fixed order, never cycle past slot 5
 * (fold the rest into "Other"). Three slots sit below 3:1 contrast on white,
 * so every chart that uses them ships a legend or direct labels (the "relief
 * rule") - the donut and line components here always do.
 *
 * The app is light-only (no theme system - bg-slate-50 is hardcoded), so
 * there is no dark variant here by design.
 */

// Categorical - identity, fixed order.
export const SERIES = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4'];

// The app's own brand blue (tailwind `brand`) - used for the single-series
// trend lines/areas so they match the rest of the UI.
export const BRAND = '#1D4ED8';
export const SUCCESS = '#16A34A';
export const DANGER = '#DC2626';
export const NEUTRAL = '#94A3B8'; // slate-400, for "cancelled"/inactive slices

// Chart chrome (from the skill's light "chrome & ink" table, nudged to the
// app's slate palette).
export const AXIS = '#64748B'; // ink-muted
export const GRID = '#E2E8F0'; // slate-200

/** Shared Recharts <Tooltip> visual style. */
export const tooltipStyle = {
  contentStyle: {
    borderRadius: '0.5rem',
    border: '1px solid #E2E8F0',
    boxShadow: '0 4px 12px rgba(15, 23, 42, 0.08)',
    fontSize: '0.75rem',
    padding: '0.5rem 0.75rem',
  },
  labelStyle: { color: '#0F172A', fontWeight: 600, marginBottom: '0.25rem' },
  itemStyle: { color: '#334155', padding: 0 },
};

/**
 * Fixed colours for the categorical breakdowns so a slice keeps its colour
 * regardless of which keys are present (colour follows the entity, not its
 * rank). Unknown keys fall back to a rotating SERIES slot.
 */
export const CHANNEL_COLORS = {
  self_service: SERIES[0],
  counter: SERIES[1],
  guest: SERIES[2],
};

export const BOOKING_STATUS_COLORS = {
  confirmed: SUCCESS,
  cancelled: NEUTRAL,
};

export const CARGO_STATUS_COLORS = {
  requested: SERIES[4],
  issued: SERIES[0],
  dispatched: SERIES[3],
  arrived: SERIES[2],
  collected: SUCCESS,
  cancelled: NEUTRAL,
};

export const PAYMENT_METHOD_COLORS = {
  cash: SERIES[0],
  telebirr: SERIES[1],
  cbe_birr: SERIES[2],
  card: SERIES[3],
};

export function colorFor(map, key, i = 0) {
  return map[key] || SERIES[i % SERIES.length];
}
