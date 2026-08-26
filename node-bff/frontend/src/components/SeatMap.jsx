/**
 * Groups flat seat rows (id, seatNo, seatClass, status) into visual rows by
 * the leading digits of seatNo (SeatLayoutGenerator's own convention: "1A",
 * "1B", "2A", ... - the number is the row, the letter is the column). The
 * seats endpoint doesn't return the bus's own seatLayout string ("2x2" etc),
 * so the aisle gap here is a visual approximation - split each row after its
 * middle seat - rather than an exact mirror of SeatLayoutGenerator's real
 * left-count/right-count split. Good enough to read as "a bus," not a
 * pixel-exact reproduction of the layout config.
 */
function groupByRow(seats) {
  const rows = new Map();
  for (const seat of seats) {
    const match = /^(\d+)/.exec(seat.seatNo || '');
    const rowKey = match ? match[1] : seat.seatNo;
    if (!rows.has(rowKey)) rows.set(rowKey, []);
    rows.get(rowKey).push(seat);
  }
  return [...rows.entries()]
    .sort((a, b) => Number(a[0]) - Number(b[0]))
    .map(([, rowSeats]) => rowSeats.sort((a, b) => a.seatNo.localeCompare(b.seatNo)));
}

function seatClasses(seat, isSelected) {
  if (seat.status === 'booked') {
    return 'border-slate-300 bg-slate-200 text-slate-400 cursor-not-allowed';
  }
  if (isSelected) {
    return 'border-accent bg-accent text-white shadow-sm';
  }
  return 'border-brand text-brand hover:bg-brand-light cursor-pointer';
}

export default function SeatMap({ seats, selectedSeatIds, onToggleSeat }) {
  const rows = groupByRow(seats);

  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-6">
      <div className="mb-6 flex items-center gap-5 text-xs text-ink-muted">
        <Legend swatchClassName="border-brand" label="Available" />
        <Legend swatchClassName="border-accent bg-accent" label="Selected" />
        <Legend swatchClassName="border-slate-300 bg-slate-200" label="Booked" />
      </div>
      <div className="flex flex-col items-center gap-2">
        {rows.map((rowSeats, rowIndex) => {
          const aisleAfter = Math.ceil(rowSeats.length / 2);
          return (
            <div key={rowIndex} className="flex items-center gap-2">
              {rowSeats.map((seat, i) => {
                const isSelected = selectedSeatIds.includes(seat.id);
                return (
                  <div key={seat.id} className="flex items-center">
                    <button
                      type="button"
                      disabled={seat.status === 'booked'}
                      onClick={() => onToggleSeat(seat)}
                      className={`flex h-11 w-11 items-center justify-center rounded-lg border-2 font-mono text-xs font-semibold transition-colors ${seatClasses(seat, isSelected)}`}
                      aria-pressed={isSelected}
                      aria-label={`Seat ${seat.seatNo}, ${seat.status === 'booked' ? 'booked' : isSelected ? 'selected' : 'available'}`}
                    >
                      {seat.seatNo}
                    </button>
                    {i + 1 === aisleAfter && i + 1 !== rowSeats.length && <div className="w-6" aria-hidden="true" />}
                  </div>
                );
              })}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function Legend({ swatchClassName, label }) {
  return (
    <div className="flex items-center gap-1.5">
      <span className={`h-3.5 w-3.5 rounded border-2 ${swatchClassName}`} />
      {label}
    </div>
  );
}
