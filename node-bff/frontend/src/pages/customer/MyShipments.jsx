import { Link } from 'react-router-dom';
import { useMyShipments } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

/**
 * The cargo counterpart to MyBookings.jsx - GET /api/my-shipments, scoped
 * through two combined ownership paths (see
 * CargoWaybillRepository.findAllOwnedByCustomer): waybills attached to a
 * booking this customer owns, and (since 2026-08-26) waybills the customer
 * requested directly via "Request a shipment" below - those start
 * "requested" and stay that way until a staff member confirms and prices
 * them at the counter.
 */
export default function MyShipments() {
  const { data, isLoading, isError, error, refetch } = useMyShipments();

  const shipments = [...(data || [])].sort(
    (a, b) => new Date(b.waybill.createdAt).getTime() - new Date(a.waybill.createdAt).getTime(),
  );

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-ink">My Shipments</h1>
        <Link
          to="/my-shipments/request"
          className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark"
        >
          Request a shipment
        </Link>
      </div>

      {isLoading && (
        <div className="flex flex-col gap-3">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      )}

      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}

      {!isLoading && !isError && shipments.length === 0 && (
        <EmptyState
          title="No shipments yet"
          description="Request a shipment above, or one attached to your booking by an operator's agent will show up here."
        />
      )}

      {!isLoading && !isError && shipments.length > 0 && (
        <div className="flex flex-col gap-3">
          {shipments.map(({ waybill: wb, items }) => {
            const summary = wb.description || `${items.length} item${items.length === 1 ? '' : 's'}`;
            return (
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
                  <p className="text-sm text-ink">{summary} · {wb.grossWeightKg} kg</p>
                  <p className="text-xs text-ink-muted">{formatDateTime(wb.createdAt)}</p>
                </div>
                <div className="flex items-center gap-4">
                  <span className="font-mono text-lg font-semibold tabular-nums text-ink">
                    {formatCurrency(wb.totalCargoCost)}
                  </span>
                  <span className="text-ink-muted">&rsaquo;</span>
                </div>
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}
