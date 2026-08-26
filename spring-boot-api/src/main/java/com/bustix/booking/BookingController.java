package com.bustix.booking;

import com.bustix.tenant.TenantContext;
import com.bustix.user.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.bustix.scheduling.Seat;
import com.bustix.scheduling.SeatRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Handles both booking channels from the design conversation:
 *  - self_service: called by the customer BFF, JWT has role=customer, no org.
 *  - counter: called by the agent portal BFF, JWT has role=agent, org set.
 *
 * NOTE: the customer/agent BFFs are expected to authenticate the end user
 * and forward that user's own access token here - this controller trusts
 * whatever principal Spring Security resolved from the bearer token, it
 * does not separately re-derive "who is booking" from a request body field.
 */
@RestController
public class BookingController {

    private final BookingService bookingService;
    private final CurrentUserService currentUserService;
    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final BookingInfantRepository bookingInfantRepository;
    private final SeatRepository seatRepository;

    public BookingController(
            BookingService bookingService,
            CurrentUserService currentUserService,
            BookingRepository bookingRepository,
            BookingSeatRepository bookingSeatRepository,
            BookingInfantRepository bookingInfantRepository,
            SeatRepository seatRepository) {
        this.bookingService = bookingService;
        this.currentUserService = currentUserService;
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.bookingInfantRepository = bookingInfantRepository;
        this.seatRepository = seatRepository;
    }

    @PostMapping("/api/bookings")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'AGENT')")
    public Booking createBooking(
            @Valid @RequestBody CreateBookingRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        boolean isAgent = hasRole(jwt, "AGENT");
        String channel = isAgent ? "counter" : "self_service";

        // For an agent, TenantContext is populated by TenantContextFilter
        // from the org claim - this doubles as an authorization check: an
        // agent can only ever book trips that belong to their own operator,
        // enforced inside BookingWriter/Trip lookups by comparing tenant_id.
        UUID agentUserId = isAgent ? currentUserService.resolveInternalUserId(jwt) : null;
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        UUID agentTenantId = TenantContext.get();
        String recipientEmail = jwt.getClaimAsString("email");

        return bookingService.createBooking(request, channel, customerUserId, agentUserId, agentTenantId, recipientEmail, null);
    }

    /**
     * No account, no session, no JWT at all - a visitor books with contact
     * info instead of an identity. Deliberately a separate endpoint rather
     * than createBooking() above branching on an absent principal, same
     * "one endpoint per access pattern" convention as
     * /api/bookings/{id}/cancel vs /api/my-bookings/{id}/cancel. See
     * SecurityConfig for the permitAll() matcher - customerUserId/
     * agentUserId/agentTenantId are always null here since there's no
     * principal to resolve them from.
     */
    @PostMapping("/api/bookings/guest")
    public Booking createGuestBooking(@Valid @RequestBody CreateGuestBookingRequest request) {
        CreateBookingRequest inner = new CreateBookingRequest(
                request.tripId(), request.passengers(), request.idempotencyKey());
        return bookingService.createBooking(
                inner, "guest", null, null, null, request.contactEmail(), request.contactPhone());
    }

    /**
     * The guest's own "My Bookings" - since there's no account to scope a
     * list by, this is a one-at-a-time bookingRef + phone lookup instead,
     * exactly mirroring CargoWaybillController.track. Works for any
     * booking whose phone matches, not just channel = guest ones - see
     * BookingService.trackByRefAndPhone.
     */
    @GetMapping("/api/bookings/guest/track/{bookingRef}")
    public BookingTrackingView trackGuestBooking(@PathVariable String bookingRef, @RequestParam String phone) {
        return bookingService.trackByRefAndPhone(bookingRef, phone);
    }

    /**
     * The natural companion to the customer self-cancel endpoint added
     * this session (POST /api/my-bookings/{id}/cancel): a customer could
     * already cancel a booking they knew the id of, but had no way to look
     * that id up in the first place. Same ownership-scoped shape as that
     * endpoint - a customer token carries no tenant to scope by instead.
     */
    @GetMapping("/api/my-bookings")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<Booking> myBookings(@AuthenticationPrincipal Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        return bookingRepository.findAllByCustomerUserId(customerUserId);
    }

    @GetMapping("/api/my-bookings/{bookingId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Booking myBooking(@PathVariable UUID bookingId, @AuthenticationPrincipal Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        return bookingRepository.findByIdAndCustomerUserId(bookingId, customerUserId)
                .orElseThrow(() -> new NoSuchElementException("Booking not found: " + bookingId));
    }

    /**
     * Which seats a booking actually covers - added while planning the
     * frontend: booking_seats had no controller at all, and POST
     * /api/bookings' own response has no seat numbers, so there was
     * previously no way for a client to show "you booked 1A, 1B" anywhere
     * other than the single moment right after booking (and only if it
     * happened to still be holding that state client-side).
     */
    @GetMapping("/api/my-bookings/{bookingId}/seats")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<BookedSeatView> myBookingSeats(@PathVariable UUID bookingId, @AuthenticationPrincipal Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        bookingRepository.findByIdAndCustomerUserId(bookingId, customerUserId)
                .orElseThrow(() -> new NoSuchElementException("Booking not found: " + bookingId));

        return bookedSeatViews(bookingId);
    }

    // --- Below: staff-facing (agent/operator_admin) tenant-scoped booking
    // lookups. Previously nothing exposed BookingRepository.findAllByTenantId/
    // findByIdAndTenantId at all - an agent had no way to list or view their
    // own operator's bookings through the API, needed for the counter portal.

    @GetMapping("/api/bookings")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public List<Booking> bookings() {
        return bookingRepository.findAllByTenantId(TenantContext.require());
    }

    @GetMapping("/api/bookings/{bookingId}")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public Booking booking(@PathVariable UUID bookingId) {
        return bookingRepository.findByIdAndTenantId(bookingId, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Booking not found: " + bookingId));
    }

    @GetMapping("/api/bookings/{bookingId}/seats")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public List<BookedSeatView> bookingSeats(@PathVariable UUID bookingId) {
        bookingRepository.findByIdAndTenantId(bookingId, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Booking not found: " + bookingId));

        return bookedSeatViews(bookingId);
    }

    /** Shared by the customer and staff seats endpoints above - same join, different access check. */
    private List<BookedSeatView> bookedSeatViews(UUID bookingId) {
        List<BookingSeat> bookingSeats = bookingSeatRepository.findAllByIdBookingId(bookingId);
        Map<UUID, Seat> seatsById = new HashMap<>();
        for (BookingSeat bookingSeat : bookingSeats) {
            seatRepository.findById(bookingSeat.getId().getSeatId()).ifPresent(seat -> seatsById.put(seat.getId(), seat));
        }

        // Infants ride with a seat's passenger, not their own seat - see
        // BookingInfant's javadoc - so they're grouped by seatId here to
        // attach back onto that seat's view rather than listed separately.
        Map<UUID, List<BookedSeatView.InfantView>> infantsBySeatId = new HashMap<>();
        for (BookingInfant infant : bookingInfantRepository.findAllByBookingId(bookingId)) {
            infantsBySeatId
                    .computeIfAbsent(infant.getSeatId(), k -> new ArrayList<>())
                    .add(new BookedSeatView.InfantView(infant.getName(), infant.getAge()));
        }

        return bookingSeats.stream()
                .map(bookingSeat -> {
                    Seat seat = seatsById.get(bookingSeat.getId().getSeatId());
                    return new BookedSeatView(
                            bookingSeat.getId().getSeatId(),
                            seat != null ? seat.getSeatNo() : null,
                            seat != null ? seat.getSeatClass() : null,
                            bookingSeat.getPrice(),
                            bookingSeat.getPassengerName(),
                            bookingSeat.getPassengerPhone(),
                            bookingSeat.getPassengerIdNumber(),
                            bookingSeat.getPassengerIdType(),
                            bookingSeat.getPassengerAge(),
                            infantsBySeatId.getOrDefault(bookingSeat.getId().getSeatId(), List.of()),
                            bookingSeat.getBoardingStatus(),
                            bookingSeat.getBoardedAt());
                })
                .toList();
    }

    private boolean hasRole(Jwt jwt, String role) {
        var realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) return false;
        @SuppressWarnings("unchecked")
        var roles = (java.util.List<String>) realmAccess.get("roles");
        return roles != null && roles.stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }

    @ExceptionHandler(SeatConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleSeatConflict(SeatConflictException e) {
        return e.getMessage();
    }

    // TenantMismatchException's own javadoc says it "maps to HTTP 403 in
    // BookingController" - that was aspirational until now, nothing actually
    // did it, so it fell through to the default 500. Found by adding
    // integration test coverage for the counter-booking cross-tenant case.
    @ExceptionHandler(TenantMismatchException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String handleTenantMismatch(TenantMismatchException e) {
        return e.getMessage();
    }

    @ExceptionHandler(OperatorInactiveException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleOperatorInactive(OperatorInactiveException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
