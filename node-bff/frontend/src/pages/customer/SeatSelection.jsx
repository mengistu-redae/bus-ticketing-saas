import { useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { useCreateBooking, useCreateGuestBooking, useTrip, useTripSeats } from '../../api/queries.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { ApiError } from '../../api/client.js';
import SeatMap from '../../components/SeatMap.jsx';
import PassengerDetailsForm from '../../components/PassengerDetailsForm.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

export default function SeatSelection() {
  const { tripId } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const { authenticated, user } = useAuth();

  // Passed by TripCard when arriving from search results - avoids a second
  // round trip for the common path. Falls back to GET /api/trips/{tripId}
  // for a direct/refreshed visit (see spring-boot-api's TripController -
  // this endpoint was added specifically to cover that gap).
  const tripFromState = location.state?.trip;
  const tripQuery = useTrip(tripFromState ? undefined : tripId);
  const trip = tripFromState || tripQuery.data;

  const { data: seats, isLoading: seatsLoading, isError: seatsError, error: seatsErr, refetch: refetchSeats } =
    useTripSeats(tripId);

  const [selectedSeatIds, setSelectedSeatIds] = useState([]);
  // { [seatId]: { passengerName, passengerPhone } } - a real ticket is
  // issued to a named passenger per seat, not an anonymous seat id (see
  // spring-boot-api's CreateBookingRequest.PassengerSeat).
  const [passengers, setPassengers] = useState({});
  const [bookingError, setBookingError] = useState(null);
  // Collected once per booking, not per seat - unlike passenger details,
  // there's only one guest making this booking regardless of how many
  // seats it covers. Only rendered/required when !authenticated - see
  // guestContact* usage in handleBook below.
  const [guestPhone, setGuestPhone] = useState('');
  const [guestEmail, setGuestEmail] = useState('');
  // Minted once per checkout attempt and reused across a retried click -
  // that's what lets the server's (tenant_id, idempotency_key) uniqueness
  // check actually protect against a double-booking on a flaky network,
  // rather than each click racing for a fresh key.
  const idempotencyKeyRef = useRef(crypto.randomUUID());

  const createBooking = useCreateBooking();
  const createGuestBooking = useCreateGuestBooking();
  const booking = authenticated ? createBooking : createGuestBooking;

  const selectedSeats = useMemo(
    () => (seats || []).filter((s) => selectedSeatIds.includes(s.id)),
    [seats, selectedSeatIds],
  );
  const subtotal = trip ? Number(trip.price) * selectedSeatIds.length : 0;
  const vatRate = trip?.vatRate != null ? Number(trip.vatRate) : 0;
  const tax = Math.round(subtotal * vatRate * 100) / 100;
  const total = subtotal + tax;
  const missingPassengerName = selectedSeatIds.some((id) => !passengers[id]?.passengerName?.trim());
  // +251[79]XXXXXXXX - same E.164 Ethiopian pattern spring-boot-api
  // validates contactPhone against (CreateGuestBookingRequest); checked
  // client-side too so a guest gets an inline message instead of a raw
  // 400 from the API.
  const guestPhoneValid = /^\+251[79]\d{8}$/.test(guestPhone.trim());

  function toggleSeat(seat) {
    if (seat.status === 'booked') return;
    setBookingError(null);
    setSelectedSeatIds((prev) => {
      if (prev.includes(seat.id)) {
        return prev.filter((id) => id !== seat.id);
      }
      // First seat selected defaults to the logged-in customer's own name,
      // for the common single-seat case - still editable, and every
      // additional seat starts blank since it's presumably someone else.
      if (prev.length === 0 && !passengers[seat.id]?.passengerName) {
        setPassengers((p) => ({ ...p, [seat.id]: { ...p[seat.id], passengerName: user?.name || user?.preferred_username || '' } }));
      }
      return [...prev, seat.id];
    });
  }

  function updatePassenger(seatId, field, value) {
    setPassengers((p) => ({ ...p, [seatId]: { ...p[seatId], [field]: value } }));
  }

  async function handleBook() {
    setBookingError(null);
    if (missingPassengerName) {
      setBookingError('Enter a passenger name for every selected seat.');
      return;
    }
    if (!authenticated && !guestPhoneValid) {
      setBookingError('Enter a valid phone number (e.g. +251911234567) so you can look up this booking later.');
      return;
    }
    const passengersPayload = selectedSeatIds.map((seatId) => ({
      seatId,
      passengerName: passengers[seatId].passengerName.trim(),
      passengerPhone: passengers[seatId]?.passengerPhone?.trim() || undefined,
      age: passengers[seatId]?.age !== '' && passengers[seatId]?.age != null ? Number(passengers[seatId].age) : undefined,
      infants: (passengers[seatId]?.infants || [])
        .filter((inf) => inf.name?.trim())
        .map((inf) => ({ name: inf.name.trim(), age: Number(inf.age) })),
    }));
    try {
      const created = await booking.mutateAsync(
        authenticated
          ? { tripId, passengers: passengersPayload, idempotencyKey: idempotencyKeyRef.current }
          : {
              tripId,
              passengers: passengersPayload,
              idempotencyKey: idempotencyKeyRef.current,
              contactPhone: guestPhone.trim(),
              contactEmail: guestEmail.trim() || undefined,
            },
      );
      navigate(`/bookings/${created.id}`, {
        state: { booking: created, trip, seats: selectedSeats },
      });
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setBookingError('One of those seats was just taken by someone else. Pick another.');
        setSelectedSeatIds([]);
        idempotencyKeyRef.current = crypto.randomUUID();
        refetchSeats();
        return;
      }
      setBookingError(err.message || 'Could not complete the booking. Please try again.');
    }
  }

  return (
    <div>
      <div className="mb-6">
        {trip ? (
          <>
            <h1 className="text-2xl font-bold text-ink">
              {trip.origin} <span className="text-ink-muted">&rarr;</span> {trip.destination}
            </h1>
            <p className="text-sm text-ink-muted">
              {trip.operatorName} · Departs {formatDateTime(trip.departureAt)} · {formatCurrency(trip.price)} / seat
            </p>
          </>
        ) : (
          <Skeleton className="h-8 w-72" />
        )}
      </div>

      {seatsLoading && <Skeleton className="h-64 w-full" />}
      {seatsError && <ErrorBanner message={seatsErr?.message} onRetry={refetchSeats} />}

      {seats && (
        <>
          <SeatMap seats={seats} selectedSeatIds={selectedSeatIds} onToggleSeat={toggleSeat} />

          {selectedSeats.length > 0 && (
            <div className="mt-6">
              <h2 className="mb-3 text-sm font-semibold text-ink">Who's traveling?</h2>
              <PassengerDetailsForm seats={selectedSeats} passengers={passengers} onChange={updatePassenger} />
            </div>
          )}

          {selectedSeats.length > 0 && !authenticated && (
            <div className="mt-4 rounded-lg border border-slate-200 p-4">
              <p className="mb-3 text-sm font-semibold text-ink">Your contact info</p>
              <p className="mb-3 text-xs text-ink-muted">
                Booking without an account - save your phone number to look this booking up again later at{' '}
                <span className="font-mono">/track-booking</span>.
              </p>
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <label className="block text-left">
                  <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Phone</span>
                  <input
                    type="tel"
                    required
                    value={guestPhone}
                    onChange={(e) => setGuestPhone(e.target.value)}
                    placeholder="+251911234567"
                    className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
                  />
                </label>
                <label className="block text-left">
                  <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Email (optional)</span>
                  <input
                    type="email"
                    value={guestEmail}
                    onChange={(e) => setGuestEmail(e.target.value)}
                    placeholder="you@example.com"
                    className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
                  />
                </label>
              </div>
            </div>
          )}

          <div className="sticky bottom-4 mt-6 flex flex-col gap-3 rounded-xl border border-slate-200 bg-surface p-4 shadow-md sm:flex-row sm:items-center sm:justify-between">
            <div>
              <p className="text-sm text-ink-muted">
                {selectedSeatIds.length === 0
                  ? 'Select a seat to continue'
                  : `${selectedSeatIds.length} seat${selectedSeatIds.length > 1 ? 's' : ''} selected: ${selectedSeats
                      .map((s) => s.seatNo)
                      .join(', ')}`}
              </p>
              <p className="font-mono text-xl font-semibold tabular-nums text-ink">{formatCurrency(total)}</p>
              {selectedSeatIds.length > 0 && tax > 0 && (
                <p className="text-xs text-ink-muted">
                  {formatCurrency(subtotal)} + {formatCurrency(tax)} VAT
                </p>
              )}
            </div>
            <button
              type="button"
              disabled={
                selectedSeatIds.length === 0 ||
                missingPassengerName ||
                (!authenticated && !guestPhoneValid) ||
                booking.isPending
              }
              onClick={handleBook}
              className="rounded-lg bg-accent px-6 py-2.5 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
            >
              {booking.isPending ? 'Booking…' : 'Book now'}
            </button>
          </div>

          {bookingError && <div className="mt-4"><ErrorBanner message={bookingError} /></div>}
        </>
      )}
    </div>
  );
}
