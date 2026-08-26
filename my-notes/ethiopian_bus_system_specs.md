# Business Requirements Document (BRD) & System Specifications
## Ethiopian Private Bus Cross-Country Ticketing & Cargo System

### 1. System Metadata & Entities
* **System Scope:** Cross-country long-distance luxury passenger transport and accompanying/freight cargo management.
* **Primary Reference Entities:** Selam Bus Line S.C., Odaa Bus, Golden Bus, Sky Bus, Zemen Bus, Hello Bus, Liyu Bus.

---

### 2. Passenger Ticket Module (Data Schema Requirements)

#### 2.1 Core Fields
* `ticket_number`: Alphanumeric UUID or pattern-based ID (e.g., `SB-YYYY-7894561`).
* `booking_reference_pnr`: 6-character unique string (e.g., `SM76TR`).
* `issue_timestamp`: ISO 8601 format date-time.
* `operator_tin`: 10-digit Ethiopian Tax Identification Number.

#### 2.2 Passenger Sub-Schema
* `passenger_name`: Format `SURNAME/FIRSTNAME TITLE` (e.g., `MAMO/ALEMU MR`).
* `phone_number`: Standardized E.164 Ethiopian format (`+2519xxxxxxxx` or `+2517xxxxxxxx`).
* `identity_document`: Type enum (`KEBELE_ID`, `DIGITAL_ID`, `PASSPORT`, `DRIVERS_LICENSE`) and `document_number`.

#### 2.3 Trip & Inventory Sub-Schema
* `origin_terminal`: Specific physical location object (e.g., `Addis Ababa (Meskel Square Terminal)`).
* `destination_terminal`: Destination city and specific station boundary.
* `reporting_time`: Fixed at `04:30 AM` local time for standard morning fleets.
* `departure_time`: Fixed at `05:00 AM` local time for standard morning fleets.
* `bus_metadata`: `plate_number` (e.g., `ET-3-A12345`), `total_capacity`, `bus_type` (e.g., Luxury Coach, Semi-Sleeper).
* `seat_allocation`: Structural code (e.g., `12A`), validating window/aisle layouts.

#### 2.4 Transactional Sub-Schema
* `base_fare`: Decimal currency representation in Ethiopian Birr (ETB).
* `vat_amount`: Fixed statutory calculation of 15% on the base fare.
* `total_paid`: Calculated system metric (`base_fare + vat_amount`).
* `payment_gateway`: Integration endpoints for `Telebirr` and `CBE Birr`.
* `payment_transaction_id`: Gateway-returned string for matching ledgers.

---

### 3. Cargo & Logistics Module (Data Schema Requirements)

#### 3.1 Structural Context
Handles accompanied baggage over the 30kg threshold, standalone parcels, and commercial cross-country delivery operations.

#### 3.2 Schema Architecture
* `waybill_number`: Unique tracking key (e.g., `SBC-990812`).
* `logistics_timestamps`: Fields for `issued_at`, `dispatched_at`, `arrived_at`, and `collected_at`.
* `parties`:
  * `consignor`: Name, authenticated phone number, and physical ID.
  * `consignee`: Name, authenticated phone number, and physical ID verification toggle.
* `item_metrics`:
  * `description`: Text-based cataloging of contents.
  * `quantity`: Integer representing discrete packaging units.
  * `declared_value`: Optional field for insurance indexing.
  * `gross_weight`: Decimal weight metric in kilograms (kg).
  * `excess_weight`: Calculated programmatic metric (`gross_weight - 30.00`). If negative, defaults to `0.00`.
* `financial_breakdown`:
  * `base_freight_charge`: Fixed rate depending on origin-destination zones.
  * `weight_surcharge`: Dynamic rate calculation (`excess_weight * surcharge_per_kg`). Surcharge defaults to `10.00 ETB/kg`.
  * `handling_service_fee`: Flat platform fee (e.g., `50.00 ETB`).
  * `total_cargo_cost`: Aggregated metric (`base_freight_charge + weight_surcharge + handling_service_fee`).
  * `payment_status`: Enum (`UNPAID`, `PAID`, `COLLECT_ON_DELIVERY`).

---

### 4. Core Business Logic & State Machines

#### 4.1 Boarding State Machine Rules
* **Validation Engine:** System must throw a blocking exception if the customer identity profile does not match the stored token string at gate check-in.
* **Gate Lockout:** Status updates to `BOARDING_CLOSED` exactly at `05:00 AM`. No exceptions or post-departure changes allowed.
* **Age Rules:**
  * Age `< 3`: Ticket type `INFANT` -> Cost: `0.00 ETB` -> Seat constraint: `LAP_SITTING`.
  * Age `>= 3`: Ticket type `ADULT` -> Cost: `FULL_FARE` -> Seat constraint: `ASSIGNED_SEAT`.

#### 4.2 Prohibited Items Validation List
The system must parse cargo descriptions or inventory notes against a regex-supported blacklist: `["livestock", "goat", "sheep", "chicken", "flammable", "petrol", "weapon", "gun", "charcoal", "khat_commercial"]`.

---

### 5. Financial Lifecycle & Cancellation Engine

The cancellation microservice must implement a dynamic countdown logic comparing `system_current_time` against scheduled `departure_time`.

```math
\Delta T = \text{departure\_time} - \text{cancellation\_request\_time}
```

```
IF Delta T > 24 hours:
    REFUND_PERCENTAGE = 90%
    PENALTY_RATE = 10%
    ALLOW_WALLET_REVERSAL = TRUE

ELSE IF Delta T >= 12 hours AND Delta T <= 24 hours:
    REFUND_PERCENTAGE = 75%
    PENALTY_RATE = 25%
    ALLOW_WALLET_REVERSAL = FALSE (Requires Terminal Verification)

ELSE IF Delta T >= 2 hours AND Delta T < 12 hours:
    REFUND_PERCENTAGE = 50%
    PENALTY_RATE = 50%
    ALLOW_WALLET_REVERSAL = FALSE

ELSE (Delta T < 2 hours OR No-Show):
    REFUND_PERCENTAGE = 0%
    PENALTY_RATE = 100%
    TICKET_STATUS = VOIDED
```

#### 5.3 Rescheduling & Modification Protocols
* **Time Gate:** Rescheduling calls evaluate `Delta T >= 12 hours`. If invalid, block the request and route to the refund engine.
* **Platform Fee Engine:** Impose a static mutation fee mapping between `50.00 ETB` and `100.00 ETB` depending on channel tiers.
* **Immutability Principle:** Modifying names or identity numbers post-generation is blocked. Only travel time, date, and seat variables remain mutable.
