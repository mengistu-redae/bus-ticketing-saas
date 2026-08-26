import { useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { useCancelMyBooking, useMyBooking, useMyBookingSeats, useTrip } from '../../api/queries.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { ApiError } from '../../api/client.js';
import StatusPill from '../../components/StatusPill.jsx';
import BoardingPassQr from '../../components/BoardingPassQr.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

export default function BookingDetail() {
  const { bookingId } = useParams();
  const location = useLocation();
  const { authenticated } = useAuth();

  // Arriving fresh from SeatSelection carries booking/trip/seats already -
  // render immediately, then still reconcile against the source of truth in
  // the background via the same queries a cold visit uses. For a guest
  // (not authenticated), useMyBooking/useMyBookingSeats are ownership-
  // scoped endpoints a guest has no session to call at all - passing
  // `undefined` here (rather than bookingId) keeps them disabled, the same
  // trick already used for tripQuery below, so a guest never triggers
  // apiFetch's blanket 401-redirect-to-login. That means a guest only ever
  // sees this page immediately after booking (via location.state); a
  // revisit/refresh has no other way to recover it - see /track-booking.
  const stateBooking = location.state?.booking;
  const stateTrip = location.state?.trip;
  const stateSeats = location.state?.seats;

  const bookingQuery = useMyBooking(authenticated ? bookingId : undefined);
  const seatsQuery = useMyBookingSeats(authenticated ? bookingId : undefined);
  const booking = bookingQuery.data || stateBooking;
  const tripId = booking?.tripId;
  const tripQuery = useTrip(stateTrip ? undefined : tripId);
  const trip = stateTrip || tripQuery.data;
  const seats = seatsQuery.data || stateSeats;

  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [cancelError, setCancelError] = useState(null);
  const cancelBooking = useCancelMyBooking(bookingId);

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

  if (bookingQuery.isLoading && !stateBooking) {
    return <Skeleton className="h-48 w-full max-w-xl" />;
  }
  if (bookingQuery.isError && !stateBooking) {
    return <ErrorBanner message={bookingQuery.error?.message} onRetry={bookingQuery.refetch} />;
  }
  if (!booking) {
    return !authenticated ? (
      <div className="max-w-xl rounded-xl border border-slate-200 bg-surface p-5 text-sm text-ink-muted">
        This booking isn't available here anymore. Look it up with your booking reference and phone number instead
        at{' '}
        <Link to="/track-booking" className="text-brand hover:underline">
          Track a booking
        </Link>
        .
      </div>
    ) : (
      <ErrorBanner message="Booking not found." />
    );
  }

  const status = cancelBooking.data?.status || booking.status;
  const refundAmount = cancelBooking.data?.refundAmount;

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
            <p className="text-sm text-ink-muted">
              {trip.operatorName} · Departs {formatDateTime(trip.departureAt)}
            </p>
          </>
        ) : (
          <p className="text-sm italic text-ink-muted">Trip details unavailable.</p>
        )}

        <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-slate-100 pt-4 text-sm">
          <div>
            <dt className="text-ink-muted">Ticket #</dt>
            <dd className="font-mono text-xs text-ink">{booking.ticketNumber || '—'}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Booking ref (PNR)</dt>
            <dd className="font-mono text-xs text-ink">{booking.bookingRef || '—'}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Booked</dt>
            <dd className="text-ink">{formatDateTime(booking.createdAt)}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Seats</dt>
            <dd className="font-mono text-ink">
              {seats && seats.length > 0 ? seats.map((s) => s.seatNo).join(', ') : '—'}
            </dd>
          </div>
          <div>
            <dt className="text-ink-muted">Fare + VAT</dt>
            <dd className="font-mono text-ink">
              {formatCurrency(booking.subtotalAmount)} + {formatCurrency(booking.taxAmount)}
            </dd>
          </div>
          <div>
            <dt className="text-ink-muted">Total paid</dt>
            <dd className="font-mono font-semibold text-ink">{formatCurrency(booking.totalAmount)}</dd>
          </div>
        </dl>

        {seats && seats.some((s) => s.passengerName) && (
          <div className="mt-4 border-t border-slate-100 pt-4 text-sm">
            <dt className="mb-2 text-ink-muted">Passengers</dt>
            <div className="flex flex-col gap-1">
              {seats.map((s) => (
                <dd key={s.seatId} className="text-ink">
                  <span className="font-mono">{s.seatNo}</span>: {s.passengerName || '—'}
                  {s.infants?.length > 0 && (
                    <span className="text-ink-muted">
                      {' '}
                      + {s.infants.map((i) => `${i.name} (${i.age})`).join(', ')}
                    </span>
                  )}
                </dd>
              ))}
            </div>
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

      {!authenticated && (
        <div className="mt-4 rounded-lg border border-slate-200 bg-surface p-3 text-sm text-ink-muted">
          Booked without an account - save your booking ref <span className="font-mono text-ink">{booking.bookingRef}</span> and
          phone number. You can look this booking up again anytime at{' '}
          <Link to="/track-booking" className="text-brand hover:underline">
            Track a booking
          </Link>
          .
        </div>
      )}

      {cancelError && (
        <div className="mt-4">
          <ErrorBanner message={cancelError} />
        </div>
      )}

      {/* Cancel/reschedule call ownership-scoped endpoints (/api/my-bookings/*)
          a guest has no session to use - not offered here at all for a guest
          booking in this pass (creation + track-lookup only, see CLAUDE.md). */}
      {authenticated && status !== 'cancelled' && (
        <div className="mt-5 flex items-center gap-3">
          {seats?.length === 1 && (
            <Link
              to={`/bookings/${bookingId}/reschedule`}
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
