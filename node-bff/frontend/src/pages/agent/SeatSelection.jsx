import { useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { useCreateBooking, useTrip, useTripSeats } from '../../api/queries.js';
import { ApiError } from '../../api/client.js';
import SeatMap from '../../components/SeatMap.jsx';
import PassengerDetailsForm from '../../components/PassengerDetailsForm.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

/**
 * The agent/counter mirror of the customer SeatSelection page. Shares the
 * same POST /api/bookings call (useCreateBooking) - spring-boot-api decides
 * channel=counter from the caller's JWT role alone, not anything in this
 * request - and the same seat-conflict/idempotency handling. Differs in
 * three ways: no name pre-fill (the walk-in customer isn't the logged-in
 * agent), showIdFields=true on the passenger form (the counter typically
 * has the customer's ID in hand at the point of sale, unlike online
 * self-service), and it lands on the staff booking detail page afterward.
 */
export default function AgentSeatSelection() {
  const { tripId } = useParams();
  const location = useLocation();
  const navigate = useNavigate();

  const tripFromState = location.state?.trip;
  const tripQuery = useTrip(tripFromState ? undefined : tripId);
  const trip = tripFromState || tripQuery.data;

  const { data: seats, isLoading: seatsLoading, isError: seatsError, error: seatsErr, refetch: refetchSeats } =
    useTripSeats(tripId);

  const [selectedSeatIds, setSelectedSeatIds] = useState([]);
  const [passengers, setPassengers] = useState({});
  const [bookingError, setBookingError] = useState(null);
  const idempotencyKeyRef = useRef(crypto.randomUUID());

  const createBooking = useCreateBooking();

  const selectedSeats = useMemo(
    () => (seats || []).filter((s) => selectedSeatIds.includes(s.id)),
    [seats, selectedSeatIds],
  );
  const total = trip ? Number(trip.price) * selectedSeatIds.length : 0;
  const missingPassengerName = selectedSeatIds.some((id) => !passengers[id]?.passengerName?.trim());

  function toggleSeat(seat) {
    if (seat.status === 'booked') return;
    setBookingError(null);
    setSelectedSeatIds((prev) =>
      prev.includes(seat.id) ? prev.filter((id) => id !== seat.id) : [...prev, seat.id],
    );
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
    try {
      const booking = await createBooking.mutateAsync({
        tripId,
        passengers: selectedSeatIds.map((seatId) => ({
          seatId,
          passengerName: passengers[seatId].passengerName.trim(),
          passengerPhone: passengers[seatId]?.passengerPhone?.trim() || undefined,
          passengerIdNumber: passengers[seatId]?.passengerIdNumber?.trim() || undefined,
          passengerIdType: passengers[seatId]?.passengerIdType || undefined,
          age: passengers[seatId]?.age !== '' && passengers[seatId]?.age != null ? Number(passengers[seatId].age) : undefined,
          infants: (passengers[seatId]?.infants || [])
            .filter((inf) => inf.name?.trim())
            .map((inf) => ({ name: inf.name.trim(), age: Number(inf.age) })),
        })),
        idempotencyKey: idempotencyKeyRef.current,
      });
      navigate(`/agent/bookings/${booking.id}`, { state: { booking } });
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
              <h2 className="mb-3 text-sm font-semibold text-ink">Passenger details</h2>
              <PassengerDetailsForm seats={selectedSeats} passengers={passengers} onChange={updatePassenger} showIdFields />
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
            </div>
            <button
              type="button"
              disabled={selectedSeatIds.length === 0 || missingPassengerName || createBooking.isPending}
              onClick={handleBook}
              className="rounded-lg bg-accent px-6 py-2.5 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
            >
              {createBooking.isPending ? 'Booking…' : 'Book & collect payment'}
            </button>
          </div>

          {bookingError && <div className="mt-4"><ErrorBanner message={bookingError} /></div>}
        </>
      )}
    </div>
  );
}
