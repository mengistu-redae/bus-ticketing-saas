import { useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import {
  useAgentBooking,
  useAgentBookingSeats,
  useCancelBooking,
  useCheckIn,
  useCreatePayment,
  usePayments,
  useTrip,
} from '../../api/queries.js';
import { ApiError } from '../../api/client.js';
import StatusPill from '../../components/StatusPill.jsx';
import BoardingPassQr from '../../components/BoardingPassQr.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

/**
 * Staff view of one booking (GET /api/bookings/{id}(/seats), tenant-scoped)
 * - the counter/agent counterpart to the customer's own BookingDetail,
 * plus two things a customer's own view has no business doing: cancelling
 * on the operator's behalf (POST /api/bookings/{id}/cancel, not the
 * customer's ownership-scoped .../my-bookings/... path) and recording a
 * payment against the booking (POST /api/bookings/{id}/payments -
 * PaymentController, cash today, gateway later - see CLAUDE.md's "Refund &
 * cancellation" section: recording one is a deliberate separate action,
 * not automatic on booking creation).
 */
export default function AgentBookingDetail() {
  const { bookingId } = useParams();
  const location = useLocation();

  const stateBooking = location.state?.booking;
  const bookingQuery = useAgentBooking(bookingId);
  const seatsQuery = useAgentBookingSeats(bookingId);
  const booking = bookingQuery.data || stateBooking;
  const tripQuery = useTrip(booking?.tripId);
  const trip = tripQuery.data;
  const seats = seatsQuery.data;

  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [cancelError, setCancelError] = useState(null);
  const cancelBooking = useCancelBooking(bookingId);

  const paymentsQuery = usePayments(bookingId);
  const createPayment = useCreatePayment(bookingId);
  const [paymentAmount, setPaymentAmount] = useState('');
  const [paymentMethod, setPaymentMethod] = useState('cash');
  const [paymentTxnId, setPaymentTxnId] = useState('');
  const [paymentError, setPaymentError] = useState(null);

  const checkIn = useCheckIn(bookingId);
  const [presentedId, setPresentedId] = useState({});
  const [checkInError, setCheckInError] = useState(null);

  async function handleCheckIn(seatId) {
    setCheckInError(null);
    try {
      await checkIn.mutateAsync({ seatId, presentedIdNumber: (presentedId[seatId] || '').trim() });
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setCheckInError(err.message || "Presented ID doesn't match the ID on file, or boarding has closed for this trip.");
      } else {
        setCheckInError(err.message || 'Could not check in this passenger.');
      }
    }
  }

  async function handleCancel() {
    setCancelError(null);
    try {
      await cancelBooking.mutateAsync();
      setConfirmingCancel(false);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setCancelError('This booking was already cancelled.');
      } else {
        setCancelError(err.message || 'Could not cancel this booking. Please try again.');
      }
      setConfirmingCancel(false);
    }
  }

  async function handleRecordPayment(event) {
    event.preventDefault();
    setPaymentError(null);
    const amount = Number(paymentAmount);
    if (!paymentAmount || Number.isNaN(amount) || amount < 0) {
      setPaymentError('Enter a valid amount.');
      return;
    }
    try {
      await createPayment.mutateAsync({ method: paymentMethod, amount, transactionId: paymentTxnId.trim() || undefined });
      setPaymentAmount('');
      setPaymentTxnId('');
    } catch (err) {
      setPaymentError(err.message || 'Could not record this payment. Please try again.');
    }
  }

  if (bookingQuery.isLoading && !stateBooking) {
    return <Skeleton className="h-48 w-full max-w-xl" />;
  }
  if (bookingQuery.isError && !stateBooking) {
    return <ErrorBanner message={bookingQuery.error?.message} onRetry={bookingQuery.refetch} />;
  }
  if (!booking) {
    return <ErrorBanner message="Booking not found." />;
  }

  const status = cancelBooking.data?.status || booking.status;
  const refundAmount = cancelBooking.data?.refundAmount;
  const payments = paymentsQuery.data || [];
  const collected = payments.reduce((sum, p) => sum + Number(p.amount), 0);
  const balanceDue = Number(booking.totalAmount) - collected;

  return (
    <div className="max-w-xl">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-ink">Booking</h1>
        <StatusPill status={status} />
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
            <dt className="text-ink-muted">Ticket #</dt>
            <dd className="font-mono text-xs text-ink">{booking.ticketNumber}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Booking ref (PNR)</dt>
            <dd className="font-mono text-xs text-ink">{booking.bookingRef}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Channel</dt>
            <dd className="text-ink capitalize">{booking.channel === 'counter' ? 'Counter' : 'Online'}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Booked</dt>
            <dd className="text-ink">{formatDateTime(booking.createdAt)}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Fare + VAT</dt>
            <dd className="font-mono text-ink">
              {formatCurrency(booking.subtotalAmount)} + {formatCurrency(booking.taxAmount)}
            </dd>
          </div>
          <div>
            <dt className="text-ink-muted">Total</dt>
            <dd className="font-mono font-semibold text-ink">{formatCurrency(booking.totalAmount)}</dd>
          </div>
        </dl>

        {seats && seats.length > 0 && (
          <div className="mt-4 border-t border-slate-100 pt-4 text-sm">
            <dt className="mb-2 text-ink-muted">Passengers</dt>
            <div className="flex flex-col gap-3">
              {seats.map((s) => (
                <div key={s.seatId}>
                  <dd className="text-ink">
                    <span className="font-mono">{s.seatNo}</span> — {s.passengerName || 'Unnamed'}
                    {s.passengerAge != null && <span className="text-ink-muted"> · age {s.passengerAge}</span>}
                    {s.passengerPhone && <span className="text-ink-muted"> · {s.passengerPhone}</span>}
                    {s.passengerIdNumber && (
                      <span className="text-ink-muted">
                        {' '}
                        · {s.passengerIdType ? s.passengerIdType.replace('_', ' ').toLowerCase() : 'ID'}: {s.passengerIdNumber}
                      </span>
                    )}
                    {s.infants?.length > 0 && (
                      <span className="text-ink-muted">
                        {' '}
                        + {s.infants.map((i) => `${i.name} (${i.age})`).join(', ')}
                      </span>
                    )}
                  </dd>
                  {status !== 'cancelled' && (
                    <div className="mt-1 flex items-center gap-2">
                      {s.boardingStatus === 'boarded' ? (
                        <span className="rounded-full bg-success-light px-2 py-0.5 text-xs font-semibold text-success">
                          Boarded {formatDateTime(s.boardedAt)}
                        </span>
                      ) : (
                        <>
                          <input
                            value={presentedId[s.seatId] || ''}
                            onChange={(e) => setPresentedId({ ...presentedId, [s.seatId]: e.target.value })}
                            placeholder="ID presented at gate"
                            className="w-40 rounded-lg border border-slate-300 px-2 py-1 text-xs focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
                          />
                          <button
                            type="button"
                            onClick={() => handleCheckIn(s.seatId)}
                            disabled={checkIn.isPending}
                            className="rounded-lg bg-brand px-2.5 py-1 text-xs font-semibold text-white hover:bg-brand-dark disabled:opacity-50"
                          >
                            Check in
                          </button>
                        </>
                      )}
                    </div>
                  )}
                </div>
              ))}
            </div>
            {checkInError && <div className="mt-3"><ErrorBanner message={checkInError} /></div>}
          </div>
        )}

        {seats && seats.length > 0 && status !== 'cancelled' && (
          <div className="mt-4 border-t border-slate-100 pt-4">
            <dt className="mb-2 text-sm text-ink-muted">Boarding pass{seats.length > 1 ? 'es' : ''}</dt>
            <div className="flex flex-wrap gap-4">
              {seats.map((s) => (
                <div key={s.seatId} className="flex flex-col items-center gap-1">
                  <BoardingPassQr bookingId={booking.id} seatId={s.seatId} />
                  <span className="font-mono text-xs text-ink-muted">{s.seatNo}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {refundAmount !== undefined && (
          <div className="mt-4 rounded-lg bg-success-light px-3 py-2 text-sm text-success">
            Refunded {formatCurrency(refundAmount)}
          </div>
        )}
      </div>

      {status !== 'cancelled' && (
        <div className="mt-5 rounded-xl border border-slate-200 bg-surface p-5">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold text-ink">Payments</h2>
            <span className="text-sm text-ink-muted">
              Collected {formatCurrency(collected)} of {formatCurrency(booking.totalAmount)}
              {balanceDue > 0 && <span className="text-warning"> · {formatCurrency(balanceDue)} due</span>}
            </span>
          </div>

          {payments.length > 0 && (
            <ul className="mb-4 flex flex-col gap-1.5 text-sm">
              {payments.map((p) => (
                <li key={p.id} className="flex items-center justify-between text-ink">
                  <span className="capitalize">
                    {p.method}
                    {p.transactionId && <span className="font-mono text-xs text-ink-muted"> ({p.transactionId})</span>}
                  </span>
                  <span className="font-mono">{formatCurrency(p.amount)}</span>
                </li>
              ))}
            </ul>
          )}

          <form onSubmit={handleRecordPayment} className="flex flex-wrap items-end gap-3">
            <label className="block">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Method</span>
              <select
                value={paymentMethod}
                onChange={(e) => setPaymentMethod(e.target.value)}
                className="rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
              >
                <option value="cash">Cash</option>
                <option value="telebirr">Telebirr</option>
                <option value="cbe_birr">CBE Birr</option>
                <option value="card">Card</option>
              </select>
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Amount</span>
              <input
                type="number"
                min="0"
                step="0.01"
                value={paymentAmount}
                onChange={(e) => setPaymentAmount(e.target.value)}
                className="w-28 rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
              />
            </label>
            {paymentMethod !== 'cash' && (
              <label className="block">
                <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Txn ID (optional)</span>
                <input
                  value={paymentTxnId}
                  onChange={(e) => setPaymentTxnId(e.target.value)}
                  className="w-40 rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
                />
              </label>
            )}
            <button
              type="submit"
              disabled={createPayment.isPending}
              className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
            >
              {createPayment.isPending ? 'Recording…' : 'Record payment'}
            </button>
          </form>
          {paymentError && <div className="mt-3"><ErrorBanner message={paymentError} /></div>}
        </div>
      )}

      {cancelError && (
        <div className="mt-4">
          <ErrorBanner message={cancelError} />
        </div>
      )}

      {status !== 'cancelled' && (
        <div className="mt-5 flex items-center gap-3">
          {seats?.length === 1 && (
            <Link
              to={`/agent/bookings/${bookingId}/reschedule`}
              className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-medium text-ink hover:bg-slate-50"
            >
              Reschedule
            </Link>
          )}
          {!confirmingCancel ? (
            <button
              type="button"
              onClick={() => setConfirmingCancel(true)}
              className="rounded-lg border border-danger/40 px-4 py-2 text-sm font-medium text-danger hover:bg-danger-light"
            >
              Cancel booking
            </button>
          ) : (
            <div className="flex items-center gap-3 rounded-lg border border-danger/30 bg-danger-light p-3">
              <p className="text-sm text-danger">Cancel this booking? This can't be undone.</p>
              <button
                type="button"
                disabled={cancelBooking.isPending}
                onClick={handleCancel}
                className="shrink-0 rounded-lg bg-danger px-3 py-1.5 text-sm font-semibold text-white hover:bg-danger/90 disabled:opacity-50"
              >
                {cancelBooking.isPending ? 'Cancelling…' : 'Yes, cancel'}
              </button>
              <button
                type="button"
                onClick={() => setConfirmingCancel(false)}
                className="shrink-0 text-sm text-ink-muted hover:underline"
              >
                Never mind
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
