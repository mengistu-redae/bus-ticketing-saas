const STYLES = {
  confirmed: 'bg-success-light text-success',
  cancelled: 'bg-slate-100 text-ink-muted',
  scheduled: 'bg-success-light text-success',
  boarding_closed: 'bg-slate-100 text-ink-muted',
  active: 'bg-success-light text-success',
  inactive: 'bg-slate-100 text-ink-muted',
  // Cargo waybill lifecycle (issued -> dispatched -> arrived -> collected).
  issued: 'bg-slate-100 text-ink-muted',
  dispatched: 'bg-warning-light text-warning',
  arrived: 'bg-brand-light text-brand',
  collected: 'bg-success-light text-success',
};

// Friendlier labels where the raw status value reads badly.
const LABELS = {
  boarding_closed: 'Departed',
};

export default function StatusPill({ status }) {
  const style = STYLES[status] || 'bg-slate-100 text-ink-muted';
  const label = LABELS[status] || String(status).replace(/_/g, ' ');
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold capitalize ${style}`}>
      {label}
    </span>
  );
}
