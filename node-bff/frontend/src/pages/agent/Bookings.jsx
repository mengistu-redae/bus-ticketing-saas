import { Link } from 'react-router-dom';
import { useAgentBookings } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

/**
 * Tenant-scoped booking list for staff (GET /api/bookings, AGENT/
 * OPERATOR_ADMIN) - added 2026-08-24 alongside the endpoint itself, which
 * previously didn't exist at all (an agent had no way to look up their own
 * operator's bookings through the API before this).
 */
export default function AgentBookings() {
  const { data, isLoading, isError, error, refetch } = useAgentBookings();

  const bookings = [...(data || [])].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
  );

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-ink">Bookings</h1>
        <Link to="/agent" className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark">
          New booking
        </Link>
      </div>

      {isLoading && (
        <div className="flex flex-col gap-3">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      )}

      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}

      {!isLoading && !isError && bookings.length === 0 && (
        <EmptyState
          title="No bookings yet"
          description="Bookings your operator's customers make online, and ones you take at the counter, both show up here."
          action={
            <Link to="/agent" className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark">
              Book for a walk-in
            </Link>
          }
        />
      )}

      {!isLoading && !isError && bookings.length > 0 && (
        <div className="flex flex-col gap-3">
          {bookings.map((booking) => (
            <Link
              key={booking.id}
              to={`/agent/bookings/${booking.id}`}
              className="flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-4 shadow-sm transition-shadow hover:shadow-md"
            >
              <div>
                <div className="mb-1 flex items-center gap-2">
                  <StatusPill status={booking.status} />
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium capitalize text-ink-muted">
                    {booking.channel === 'counter' ? 'Counter' : 'Online'}
                  </span>
                  <span className="text-xs text-ink-muted">{formatDateTime(booking.createdAt)}</span>
                </div>
                <p className="font-mono text-xs text-ink-muted">
                  {booking.ticketNumber} · {booking.bookingRef}
                </p>
              </div>
              <div className="flex items-center gap-4">
                <span className="font-mono text-lg font-semibold tabular-nums text-ink">
                  {formatCurrency(booking.totalAmount)}
                </span>
                <span className="text-ink-muted">&rsaquo;</span>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
