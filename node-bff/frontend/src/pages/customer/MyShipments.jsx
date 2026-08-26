import { Link } from 'react-router-dom';
import { useMyShipments } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

/**
 * The cargo counterpart to MyBookings.jsx - GET /api/my-shipments, scoped
 * through waybills attached to a booking this customer owns (see
 * CargoWaybillRepository.findAllByBookingCustomerUserId). A standalone
 * staff-created waybill with no bookingId never shows up here for anyone -
 * waybills are still staff-issued in v1, this is only a read-only history
 * view, same as MyBookings has no create-booking form of its own.
 */
export default function MyShipments() {
  const { data, isLoading, isError, error, refetch } = useMyShipments();

  const waybills = [...(data || [])].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
  );

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-ink">My Shipments</h1>

      {isLoading && (
        <div className="flex flex-col gap-3">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      )}

      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}

      {!isLoading && !isError && waybills.length === 0 && (
        <EmptyState
          title="No shipments yet"
          description="Shipments attached to one of your bookings by an operator's agent will show up here."
        />
      )}

      {!isLoading && !isError && waybills.length > 0 && (
        <div className="flex flex-col gap-3">
          {waybills.map((wb) => (
            <Link
              key={wb.id}
              to={`/my-shipments/${wb.id}`}
              className="flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-4 shadow-sm transition-shadow hover:shadow-md"
            >
              <div>
                <div className="mb-1 flex items-center gap-2">
                  <StatusPill status={wb.status} />
                  <span className="font-mono text-xs text-ink-muted">{wb.waybillNumber}</span>
                </div>
                <p className="text-sm text-ink">{wb.description} · {wb.grossWeightKg} kg</p>
                <p className="text-xs text-ink-muted">{formatDateTime(wb.createdAt)}</p>
              </div>
              <div className="flex items-center gap-4">
                <span className="font-mono text-lg font-semibold tabular-nums text-ink">
                  {formatCurrency(wb.totalCargoCost)}
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
