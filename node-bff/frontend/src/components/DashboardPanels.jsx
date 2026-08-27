import { Link } from 'react-router-dom';
import StatusPill from './StatusPill.jsx';
import EmptyState from './EmptyState.jsx';
import { formatCurrency, formatDateTime } from '../lib/format.js';

/**
 * Shared "recent bookings" / "upcoming departures" panels for the
 * operator/agent dashboards - identical shape on both (see
 * spring-boot-api's BookingSummary / DepartureSummary). A section wrapper
 * plus a rows list; kept here rather than duplicated per role page, same
 * reasoning as the other shared components (SeatMap, StatusPill, ...).
 */
export function DashboardSection({ title, children, cta }) {
  return (
    <section>
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-ink-muted">{title}</h2>
        {cta}
      </div>
      {children}
    </section>
  );
}

export function RecentBookingsPanel({ bookings = [], linkBase }) {
  if (bookings.length === 0) {
    return <EmptyState title="No bookings yet" description="New bookings will show up here." />;
  }
  return (
    <div className="flex flex-col gap-2">
      {bookings.map((b) => {
        const inner = (
          <>
            <div>
              <div className="mb-1 flex items-center gap-2">
                <StatusPill status={b.status} />
                <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium capitalize text-ink-muted">
                  {b.channel === 'counter' ? 'Counter' : b.channel === 'guest' ? 'Guest' : 'Online'}
                </span>
                <span className="text-xs text-ink-muted">{formatDateTime(b.createdAt)}</span>
              </div>
              <p className="font-mono text-xs text-ink-muted">
                {b.ticketNumber} · {b.bookingRef}
              </p>
            </div>
            <span className="font-mono text-sm font-semibold tabular-nums text-ink">
              {formatCurrency(b.totalAmount)}
            </span>
          </>
        );
        const className =
          'flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-3 shadow-sm';
        return linkBase ? (
          <Link key={b.id} to={`${linkBase}/${b.id}`} className={`${className} transition-shadow hover:shadow-md`}>
            {inner}
          </Link>
        ) : (
          <div key={b.id} className={className}>
            {inner}
          </div>
        );
      })}
    </div>
  );
}

export function DeparturesPanel({ departures = [] }) {
  if (departures.length === 0) {
    return <EmptyState title="No upcoming departures" description="Scheduled trips will appear here." />;
  }
  return (
    <div className="flex flex-col gap-2">
      {departures.map((d) => (
        <div
          key={d.tripId}
          className="flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-3 shadow-sm"
        >
          <div>
            <p className="text-sm font-medium text-ink">{d.routeName || 'Route'}</p>
            <p className="text-xs text-ink-muted">{formatDateTime(d.departureAt)}</p>
          </div>
          <span className="font-mono text-xs tabular-nums text-ink-muted">
            {d.seatsBooked}/{d.capacity} seats
          </span>
        </div>
      ))}
    </div>
  );
}
