const dateTimeFormatter = new Intl.DateTimeFormat(undefined, {
  weekday: 'short',
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
});

const timeFormatter = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' });

const dayLabelFormatter = new Intl.DateTimeFormat(undefined, { weekday: 'short', month: 'short', day: 'numeric' });

export function formatDateTime(iso) {
  if (!iso) return '—';
  return dateTimeFormatter.format(new Date(iso));
}

export function formatTime(iso) {
  if (!iso) return '—';
  return timeFormatter.format(new Date(iso));
}

export function formatCurrency(amount) {
  if (amount === null || amount === undefined) return '—';
  return new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount);
}

/** A Date -> "YYYY-MM-DD" in the viewer's local timezone, for <input type="date">. */
export function toDateInputValue(date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/**
 * ISO instant for local midnight of the given Date's day - matches how the
 * search form builds the `departureAfter` param (`new Date(\`${d}T00:00:00\`)`).
 */
export function startOfLocalDayIso(date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).toISOString();
}

/** "Tue, Sep 2" - short day label for the quick-date buttons. */
export function formatDayLabel(date) {
  return dayLabelFormatter.format(date);
}

/** "6h 30m" / "45m" from a millisecond span; '—' when not finite. */
export function formatDuration(ms) {
  if (!Number.isFinite(ms) || ms <= 0) return '—';
  const totalMinutes = Math.round(ms / 60000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) return `${minutes}m`;
  if (minutes === 0) return `${hours}h`;
  return `${hours}h ${minutes}m`;
}
