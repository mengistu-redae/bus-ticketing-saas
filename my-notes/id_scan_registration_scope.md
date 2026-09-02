# Scan-the-national-ID passenger & cargo registration — scope / plan

Raised 2026-08-28. Goal: at booking (passenger) and waybill (cargo) time,
let staff/customers **scan an Ethiopian Fayda national ID** (or a passport)
and auto-fill name, date of birth / age, sex, ID type, ID number — instead
of typing every field. Not yet built; this is the planning doc, read before
scoping a build pass.

## Background — Ethiopian Fayda

- **Fayda** = national digital ID, run by the National ID Program (NIDP),
  built on **MOSIP**.
- Identifiers: **FIN** (Fayda Identification Number, 12 digits) and **FCN**
  (card number, on the physical card).
- Physical card has a **QR code**: a compressed, **digitally signed**
  demographic payload (MOSIP "secure QR" / PixelPass format) — name, DOB,
  sex, address, sometimes phone, optionally a low-res face image. Verifiable
  **offline** against NIDP's published signing certificate.
- **Fayda eSignet**: MOSIP's OIDC identity provider — "Verify with Fayda" /
  eKYC. Registered relying parties get verified KYC claims after user
  consent + OTP/biometric auth.
- **Partner onboarding is a hard prerequisite** for anything beyond decoding
  a QR: register as a relying party on the NIDP partner portal, get sandbox
  creds + the QR signing cert, sign a data-sharing agreement, whitelist
  redirect URIs. Lead-time work — start with the MOSIP sandbox + published
  test FINs.

## Current state in this codebase

- `booking_seats.passenger_id_number` / `passenger_id_type` — **plaintext**,
  both nullable, entered manually. `passengerIdType` enum
  (`IdentityDocumentType`): `KEBELE_ID` / `DIGITAL_ID` (= Fayda) / `PASSPORT`
  / `DRIVERS_LICENSE`.
- `cargo_waybills.consignor_id_number` (nullable) / `consignee_id_number`
  (**NOT NULL**) — plaintext.
- Equality-only checks against the stored number:
  `BoardingService.checkIn` (presented vs `passenger_id_number`) and
  `CargoWaybillService.collect` (presented vs `consignee_id_number`).
- Frontend `PassengerDetailsForm` shows ID fields only when
  `showIdFields` (agent flow). `age` is "descriptive only," no DOB stored.
- node-bff already runs one OIDC client (Keycloak) via `openid-client` in
  `src/auth/oidc.js` — a second provider (Fayda eSignet) would follow the
  same shape.

## Capture channels (mapped to the two app channels)

### A. Counter / agent — QR scan (offline, recommended first)
Agent has the walk-in passenger's physical card.
- Decode with a USB 2D barcode scanner (types the string into a focused
  input) **or** device camera (`html5-qrcode` / zxing in the React app).
- Frontend sends the raw decoded string to node-bff → `POST /api/identity/
  scan/qr` on spring-boot-api → **verify MOSIP signature** against the
  pinned NIDP cert, decompress, parse → return a normalized
  `ScannedIdentity`.
- Verification is server-side so the trust cert / parsing lives in one
  place and never ships to the browser.
- Proves the **card is genuine and unaltered**, not that the presenter is
  the holder (agent still eyeballs the photo, or a later biometric step).

### B. Self-service / customer — "Verify with Fayda" (eSignet OIDC eKYC)
- New **second OIDC client** (separate from the Keycloak login client):
  `node-bff/src/auth/fayda.js` + `src/routes/fayda.js`
  (`GET /auth/fayda/start`, `/auth/fayda/callback`).
- Callback exchanges the code, calls the eKYC userinfo endpoint, stashes
  verified claims in `req.session.faydaKyc` (short TTL), redirects back to
  the booking page with a flag; frontend reads them via
  `GET /auth/fayda/result` (same shape as `/auth/me`), pre-fills, clears.
- Note: eSignet may return a **PSUT** (pairwise pseudonymous id), not the
  raw FIN, unless the individual-id claim/scope is granted — decide which
  we store.

### C. Fallbacks
- **Passport**: MRZ (2 bottom lines) — client-side parse + check digits
  (`POST /api/identity/scan/mrz`), `idType = PASSPORT`. Standardized, no
  crypto trust.
- **Old Kebele ID / driver's licence**: no machine-readable standard →
  keep manual entry (OCR-assist is low priority / unreliable).
- **Manual entry** always stays as the universal fallback.

## Data the scan fills

| Field | Fayda QR | Fayda eKYC | Passport MRZ |
|---|---|---|---|
| name (SURNAME/FIRSTNAME) | ✓ | ✓ | ✓ |
| DOB → age | ✓ | ✓ | ✓ |
| sex | ✓ | ✓ | ✓ |
| idType | DIGITAL_ID | DIGITAL_ID | PASSPORT |
| idNumber | FIN | FIN or PSUT | passport no. |
| phone | sometimes | ✓ (registered) | ✗ |
| address | ✓ | ✓ | ✗ |
| photo | low-res ✓ | ✓ | ✗ |

## Schema changes

- `booking_seats`: `passenger_id_verified BOOLEAN NOT NULL DEFAULT false`,
  `passenger_id_verification_source VARCHAR` (`manual` | `fayda_qr` |
  `fayda_ekyc` | `passport_mrz`). Optional `passenger_dob DATE` (additive;
  `age` stays).
- `cargo_waybills`: same verified / source pair per party (consignor,
  consignee).
- New `identity_verifications` audit table (compliance-friendly, keeps
  `booking_seats` lean): `(id, booking_id?, waybill_id?, party, source,
  full_name, date_of_birth, sex, id_number_hash, id_number_last4,
  consent_at, verified_by, verified_at)` — **no photo, no raw FIN**.
- **PII hardening (do regardless of scanning):** stop storing raw ID
  numbers in the clear. The boarding/collect checks are equality-only, so
  **hash + last4** is sufficient and lowest-risk; encryption (reversible)
  only if a full number must ever be re-displayed/printed. Migrate existing
  `booking_seats` / `cargo_waybills` rows; update `BoardingService` /
  `CargoWaybillService` to compare hashes.

## New backend endpoints (spring-boot-api)

- `POST /api/identity/scan/qr` `{ rawQr }` → `ScannedIdentity { fullName,
  surname, firstName, dateOfBirth, sex, idType, idNumberLast4,
  idNumberToken (opaque, single-use, Redis-cached ~10 min), phone?,
  address?, photoBase64? }`. `AGENT` / `OPERATOR_ADMIN`. Rate-limited.
- `POST /api/identity/scan/mrz` `{ mrzLine1, mrzLine2 }` → same shape,
  `idType = PASSPORT`.
- Booking / waybill create (and cargo `confirm-and-issue` / `collect`)
  requests gain an optional **`idVerificationToken`** per passenger/party.
  When present, the server re-reads the cached verified claim, sets
  `_verified = true` + source, and takes the ID number **from the token**,
  not the request body — the browser never holds or resends the raw FIN.

## node-bff changes

- New Fayda eSignet OIDC client + routes (channel B above). Tokens/claims
  stay server-side; browser only receives the demographic fields it renders.
- `forwardToApi` already passes bodies/params through — `/api/identity/*`
  needs no `PUBLIC_ROUTES` entry (authenticated).

## Frontend changes

- New shared `components/IdScanButton.jsx` + `hooks/useIdScan.js`
  (camera-or-hardware-scanner modal → POST → normalized result).
- `PassengerDetailsForm`: "Scan ID" per seat card (agent flow). On success:
  fill name/age/sex/idType, show last4, hold the token in form state, show a
  green "Verified via Fayda" badge. Manual edit reverts source to `manual` +
  clears verified.
- Customer self-service: "Verify with Fayda" button doing the eSignet
  redirect; on return pre-fill + badge the first passenger.
- Cargo `Waybills.jsx` / `RequestShipment.jsx`: "Scan ID" for the consignor;
  `WaybillDetail.jsx` collect step: scan the consignee's ID to satisfy the
  presented-ID check instead of typing it.

## Config / secrets

`bustix.fayda.*` in `application.yml`: `qr-signing-cert` (pinned NIDP PEM),
`ekyc.issuer` / `ekyc.client-id`, partner auth creds if the NIDP auth API is
used. Secrets via env (same pattern as `BFF_CLIENT_SECRET` /
`bustix.keycloak-admin.*`). Separate sandbox vs prod issuer.

## Compliance (Ethiopia)

- Digital ID Proclamation **1284/2023** + Personal Data Protection
  Proclamation **1321/2024**: lawful basis + explicit consent to process
  Fayda data; data minimization; FIN is sensitive.
- Capture consent at scan time and log it (`identity_verifications`).
- **Do not persist** the photo or biometrics — show transiently only.
- Store the minimum: verified name, DOB, source, timestamp, FIN **hash** +
  last4.
- NIDP partner agreement governs allowed use; ticketing KYC is a standard
  case but must be registered.
- Retention: purge verified-claim snapshots a short window after the trip
  (scheduled job, same shape as `TripLifecycleScheduler`).

## Phasing

- **Phase 0 — spike (no app changes):** `pixelpass`-decode a sample Fayda QR
  in a scratch script; confirm the exact payload shape and which fields
  Ethiopia populates; obtain sandbox eSignet creds + the QR signing cert.
- **Phase 1 — QR at the counter:** schema (`identity_verifications`,
  `_verified` / `_source`), `POST /api/identity/scan/qr` with signature
  verification, `IdScanButton` in the agent `PassengerDetailsForm`, verified
  badge. Manual entry untouched.
- **Phase 2 — PII hardening:** hash + last4 for all stored ID numbers
  (booking + cargo), migrate existing rows, switch the equality checks to
  hash compares. (Could precede Phase 1.)
- **Phase 3 — cargo:** scan consignor at issue, consignee at collect.
- **Phase 4 — self-service Fayda eSignet** OIDC eKYC flow.
- **Phase 5 — passport MRZ fallback** + retention purge job.

## Open forks (confirm before Phase 1)

1. **Primary channel:** counter-QR first / self-service-eSignet first /
   both.
2. **ID number at rest:** hash + last4 (irreversible, enough for the
   equality checks — recommended) / encrypt (reversible, printable) / leave
   plaintext (status quo, not recommended).
3. **Face photo:** persist it for an agent visual match, or never store it
   (transient display only — recommended).
4. **Assurance level:** trust the signed QR alone (offline) / also require an
   NIDP auth call (OTP or biometric) for a higher-assurance "verified".
5. **Scope of the first build:** passenger only, or passenger + cargo
   parties together.

## Related

- [[intermediate-stops-enhancement]] — the other deferred enhancement.
- `my-notes/ethiopian_bus_system_specs.md` §2.2 (identity_document), §4.1
  (gate-check ID match).
