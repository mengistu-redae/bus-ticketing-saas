import { useState } from 'react';
import { useTrackBooking } from '../api/queries.js';
import { ApiError } from '../api/client.js';
import StatusPill from '../components/StatusPill.jsx';
import Skeleton from '../components/Skeleton.jsx';
import { formatCurrency, formatDateTime } from '../lib/format.js';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * Reachable without logging in, structurally identical to Track.jsx (the
 * cargo-tracking page this was modeled on) - GET
 * /api/bookings/guest/track/{bookingRef}?phone=... (permitAll()'d
 * server-side, and carved out of node-bff's normal requireSession gate
 * too, see node-bff/src/routes/api.js). A guest booking has no account to
 * look its own history up by, so this is a two-factor lookup (booking ref
 * + a matching phone) instead - see BookingTrackingView's javadoc for
 * exactly which fields this intentionally does and doesn't expose. Works
 * for any booking whose phone matches, not just guest-channel ones, so a
 * logged-out customer can use it too.
 */
export default function TrackBooking() {
  const [bookingRef, setBookingRef] = useState('');
  const [phone, setPhone] = useState('');
  const [submitted, setSubmitted] = useState(null);

  const trackQuery = useTrackBooking(submitted?.bookingRef, submitted?.phone);

  function handleSubmit(event) {
    event.preventDefault();
    setSubmitted({ bookingRef: bookingRef.trim(), phone: phone.trim() });
  }

  const notFound = trackQuery.isError && trackQuery.error instanceof ApiError && trackQuery.error.status === 404;

  return (
    <div className="mx-auto max-w-md">
      <h1 className="mb-1 text-2xl font-bold text-ink">Track a booking</h1>
      <p className="mb-6 text-sm text-ink-muted">
        Enter your booking reference (PNR) and the phone number you booked with.
      </p>

      <form onSubmit={handleSubmit} className="mb-6 flex flex-col gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <label className="block">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Booking ref (PNR)</span>
          <input
            value={bookingRef}
            onChange={(e) => setBookingRef(e.target.value)}
            placeholder="e.g. A1B2C3"
            className={`${inputClass} w-full font-mono uppercase`}
          />
        </label>
        <label className="block">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Phone number</span>
          <input value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="+2519xxxxxxxx" className={`${inputClass} w-full`} />
        </label>
        <button
          type="submit"
          disabled={!bookingRef.trim() || !phone.trim()}
          className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
        >
          Track
        </button>
      </form>

      {trackQuery.isLoading && <Skeleton className="h-32 w-full" />}

      {notFound && (
        <div className="rounded-xl border border-danger/30 bg-danger-light px-4 py-3 text-sm text-danger">
          No booking found for that reference and phone number. Double-check both and try again.
        </div>
      )}
      {trackQuery.isError && !notFound && (
        <div className="rounded-xl border border-danger/30 bg-danger-light px-4 py-3 text-sm text-danger">
          {trackQuery.error?.message || 'Something went wrong.'}
        </div>
      )}

      {trackQuery.data && (
        <div className="rounded-xl border border-slate-200 bg-surface p-5">
          <div className="mb-4 flex items-center justify-between">
            <span className="font-mono text-xs text-ink-muted">{trackQuery.data.bookingRef}</span>
            <StatusPill status={trackQuery.data.status} />
          </div>
          <p className="mb-1 text-sm font-semibold text-ink">
            {trackQuery.data.origin} <span className="text-ink-muted">&rarr;</span> {trackQuery.data.destination}
          </p>
          <p className="mb-4 text-xs text-ink-muted">
            Departs {formatDateTime(trackQuery.data.departureAt)} · Ticket {trackQuery.data.ticketNumber}
          </p>

          {trackQuery.data.seats?.length > 0 && (
            <div className="mb-4 border-t border-slate-100 pt-4 text-sm">
              <p className="mb-2 text-ink-muted">Seats</p>
              <div className="flex flex-col gap-1">
                {trackQuery.data.seats.map((s, i) => (
                  <p key={i} className="text-ink">
                    <span className="font-mono">{s.seatNo}</span>: {s.passengerName || '—'}
                  </p>
                ))}
              </div>
            </div>
          )}

          <dl className="grid grid-cols-2 gap-3 border-t border-slate-100 pt-4 text-sm">
            <div>
              <dt className="text-ink-muted">Fare + VAT</dt>
              <dd className="font-mono text-ink">
                {formatCurrency(trackQuery.data.subtotalAmount)} + {formatCurrency(trackQuery.data.taxAmount)}
              </dd>
            </div>
            <div>
              <dt className="text-ink-muted">Total</dt>
              <dd className="font-mono font-semibold text-ink">{formatCurrency(trackQuery.data.totalAmount)}</dd>
            </div>
          </dl>
        </div>
      )}
    </div>
  );
}
