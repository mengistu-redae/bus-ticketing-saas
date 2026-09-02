import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiDelete, apiGet, apiGetWithCount, apiPatch, apiPost } from './client.js';

// ---- auth ----

export function useAuthMe() {
  return useQuery({
    queryKey: ['auth', 'me'],
    queryFn: async () => {
      try {
        return await apiGet('/auth/me');
      } catch (err) {
        // /auth/me itself returning 401 is an expected "not logged in"
        // state, not an error to retry or redirect on - every other /api
        // call redirects on 401 (see api/client.js), this one is the
        // exception since it's how the app finds out it's logged out.
        if (err.status === 401) {
          return { authenticated: false };
        }
        throw err;
      }
    },
    retry: false,
    staleTime: 5 * 60 * 1000,
  });
}

// ---- dashboards (role landing pages) ----
// GET /api/{operator,agent,platform,my}/dashboard - read-only aggregates,
// one per role, see spring-boot-api's com.bustix.dashboard. Each is gated
// server-side by @PreAuthorize; the frontend only calls the one matching the
// signed-in role (see App.jsx's RoleHome / Home.jsx's customer block).

export function useOperatorDashboard(period = '30d') {
  return useQuery({
    queryKey: ['dashboard', 'operator', period],
    queryFn: () => apiGet(`/api/operator/dashboard?period=${period}`),
    placeholderData: (prev) => prev, // keep the old view while a period switch refetches
  });
}

export function useAgentDashboard() {
  return useQuery({ queryKey: ['dashboard', 'agent'], queryFn: () => apiGet('/api/agent/dashboard') });
}

export function usePlatformDashboard(period = '30d') {
  return useQuery({
    queryKey: ['dashboard', 'platform', period],
    queryFn: () => apiGet(`/api/platform/dashboard?period=${period}`),
    placeholderData: (prev) => prev,
  });
}

export function useMyDashboard(enabled = true) {
  return useQuery({
    queryKey: ['dashboard', 'my'],
    queryFn: () => apiGet('/api/my-dashboard'),
    enabled,
  });
}

// ---- trips (marketplace) ----

export function useTripSearch({ origin, destination, departureAfter, page = 0, size = 20 }, enabled) {
  const params = new URLSearchParams({ origin, destination, page: String(page), size: String(size) });
  if (departureAfter) params.set('departureAfter', departureAfter);

  return useQuery({
    queryKey: ['trips', 'search', origin, destination, departureAfter, page, size],
    queryFn: () => apiGetWithCount(`/api/trips/search?${params.toString()}`),
    enabled: Boolean(enabled && origin && destination),
    // Keep the current page visible (with its pager) while the next page loads.
    placeholderData: keepPreviousData,
  });
}

// The whole lane in one request - the results page (TripSearchView) filters,
// sorts, categorises and re-paginates client-side, so it wants every
// scheduled trip on the route up front, not a server page. size=200 is well
// under the backend's 300 ceiling; data.totalCount still flags if a lane
// somehow exceeds it.
const LANE_PAGE_SIZE = 200;

export function useLaneTripSearch({ origin, destination, departureAfter }, enabled) {
  return useTripSearch({ origin, destination, departureAfter, page: 0, size: LANE_PAGE_SIZE }, enabled);
}

// The whole lane, tenant-scoped - the agent /agent/search results page. A
// counter agent can only sell their own operator's trips (BookingService
// throws TenantMismatchException / 403 otherwise), so their search must be
// scoped to that operator, not the cross-operator marketplace.
export function useFleetLaneTripSearch({ origin, destination, departureAfter }, enabled) {
  return useFleetTripSearch({ origin, destination, departureAfter, page: 0, size: LANE_PAGE_SIZE }, enabled);
}

// Backs the From/To autocomplete dropdown (LocationAutocomplete) - the
// component itself debounces keystrokes before this ever fires, so no
// debouncing here; query is disabled below 2 characters, matching the
// backend's own floor (TripController.locations) so an empty/near-empty
// query never round-trips at all.
export function useLocationSuggestions(query) {
  const trimmed = (query ?? '').trim();
  return useQuery({
    queryKey: ['trips', 'locations', trimmed],
    queryFn: () => apiGet(`/api/trips/locations?query=${encodeURIComponent(trimmed)}`),
    enabled: trimmed.length >= 2,
    staleTime: 60 * 1000,
  });
}

export function useTrip(tripId) {
  return useQuery({
    queryKey: ['trips', tripId],
    queryFn: () => apiGet(`/api/trips/${tripId}`),
    enabled: Boolean(tripId),
  });
}

export function useTripSeats(tripId) {
  return useQuery({
    queryKey: ['trips', tripId, 'seats'],
    queryFn: () => apiGet(`/api/trips/${tripId}/seats`),
    enabled: Boolean(tripId),
    // The seat map goes stale as other people book; poll while the seat-
    // selection page is open so a taken seat shows as taken before the
    // user hits the 409 at "Book now".
    refetchInterval: 15000,
  });
}

// ---- bookings ----

/**
 * Shared by both booking channels - spring-boot-api decides self_service vs
 * counter from the caller's JWT role, not from anything in this payload
 * (see BookingController). `passengers` is a list of
 * {seatId, passengerName, passengerPhone?, passengerIdNumber?, passengerIdType?} -
 * changed 2026-08-24 from a bare seatIds array, since a real ticket is
 * issued to a named passenger per seat (see spring-boot-api's
 * CreateBookingRequest.PassengerSeat).
 */
export function useCreateBooking() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ tripId, passengers, idempotencyKey }) =>
      apiPost('/api/bookings', { tripId, passengers, idempotencyKey }),
    onSuccess: (_data, { tripId }) => {
      queryClient.invalidateQueries({ queryKey: ['my-bookings'] });
      queryClient.invalidateQueries({ queryKey: ['agent-bookings'] });
      queryClient.invalidateQueries({ queryKey: ['trips', tripId, 'seats'] });
    },
  });
}

/**
 * No account, no session - a visitor books with contact info instead of an
 * identity (see spring-boot-api's BookingController.createGuestBooking).
 * `contactPhone` is required (E.164 Ethiopian, same pattern used elsewhere)
 * since it's also the second factor for useTrackBooking below;
 * `contactEmail` is optional and only used for the confirmation email.
 * Deliberately not the same hook as useCreateBooking above - the request
 * shape/endpoint genuinely differ, same "one hook per access pattern" the
 * rest of this file already follows (useCancelMyBooking vs
 * useCancelBooking, etc).
 */
export function useCreateGuestBooking() {
  return useMutation({
    mutationFn: ({ tripId, passengers, idempotencyKey, contactPhone, contactEmail }) =>
      apiPost('/api/bookings/guest', { tripId, passengers, idempotencyKey, contactPhone, contactEmail }),
  });
}

// ---- public booking tracking (no login - see node-bff/src/routes/api.js's
// permitAll carve-out for this path, same shape as useTrackWaybill below) ----

export function useTrackBooking(bookingRef, phone) {
  return useQuery({
    queryKey: ['bookings', 'track', bookingRef, phone],
    queryFn: () => apiGet(`/api/bookings/guest/track/${encodeURIComponent(bookingRef)}?phone=${encodeURIComponent(phone)}`),
    enabled: Boolean(bookingRef && phone),
    retry: false,
  });
}

export function useMyBookings() {
  return useQuery({
    queryKey: ['my-bookings'],
    queryFn: () => apiGet('/api/my-bookings'),
  });
}

export function useMyBooking(bookingId) {
  return useQuery({
    queryKey: ['my-bookings', bookingId],
    queryFn: () => apiGet(`/api/my-bookings/${bookingId}`),
    enabled: Boolean(bookingId),
  });
}

export function useMyBookingSeats(bookingId) {
  return useQuery({
    queryKey: ['my-bookings', bookingId, 'seats'],
    queryFn: () => apiGet(`/api/my-bookings/${bookingId}/seats`),
    enabled: Boolean(bookingId),
  });
}

export function useCancelMyBooking(bookingId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (reason) => apiPost(`/api/my-bookings/${bookingId}/cancel`, reason ? { reason } : undefined),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['my-bookings'] });
      queryClient.invalidateQueries({ queryKey: ['my-bookings', bookingId] });
    },
  });
}

/**
 * v1 only supports single-seat bookings (see spring-boot-api's
 * BookingRescheduleService) - {newTripId, newSeatId}, not a list. Blocked
 * server-side with a 409 if less than 12h notice remains before the
 * *current* trip's departure (my-notes/ethiopian_bus_system_specs.md
 * section 5.3's "Time Gate") - the caller is expected to cancel instead.
 */
export function useRescheduleMyBooking(bookingId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ newTripId, newSeatId }) => apiPost(`/api/my-bookings/${bookingId}/reschedule`, { newTripId, newSeatId }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['my-bookings'] });
      queryClient.invalidateQueries({ queryKey: ['my-bookings', bookingId] });
    },
  });
}

// ---- staff (agent/operator_admin) bookings ----
// GET /api/bookings(/{id})(/seats) - tenant-scoped, added 2026-08-24
// alongside the passenger/ticket fields; previously nothing exposed a
// staff-facing way to look up bookings at all. Kept as a separate
// 'agent-bookings' query key rather than reusing 'my-bookings', which is
// ownership-scoped to the customer's own bookings, a different thing.

export function useAgentBookings() {
  return useQuery({
    queryKey: ['agent-bookings'],
    queryFn: () => apiGet('/api/bookings'),
  });
}

export function useAgentBooking(bookingId) {
  return useQuery({
    queryKey: ['agent-bookings', bookingId],
    queryFn: () => apiGet(`/api/bookings/${bookingId}`),
    enabled: Boolean(bookingId),
  });
}

export function useAgentBookingSeats(bookingId) {
  return useQuery({
    queryKey: ['agent-bookings', bookingId, 'seats'],
    queryFn: () => apiGet(`/api/bookings/${bookingId}/seats`),
    enabled: Boolean(bookingId),
  });
}

/** The staff cancellation path (POST /api/bookings/{id}/cancel) - tenant-scoped, not the customer's ownership-scoped one. */
export function useCancelBooking(bookingId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (reason) => apiPost(`/api/bookings/${bookingId}/cancel`, reason ? { reason } : undefined),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-bookings'] });
      queryClient.invalidateQueries({ queryKey: ['agent-bookings', bookingId] });
    },
  });
}

/** Staff path - see useRescheduleMyBooking for the shared v1 shape/limitations. */
export function useRescheduleBooking(bookingId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ newTripId, newSeatId }) => apiPost(`/api/bookings/${bookingId}/reschedule`, { newTripId, newSeatId }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-bookings'] });
      queryClient.invalidateQueries({ queryKey: ['agent-bookings', bookingId] });
    },
  });
}

/** Gate check-in - staff-only, see spring-boot-api's BoardingService for the identity-match/gate-lockout rules. */
export function useCheckIn(bookingId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ seatId, presentedIdNumber }) =>
      apiPost(`/api/bookings/${bookingId}/seats/${seatId}/check-in`, { presentedIdNumber }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-bookings', bookingId, 'seats'] });
    },
  });
}

// ---- payments (recorded against a booking by staff) ----

export function usePayments(bookingId) {
  return useQuery({
    queryKey: ['agent-bookings', bookingId, 'payments'],
    queryFn: () => apiGet(`/api/bookings/${bookingId}/payments`),
    enabled: Boolean(bookingId),
  });
}

export function useCreatePayment(bookingId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ method, amount, transactionId }) =>
      apiPost(`/api/bookings/${bookingId}/payments`, { method, amount, transactionId }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-bookings', bookingId, 'payments'] });
    },
  });
}

// ---- operator_admin fleet management (buses/routes/trips/refund-policies) ----
// All tenant-scoped via TenantContext server-side, OPERATOR_ADMIN-only.
// Mutations return the mutated resource (spring-boot-api's convention
// throughout, not just here), so no separate invalidate-then-refetch is
// needed for the single-resource case, but the list is still invalidated
// since a list view needs to pick up the change too.

export function useFleetBuses() {
  return useQuery({ queryKey: ['fleet', 'buses'], queryFn: () => apiGet('/api/fleet/buses') });
}

export function useCreateBus() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/fleet/buses', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['fleet', 'buses'] }),
  });
}

export function useUpdateBus() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ busId, ...body }) => apiPatch(`/api/fleet/buses/${busId}`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['fleet', 'buses'] }),
  });
}

export function useFleetRoutes() {
  return useQuery({ queryKey: ['fleet', 'routes'], queryFn: () => apiGet('/api/fleet/routes') });
}

export function useCreateRoute() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/fleet/routes', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['fleet', 'routes'] }),
  });
}

export function useUpdateRoute() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ routeId, ...body }) => apiPatch(`/api/fleet/routes/${routeId}`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['fleet', 'routes'] }),
  });
}

export function useFleetTrips() {
  return useQuery({ queryKey: ['fleet', 'trips'], queryFn: () => apiGet('/api/fleet/trips') });
}

/**
 * Operator-scoped trip search - GET /api/fleet/trips/search. Same shape as
 * the cross-operator marketplace useTripSearch, but only the caller's own
 * trips (an operator never sees another operator's inventory).
 */
export function useFleetTripSearch({ origin, destination, departureAfter, page = 0, size = 20 }, enabled) {
  const params = new URLSearchParams({ origin, destination, page: String(page), size: String(size) });
  if (departureAfter) params.set('departureAfter', departureAfter);
  return useQuery({
    queryKey: ['fleet', 'trips', 'search', origin, destination, departureAfter, page, size],
    queryFn: () => apiGetWithCount(`/api/fleet/trips/search?${params.toString()}`),
    enabled: Boolean(enabled && origin && destination),
    placeholderData: keepPreviousData,
  });
}

/**
 * The operator "Trips" management list - GET /api/fleet/trips/manage.
 * Denormalized (route/bus names, seat occupancy), filtered
 * (status: upcoming|all|cancelled) and paged, unlike useFleetTrips() which
 * is the bare all-trips list the cargo trip-picker uses.
 */
export function useFleetTripsManage({ status = 'upcoming', routeId, page = 0, size = 20 }) {
  const params = new URLSearchParams({ status, page: String(page), size: String(size) });
  if (routeId) params.set('routeId', routeId);
  return useQuery({
    queryKey: ['fleet', 'trips', 'manage', status, routeId || null, page, size],
    queryFn: () => apiGetWithCount(`/api/fleet/trips/manage?${params.toString()}`),
    placeholderData: keepPreviousData,
  });
}

// A trip create / edit / cancel changes both the bare list (cargo picker) and
// the manage list (operator Trips page).
function invalidateTrips(queryClient) {
  queryClient.invalidateQueries({ queryKey: ['fleet', 'trips'] });
}

export function useCreateTrip() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/fleet/trips', body),
    onSuccess: () => invalidateTrips(queryClient),
  });
}

export function useUpdateTrip() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ tripId, ...body }) => apiPatch(`/api/fleet/trips/${tripId}`, body),
    onSuccess: () => invalidateTrips(queryClient),
  });
}

/** Sets a trip's status to cancelled - see TripController.cancel's javadoc on why this isn't a row delete. */
export function useCancelTrip() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (tripId) => apiDelete(`/api/fleet/trips/${tripId}`),
    onSuccess: () => invalidateTrips(queryClient),
  });
}

export function useRefundPolicies() {
  return useQuery({ queryKey: ['fleet', 'refund-policies'], queryFn: () => apiGet('/api/fleet/refund-policies') });
}

export function useCreateRefundPolicy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/fleet/refund-policies', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['fleet', 'refund-policies'] }),
  });
}

export function useUpdateRefundPolicy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ policyId, tiers }) => apiPatch(`/api/fleet/refund-policies/${policyId}`, { tiers }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['fleet', 'refund-policies'] }),
  });
}

/** Real delete, not a soft-deactivate - see RefundPolicyController's javadoc on why that's safe here. */
export function useDeleteRefundPolicy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (policyId) => apiDelete(`/api/fleet/refund-policies/${policyId}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['fleet', 'refund-policies'] }),
  });
}

// ---- operator settings (operator_admin, singleton per operator) ----
// GET /api/fleet/settings returns { overrides, effective, defaults } - see
// OperatorSettingsController. PATCH is a full replace of the override set:
// a null field clears that override back to the platform default.

export function useOperatorSettings() {
  return useQuery({ queryKey: ['fleet', 'settings'], queryFn: () => apiGet('/api/fleet/settings') });
}

export function useUpdateOperatorSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPatch('/api/fleet/settings', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['fleet', 'settings'] }),
  });
}

// ---- operator branding (operator_admin writes; agent reads for the workspace theme) ----
// GET/PATCH /api/operator/branding - its own endpoint, disjoint from the
// full-replace PATCH /api/fleet/settings (see OperatorBrandingController).

export function useOperatorBranding() {
  return useQuery({ queryKey: ['operator', 'branding'], queryFn: () => apiGet('/api/operator/branding') });
}

export function useUpdateOperatorBranding() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPatch('/api/operator/branding', body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['operator', 'branding'] }); // BrandingProvider theme
      queryClient.invalidateQueries({ queryKey: ['fleet', 'settings'] });    // the Branding tab's raw source
    },
  });
}

// ---- cargo waybills (agent/operator_admin, tenant-scoped) ----
// /api/cargo/waybills(/{id})(/dispatch|arrive|collect|cancel) - staff-only,
// see com.bustix.cargo.CargoWaybillController. Every read/mutation here
// resolves to a WaybillWithItems shape ({waybill, items: [...]}), not a
// bare CargoWaybill - a shipment is one or more line items (see
// CargoWaybillItem), which the entity itself doesn't carry inline. The
// list is still invalidated on mutation since a list view needs to pick
// up status changes too.

export function useWaybills({ tripId, status } = {}) {
  const params = new URLSearchParams();
  if (tripId) params.set('tripId', tripId);
  if (status) params.set('status', status);
  const query = params.toString();
  return useQuery({
    queryKey: ['cargo', 'waybills', tripId || null, status || null],
    queryFn: () => apiGet(`/api/cargo/waybills${query ? `?${query}` : ''}`),
  });
}

export function useWaybill(waybillId) {
  return useQuery({
    queryKey: ['cargo', 'waybills', waybillId],
    queryFn: () => apiGet(`/api/cargo/waybills/${waybillId}`),
    enabled: Boolean(waybillId),
  });
}

export function useCreateWaybill() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/cargo/waybills', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['cargo', 'waybills'] }),
  });
}

/** PATCH - physical-shipment fields 409 once status != "issued" (see CargoWaybillService.update); paymentStatus is exempt from that freeze. */
export function useUpdateWaybill(waybillId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPatch(`/api/cargo/waybills/${waybillId}`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['cargo', 'waybills'] });
      queryClient.invalidateQueries({ queryKey: ['cargo', 'waybills', waybillId] });
    },
  });
}

function useWaybillLifecycleAction(waybillId, action) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/cargo/waybills/${waybillId}/${action}`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['cargo', 'waybills'] });
      queryClient.invalidateQueries({ queryKey: ['cargo', 'waybills', waybillId] });
    },
  });
}

export function useDispatchWaybill(waybillId) {
  return useWaybillLifecycleAction(waybillId, 'dispatch');
}

export function useArriveWaybill(waybillId) {
  return useWaybillLifecycleAction(waybillId, 'arrive');
}

/** {presentedIdNumber} - checked against consigneeIdNumber on file, 409 on mismatch (see ConsigneeIdentityMismatchException). */
export function useCollectWaybill(waybillId) {
  return useWaybillLifecycleAction(waybillId, 'collect');
}

/** Pre-dispatch only - reuses the same refund_policies an operator configures for passenger bookings. */
export function useCancelWaybill(waybillId) {
  return useWaybillLifecycleAction(waybillId, 'cancel');
}

// ---- cargo payments (agent/operator_admin, waybill-scoped) ----
// /api/cargo/waybills/{waybillId}/payments(/{id}) - the freight counterpart
// to usePayments/useCreatePayment above, same Payment entity/table (V10
// added a nullable waybill_id). Separate query key namespace so
// invalidation doesn't collide with booking payments.

export function useWaybillPayments(waybillId) {
  return useQuery({
    queryKey: ['cargo', 'waybills', waybillId, 'payments'],
    queryFn: () => apiGet(`/api/cargo/waybills/${waybillId}/payments`),
    enabled: Boolean(waybillId),
  });
}

export function useCreateWaybillPayment(waybillId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/cargo/waybills/${waybillId}/payments`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['cargo', 'waybills', waybillId, 'payments'] });
    },
  });
}

// ---- cargo rates (operator_admin) ----
// /api/fleet/cargo-rates - same shape as refund-policies above, including
// route_id nullable = operator-wide default. Real delete, not
// soft-deactivate (same reasoning as refund policies).

export function useCargoRates() {
  return useQuery({ queryKey: ['fleet', 'cargo-rates'], queryFn: () => apiGet('/api/fleet/cargo-rates') });
}

export function useCreateCargoRate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/fleet/cargo-rates', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['fleet', 'cargo-rates'] }),
  });
}

export function useUpdateCargoRate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ rateId, ...body }) => apiPatch(`/api/fleet/cargo-rates/${rateId}`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['fleet', 'cargo-rates'] }),
  });
}

export function useDeleteCargoRate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (rateId) => apiDelete(`/api/fleet/cargo-rates/${rateId}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['fleet', 'cargo-rates'] }),
  });
}

// ---- a logged-in customer's own shipment history ----
// /api/my-shipments(/{id}) - the cargo counterpart to useMyBookings above.
// Scoped through two ownership paths combined, not mutually exclusive:
// waybills attached to a booking the customer owns, and (since
// 2026-08-26) waybills the customer requested directly - see
// CargoWaybillRepository.findAllOwnedByCustomer. A standalone
// staff-created waybill with neither never appears here. Resolves to
// WaybillWithItems shapes, same as the staff cargo hooks above.

export function useMyShipments() {
  return useQuery({ queryKey: ['my-shipments'], queryFn: () => apiGet('/api/my-shipments') });
}

/**
 * GET /api/operators - id + name of active operators, for the operator
 * picker on the shipment-request form (a request is routed to one operator
 * at creation, see spring-boot-api's CargoWaybillService.requestShipment).
 */
export function useOperators() {
  return useQuery({
    queryKey: ['operators'],
    queryFn: () => apiGet('/api/operators'),
    staleTime: 5 * 60 * 1000,
  });
}

export function useMyShipment(waybillId) {
  return useQuery({
    queryKey: ['my-shipments', waybillId],
    queryFn: () => apiGet(`/api/my-shipments/${waybillId}`),
    enabled: Boolean(waybillId),
  });
}

/** POST /api/my-shipments - a customer's own shipment request, status "requested" until staff confirm-and-issue it. See CargoWaybillService.requestShipment. */
export function useRequestShipment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/my-shipments', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['my-shipments'] }),
  });
}

// ---- staff: customer shipment requests awaiting review ----
// /api/cargo/requests (the "requested"-status inbox) and the
// confirm-and-issue action that turns one into a normal issued waybill -
// see CargoWaybillRepository.findAllByStatusAndTenantIdIsNull and
// CargoWaybillService.confirmAndIssue.

export function usePendingCargoRequests() {
  return useQuery({ queryKey: ['cargo', 'requests'], queryFn: () => apiGet('/api/cargo/requests') });
}

export function useConfirmAndIssueWaybill(waybillId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/cargo/waybills/${waybillId}/confirm-and-issue`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['cargo', 'requests'] });
      queryClient.invalidateQueries({ queryKey: ['cargo', 'waybills'] });
    },
  });
}

// ---- public cargo tracking (no login - see node-bff/src/routes/api.js's
// permitAll carve-out for this one path) ----

export function useTrackWaybill(waybillNumber, phone) {
  return useQuery({
    queryKey: ['cargo', 'track', waybillNumber, phone],
    queryFn: () => apiGet(`/api/cargo/track/${encodeURIComponent(waybillNumber)}?phone=${encodeURIComponent(phone)}`),
    enabled: Boolean(waybillNumber && phone),
    retry: false,
  });
}

// ---- platform_admin operator onboarding ----
// /api/platform/operators - cross-tenant by nature (PlatformController
// manages operators themselves, not anything scoped within one). POST
// provisions a real Keycloak Organization via the admin API first, then
// inserts the local row - see OperatorProvisioningService's javadoc for
// why there's no compensating rollback if the second step fails. DELETE
// soft-deactivates (status='inactive') with no reactivate endpoint - see
// PlatformController.deactivate's javadoc.

export function usePlatformOperators() {
  return useQuery({ queryKey: ['platform', 'operators'], queryFn: () => apiGet('/api/platform/operators') });
}

export function useCreateOperator() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/platform/operators', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['platform', 'operators'] }),
  });
}

export function useUpdateOperator() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ operatorId, ...body }) => apiPatch(`/api/platform/operators/${operatorId}`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['platform', 'operators'] }),
  });
}

export function useDeactivateOperator() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (operatorId) => apiDelete(`/api/platform/operators/${operatorId}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['platform', 'operators'] }),
  });
}

// /api/platform/partners - third-party API integration credentials (Partner
// API WS-1). POST provisions a real confidential Keycloak client
// (client-credentials grant, agent role) via KeycloakPartnerClient and
// returns the client secret ONCE - it is never stored, so the UI must show
// it immediately. DELETE disables the Keycloak client and flips the row to
// `revoked` (not a row delete - audit trail).

export function usePlatformPartners() {
  return useQuery({ queryKey: ['platform', 'partners'], queryFn: () => apiGet('/api/platform/partners') });
}

export function useCreatePartner() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/platform/partners', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['platform', 'partners'] }),
  });
}

export function useRevokePartner() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (partnerId) => apiDelete(`/api/platform/partners/${partnerId}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['platform', 'partners'] }),
  });
}
