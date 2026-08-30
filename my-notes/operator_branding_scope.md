# Operator branding & UI customization — planning doc

**Status:** Phase 1 built 2026-08-30 (see CLAUDE.md "Operator branding").
Rest to be refined as it's built. Sibling of
`my-notes/operator_role_partitioning.md`.

## The framing

There is **no single "operator site"** — customers browse a cross-operator
marketplace, staff use one shared SPA, and tickets/tracking are the only
inherently single-operator surfaces. So branding is planned per-surface,
not as one global theme.

## Surfaces, ranked

| Surface | Single-operator context? | Value | Effort | Risk | Phase |
|---|---|---|---|---|---|
| Ticket / boarding pass (print + email) | Yes | High | Med | Low | 1 (partial — screen ticket only; print/email later) |
| Staff workspace chrome | Yes (logged-in tenant) | Med | Med | Low | **1 — done** |
| Public tracking result card | Yes (the booking/waybill) | Low–Med | Low | Low | **1 — done** |
| Marketplace trip cards (logo + name) | Per-row | Med | Low | Low | **1 — done** |
| Operator microsite (`/o/<slug>`) | Yes | High | High | Med | 2 |
| Custom domain (`selambus.et`) | Yes | High | Very high | Med | 3 (on demand only) |
| Branded email templates | Yes | Med | Med (blocked on real email) | Low | 4 |

## Phase 1 — done (2026-08-30)

- `V13` — `operator_settings` + `display_name`, `tagline`, `brand_color`,
  `accent_color`, `logo_url` (all nullable; null → Bustix default applied
  by the frontend). **Logo is a URL, not an uploaded image** — no asset
  storage in this app.
- `GET`/`PATCH /api/operator/branding` — its own endpoint (disjoint column
  set), because `PATCH /api/fleet/settings` is a full-replace. `GET`
  readable by `AGENT` (workspace theme), `PATCH` `OPERATOR_ADMIN` only.
- `OperatorBrandingView` embedded as `branding` in `TripSearchResult` /
  `BookingTrackingView` / `WaybillTrackingView`.
- Frontend: `brand`/`accent` Tailwind tokens → `rgb(var(--x) /
  <alpha-value>)`; `src/lib/color.js` hex→channels + shade derivation;
  `theme/BrandingProvider.jsx` themes the **staff workspace only**
  (fetches for `operator_admin`/`agent`); single-booking ticket/tracking
  cards get a **card-scoped** inline theme; `TripCard` gets logo + name
  only (no recolour — lists span operators). "Branding" tab in the
  Settings hub with a live preview. `public/brand/bustix-mark.svg` default.
- Only `brand` + `accent` are runtime-dynamic; `success`/`danger`/
  `warning`/`ink` stay fixed (semantic states must not be operator-tinted).

## Phase 2 — operator microsite (`/o/<slug>`)

Path-based, no new infra. A themed storefront showing **only that
operator's** routes/trips, full brand theme (not card-scoped). Needs:
- `operators.slug` (unique, URL-safe) + a `GET /api/operators/by-slug/{slug}`
  resolving slug → operator + branding.
- SPA: a `/o/:slug` route tree that filters search to that operator and
  wraps its subtree in the operator theme (reuse `themeVars`).
- Decide: does the microsite get its own header/footer, or the same shell
  re-themed? Does booking from a microsite differ from the marketplace?

## Phase 3 — custom domains (on demand only)

`selambus.et` → the operator's microsite. Needs DNS/CNAME instructions,
automated per-domain TLS (Let's Encrypt / ACME), Host-header routing in
nginx + node-bff resolving `Host` → operator. Multi-week. Do not build
until an operator actually asks.

## Phase 4 — branded email

After `LoggingEmailSender` is replaced with a real `NotificationSender`
(see CLAUDE.md "Known gaps"). Operator logo/colours in the outbox
templates, `email_from_name` on `operator_settings`.

## "UI setups" — operator config vs per-user preference

| Operator-level (`operator_settings`) | Per-user preference (localStorage / a future `user_prefs`) |
|---|---|
| Currency display, locale / date format | Table density, default dashboard period (already localStorage) |
| Ticket layout choice | Collapsed nav sections, last-used filters |
| Which optional modules show (e.g. cargo off for a passenger-only operator) | |

Phase these in with the surface that needs them.

## Guardrails

- **Logo is a URL** — no upload hygiene needed yet, but note the
  external-fetch / availability / mixed-content dependency. If uploads are
  added later: content-type allow-list, size cap, deny/sanitize SVG,
  serve from a distinct origin.
- **Colour contrast** — currently only format-validated (`#RRGGBB`). Add a
  WCAG-AA contrast check at save time before operators pick white-on-white.
- **Tenant leak** — theme by the *resource's* operator, never the viewer's
  (Phase 1 does this: card-scoped inline vars on ticket/tracking).
- **White-label vs impersonation** — a fully branded microsite is the
  intent of this SaaS; keep a "powered by Bustix" footer and prevent
  `display_name` collisions across operators.

## Open decisions

1. Print/PDF ticket — a dedicated `@media print` view or server-side PDF?
   (Phase 1 only branded the on-screen booking-detail card.)
2. Microsite before custom domains — yes (recommended).
3. Full palette runtime-dynamic vs just brand/accent — kept to brand/accent
   in Phase 1; revisit only if operators ask.
4. Does the marketplace show operator branding at all, or stay neutral so
   no operator gets a visual edge in search results? (Phase 1 shows logo +
   name on cards; revisit if it feels unfair.)
5. Multi-language / i18n — its own doc, much bigger than colours/logos.

## References

- CLAUDE.md "Operator branding" — Phase 1 as built
- CLAUDE.md "Per-operator settings" — the `operator_settings` table / config hub
- CLAUDE.md "Frontend" → "Design system" — the Tailwind tokens
- `my-notes/operator_role_partitioning.md` — branding management is an
  `operator_admin` capability there
