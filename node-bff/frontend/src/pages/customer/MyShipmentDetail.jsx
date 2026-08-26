import { useParams } from 'react-router-dom';
import { useMyShipment, useTrip } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

/**
 * Read-only counterpart to pages/cargo/WaybillDetail.jsx - a customer can
 * see a shipment attached to their own booking, but every lifecycle action
 * (dispatch/arrive/collect/cancel/edit/payment status) stays staff-only, so
 * none of that is offered here at all. Route/departure resolved via GET
 * /api/trips/{tripId} (public/customer-accessible) rather than the
 * tenant-scoped fleet trips/routes lists WaybillDetail.jsx uses, since a
 * customer JWT has no access to those.
 */
export default function MyShipmentDetail() {
  const { waybillId } = useParams();
  const waybillQuery = useMyShipment(waybillId);
  const waybill = waybillQuery.data?.waybill;
  const items = waybillQuery.data?.items || [];
  const tripQuery = useTrip(waybill?.tripId);
  const trip = tripQuery.data;

  if (waybillQuery.isLoading) {
    return <Skeleton className="h-48 w-full max-w-xl" />;
  }
  if (waybillQuery.isError) {
    return <ErrorBanner message={waybillQuery.error?.message} onRetry={waybillQuery.refetch} />;
  }
  if (!waybill) {
    return <ErrorBanner message="Shipment not found." />;
  }

  return (
    <div className="max-w-xl">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-ink">Shipment</h1>
          <p className="font-mono text-xs text-ink-muted">{waybill.waybillNumber}</p>
        </div>
        <StatusPill status={waybill.status} />
      </div>

      <div className="rounded-xl border border-slate-200 bg-surface p-5">
        {trip ? (
          <>
            <p className="text-lg font-semibold text-ink">
              {trip.origin} <span className="text-ink-muted">&rarr;</span> {trip.destination}
            </p>
            <p className="text-sm text-ink-muted">Departs {formatDateTime(trip.departureAt)}</p>
          </>
        ) : (
          <p className="text-sm italic text-ink-muted">Trip details unavailable.</p>
        )}

        <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-slate-100 pt-4 text-sm">
          <div>
            <dt className="text-ink-muted">Consignor</dt>
            <dd className="text-ink">{waybill.consignorName} · {waybill.consignorPhone}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Consignee</dt>
            <dd className="text-ink">{waybill.consigneeName} · {waybill.consigneePhone}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Weight</dt>
            <dd className="text-ink">{waybill.grossWeightKg} kg</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Total</dt>
            <dd className="font-mono font-semibold text-ink">{formatCurrency(waybill.totalCargoCost)}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Payment</dt>
            <dd className="capitalize text-ink">{waybill.paymentStatus?.replace('_', ' ')}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Issued</dt>
            <dd className="text-ink">{formatDateTime(waybill.createdAt)}</dd>
          </div>
          {waybill.dispatchedAt && (
            <div>
              <dt className="text-ink-muted">Dispatched</dt>
              <dd className="text-ink">{formatDateTime(waybill.dispatchedAt)}</dd>
            </div>
          )}
          {waybill.arrivedAt && (
            <div>
              <dt className="text-ink-muted">Arrived</dt>
              <dd className="text-ink">{formatDateTime(waybill.arrivedAt)}</dd>
            </div>
          )}
          {waybill.collectedAt && (
            <div>
              <dt className="text-ink-muted">Collected</dt>
              <dd className="text-ink">{formatDateTime(waybill.collectedAt)}</dd>
            </div>
          )}
        </dl>

        {waybill.description && (
          <p className="mt-3 text-sm text-ink-muted">{waybill.description}</p>
        )}

        <div className="mt-3 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-ink-muted">
                <th className="pb-1 pr-3 font-semibold">Item</th>
                <th className="pb-1 pr-3 font-semibold">Qty</th>
                <th className="pb-1 font-semibold">Weight</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id} className="border-t border-slate-100">
                  <td className="py-1 pr-3 text-ink">{item.description}</td>
                  <td className="py-1 pr-3 text-ink">{item.quantity}</td>
                  <td className="py-1 text-ink">{item.grossWeightKg} kg</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
