import { Link } from 'react-router-dom';
import { formatDateTime, formatCurrency } from '../lib/format.js';

function seatsBadge(availableSeats) {
  if (availableSeats <= 0) return { text: 'Sold out', className: 'bg-danger-light text-danger' };
  if (availableSeats <= 4) return { text: `${availableSeats} seats left`, className: 'bg-warning-light text-warning' };
  return { text: `${availableSeats} seats left`, className: 'bg-success-light text-success' };
}

export default function TripCard({ trip, to }) {
  const badge = seatsBadge(trip.availableSeats);

  return (
    <Link
      to={to}
      state={{ trip }}
      className="block rounded-xl border border-slate-200 bg-surface p-4 shadow-sm transition-shadow hover:shadow-md"
    >
      <div className="flex items-center gap-2">
        {trip.branding?.logoUrl && (
          <img src={trip.branding.logoUrl} alt="" className="h-4 w-auto max-w-[5rem] object-contain" />
        )}
        <p className="text-xs font-medium uppercase tracking-wide text-ink-muted">
          {trip.branding?.displayName || trip.operatorName}
        </p>
      </div>
      <div className="mt-1 flex items-baseline gap-2">
        <h3 className="text-xl font-semibold text-ink">{trip.origin}</h3>
        <span className="text-ink-muted">&rarr;</span>
        <h3 className="text-xl font-semibold text-ink">{trip.destination}</h3>
      </div>
      <p className="mt-1 text-sm text-ink-muted">
        Departs {formatDateTime(trip.departureAt)}
        {trip.arrivalAt && <> · Arrives {formatDateTime(trip.arrivalAt)}</>}
      </p>
      <div className="mt-4 flex items-center justify-between">
        <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${badge.className}`}>
          {badge.text}
        </span>
        <div className="flex items-center gap-3">
          <span className="font-mono text-lg font-semibold tabular-nums text-ink">{formatCurrency(trip.price)}</span>
          <span className="rounded-lg bg-accent px-3 py-1.5 text-sm font-semibold text-white">Select seats</span>
        </div>
      </div>
    </Link>
  );
}
