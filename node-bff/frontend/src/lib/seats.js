/**
 * Tailwind chip classes for a seat-availability indicator, keyed on how many
 * seats are still open: sold out (danger), nearly full (warning), plenty
 * (success). Shared by the customer `TripCard` and the operator Trips list.
 */
export function seatFillClass(availableSeats) {
  if (availableSeats <= 0) return 'bg-danger-light text-danger';
  if (availableSeats <= 4) return 'bg-warning-light text-warning';
  return 'bg-success-light text-success';
}
