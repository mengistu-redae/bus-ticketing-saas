const ID_TYPES = [
  { value: '', label: 'Not provided' },
  { value: 'KEBELE_ID', label: 'Kebele ID' },
  { value: 'DIGITAL_ID', label: 'Digital ID' },
  { value: 'PASSPORT', label: 'Passport' },
  { value: 'DRIVERS_LICENSE', label: "Driver's license" },
];

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

// Same as inputClass minus `w-full` - for the age/infant fields that want to
// stay narrow. Tailwind emits `.w-full` after the numbered width utilities,
// so `${inputClass} w-24` would otherwise still render full-width.
const narrowInputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * One card per selected seat, collecting who it's actually for - a real
 * ticket is issued to a named passenger per seat, not an anonymous seat id
 * (see spring-boot-api's CreateBookingRequest.PassengerSeat, changed
 * 2026-08-24). `passengers` is a { [seatId]: {passengerName, passengerPhone,
 * passengerIdNumber, passengerIdType, age, infants} } map;
 * `onChange(seatId, field, value)` updates one field of one seat's entry -
 * `infants` is set as a whole new array rather than one entry at a time,
 * same call shape as any scalar field.
 *
 * `showIdFields` is off by default (customer self-service - a name is
 * enough to book, an ID document is usually checked in person at boarding,
 * not entered online) and on for the counter/agent flow, where the walk-in
 * customer's ID is typically already in hand at the point of sale.
 *
 * Age/infants implement my-notes/ethiopian_bus_system_specs.md section
 * 4.1's age rules: an infant (age < 3) rides free on this seat's
 * passenger's lap rather than getting a seat of their own - see
 * spring-boot-api's BookingInfant for why. Shown for both customer and
 * agent flows (unlike showIdFields), since any booking can include a
 * lap-sitting infant, not just counter ones.
 */
export default function PassengerDetailsForm({ seats, passengers, onChange, showIdFields = false }) {
  return (
    <div className="flex flex-col gap-3">
      {seats.map((seat) => {
        const passenger = passengers[seat.id] || {};
        const infants = passenger.infants || [];

        function updateInfant(index, field, value) {
          onChange(seat.id, 'infants', infants.map((inf, i) => (i === index ? { ...inf, [field]: value } : inf)));
        }
        function addInfant() {
          onChange(seat.id, 'infants', [...infants, { name: '', age: 0 }]);
        }
        function removeInfant(index) {
          onChange(seat.id, 'infants', infants.filter((_, i) => i !== index));
        }

        return (
          <div key={seat.id} className="rounded-lg border border-slate-200 p-4">
            <p className="mb-3 font-mono text-xs font-semibold uppercase tracking-wide text-ink-muted">
              Seat {seat.seatNo}
            </p>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field label="Passenger name">
                <input
                  required
                  value={passenger.passengerName || ''}
                  onChange={(e) => onChange(seat.id, 'passengerName', e.target.value)}
                  placeholder="SURNAME/FIRSTNAME"
                  className={inputClass}
                />
              </Field>
              <Field label="Phone (optional)">
                <input
                  type="tel"
                  value={passenger.passengerPhone || ''}
                  onChange={(e) => onChange(seat.id, 'passengerPhone', e.target.value)}
                  placeholder="+251911234567"
                  className={inputClass}
                />
              </Field>
              <Field label="Age (optional)">
                <input
                  type="number"
                  min="0"
                  value={passenger.age ?? ''}
                  onChange={(e) => onChange(seat.id, 'age', e.target.value)}
                  className={`${narrowInputClass} w-24`}
                />
              </Field>
              {showIdFields && (
                <>
                  <Field label="ID type (optional)">
                    <select
                      value={passenger.passengerIdType || ''}
                      onChange={(e) => onChange(seat.id, 'passengerIdType', e.target.value)}
                      className={inputClass}
                    >
                      {ID_TYPES.map((t) => (
                        <option key={t.value} value={t.value}>
                          {t.label}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="ID number (optional)">
                    <input
                      value={passenger.passengerIdNumber || ''}
                      onChange={(e) => onChange(seat.id, 'passengerIdNumber', e.target.value)}
                      className={inputClass}
                    />
                  </Field>
                </>
              )}
            </div>

            <div className="mt-3 border-t border-slate-100 pt-3">
              {infants.map((infant, i) => (
                <div key={i} className="mb-2 flex items-end gap-2">
                  <Field label="Infant name">
                    <input
                      value={infant.name}
                      onChange={(e) => updateInfant(i, 'name', e.target.value)}
                      placeholder="Riding on this passenger's lap"
                      className={`${narrowInputClass} w-56`}
                    />
                  </Field>
                  <Field label="Age (0-2)">
                    <input
                      type="number"
                      min="0"
                      max="2"
                      value={infant.age}
                      onChange={(e) => updateInfant(i, 'age', e.target.value)}
                      className={`${narrowInputClass} w-20`}
                    />
                  </Field>
                  <button type="button" onClick={() => removeInfant(i)} className="mb-2 text-xs text-danger hover:underline">
                    Remove
                  </button>
                </div>
              ))}
              <button type="button" onClick={addInfant} className="text-xs font-semibold text-brand hover:underline">
                + Add infant (under 3, rides free, no seat needed)
              </button>
            </div>
          </div>
        );
      })}
    </div>
  );
}

function Field({ label, children }) {
  return (
    <label className="block text-left">
      <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</span>
      {children}
    </label>
  );
}
