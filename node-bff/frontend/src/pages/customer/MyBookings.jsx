import { Link } from 'react-router-dom';
import { useMyBookings } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

export default function MyBookings() {
  const { data, isLoading, isError, error, refetch } = useMyBookings();

  const bookings = [...(data || [])].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
  );

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-ink">My Bookings</h1>

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
          description="Search for a trip to get started."
          action={
            <Link to="/" className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark">
              Search trips
            </Link>
          }
        />
      )}

      {!isLoading && !isError && bookings.length > 0 && (
        <div className="flex flex-col gap-3">
          {bookings.map((booking) => (
            <Link
              key={booking.id}
              to={`/bookings/${booking.id}`}
              className="flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-4 shadow-sm transition-shadow hover:shadow-md"
            >
              <div>
                <div className="mb-1 flex items-center gap-2">
                  <StatusPill status={booking.status} />
                  <span className="text-xs text-ink-muted">{formatDateTime(booking.createdAt)}</span>
                </div>
                <p className="font-mono text-xs text-ink-muted">{booking.id}</p>
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
