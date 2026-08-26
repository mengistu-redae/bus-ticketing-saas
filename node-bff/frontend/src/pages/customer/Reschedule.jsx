import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMyBooking, useRescheduleMyBooking, useTrip, useTripSearch, useTripSeats } from '../../api/queries.js';
import { ApiError } from '../../api/client.js';
import SeatMap from '../../components/SeatMap.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

/**
 * v1 reschedule only supports single-seat bookings (see spring-boot-api's
 * BookingRescheduleService) - this page picks one new trip and one new
 * seat, not a per-passenger mapping. Candidate trips are the same route
 * the booking is already on (a reschedule keeps the ticket, not the whole
 * itinerary a customer might actually want) - reuses useTripSearch exactly
 * like the customer Home/SearchResults flow does, just pre-filled and
 * auto-run instead of typed in.
 */
export default function Reschedule() {
  const { bookingId } = useParams();
  const navigate = useNavigate();

  const bookingQuery = useMyBooking(bookingId);
  const booking = bookingQuery.data;
  const currentTripQuery = useTrip(booking?.tripId);
  const currentTrip = currentTripQuery.data;

  const candidatesQuery = useTripSearch(
    { origin: currentTrip?.origin, destination: currentTrip?.destination },
    Boolean(currentTrip),
  );
  const candidateTrips = (candidatesQuery.data?.data || []).filter((t) => t.tripId !== booking?.tripId);

  const [selectedTripId, setSelectedTripId] = useState(null);
  const [selectedSeatId, setSelectedSeatId] = useState(null);
  const seatsQuery = useTripSeats(selectedTripId);

  const [submitError, setSubmitError] = useState(null);
  const reschedule = useRescheduleMyBooking(bookingId);

  async function handleConfirm() {
    setSubmitError(null);
    try {
      await reschedule.mutateAsync({ newTripId: selectedTripId, newSeatId: selectedSeatId });
      navigate(`/bookings/${bookingId}`);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setSubmitError(
          err.message ||
            "Can't reschedule this booking - it may be too close to departure (needs 12h notice), or that seat was just taken.",
        );
      } else {
        setSubmitError(err.message || 'Could not reschedule this booking. Please try again.');
      }
    }
  }

  if (bookingQuery.isLoading || currentTripQuery.isLoading) {
    return <Skeleton className="h-48 w-full max-w-2xl" />;
  }
  if (!booking || !currentTrip) {
    return <ErrorBanner message="Booking not found." />;
  }

  return (
    <div className="max-w-2xl">
      <h1 className="mb-1 text-2xl font-bold text-ink">Reschedule</h1>
      <p className="mb-6 text-sm text-ink-muted">
        Currently on {currentTrip.origin} &rarr; {currentTrip.destination}, departs {formatDateTime(currentTrip.departureAt)}.
        A mutation fee applies, and this needs at least 12 hours' notice before the current departure.
      </p>

      {!selectedTripId && (
        <>
          <h2 className="mb-3 text-sm font-semibold text-ink">Choose a new trip</h2>
          {candidatesQuery.isLoading && <Skeleton className="h-32 w-full" />}
          {candidatesQuery.isError && <ErrorBanner message={candidatesQuery.error?.message} onRetry={candidatesQuery.refetch} />}
          {!candidatesQuery.isLoading && candidateTrips.length === 0 && (
            <EmptyState title="No other trips on this route" description="Check back later, or cancel this booking instead." />
          )}
          <div className="flex flex-col gap-3">
            {candidateTrips.map((trip) => (
              <button
                key={trip.tripId}
                type="button"
                onClick={() => setSelectedTripId(trip.tripId)}
                className="rounded-xl border border-slate-200 bg-surface p-4 text-left shadow-sm hover:shadow-md"
              >
                <p className="text-sm font-semibold text-ink">Departs {formatDateTime(trip.departureAt)}</p>
                <p className="text-sm text-ink-muted">
                  {trip.operatorName} · {trip.availableSeats} seats left · {formatCurrency(trip.price)} / seat
                </p>
              </button>
            ))}
          </div>
        </>
      )}

      {selectedTripId && (
        <>
          <button type="button" onClick={() => { setSelectedTripId(null); setSelectedSeatId(null); }} className="mb-3 text-sm text-brand hover:underline">
            &larr; Choose a different trip
          </button>
          {seatsQuery.isLoading && <Skeleton className="h-64 w-full" />}
          {seatsQuery.isError && <ErrorBanner message={seatsQuery.error?.message} onRetry={seatsQuery.refetch} />}
          {seatsQuery.data && (
            <SeatMap
              seats={seatsQuery.data}
              selectedSeatIds={selectedSeatId ? [selectedSeatId] : []}
              onToggleSeat={(seat) => setSelectedSeatId(seat.status === 'booked' ? selectedSeatId : seat.id)}
            />
          )}

          <div className="sticky bottom-4 mt-6 flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-4 shadow-md">
            <p className="text-sm text-ink-muted">{selectedSeatId ? 'Seat selected' : 'Select a seat to continue'}</p>
            <button
              type="button"
              disabled={!selectedSeatId || reschedule.isPending}
              onClick={handleConfirm}
              className="rounded-lg bg-accent px-6 py-2.5 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
            >
              {reschedule.isPending ? 'Rescheduling…' : 'Confirm reschedule'}
            </button>
          </div>
          {submitError && <div className="mt-4"><ErrorBanner message={submitError} /></div>}
        </>
      )}
    </div>
  );
}
