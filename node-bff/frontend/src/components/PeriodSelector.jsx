const OPTIONS = [
  { value: '7d', label: '7 days' },
  { value: '30d', label: '30 days' },
  { value: '90d', label: '90 days' },
];

const STORAGE_KEY = 'bustix.dashboard.period';

/** Last-used dashboard window, remembered per browser. Falls back to 30d. */
export function loadPeriod() {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return OPTIONS.some((o) => o.value === v) ? v : '30d';
  } catch {
    return '30d';
  }
}

/** Segmented control for the analytics window. */
export default function PeriodSelector({ value, onChange }) {
  function pick(next) {
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      /* private window - selection just won't persist */
    }
    onChange(next);
  }

  return (
    <div className="inline-flex rounded-lg border border-slate-200 bg-surface p-0.5 text-sm">
      {OPTIONS.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => pick(o.value)}
          className={`rounded-md px-3 py-1 font-medium transition-colors ${
            value === o.value ? 'bg-brand text-white' : 'text-ink-muted hover:bg-slate-100'
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}
