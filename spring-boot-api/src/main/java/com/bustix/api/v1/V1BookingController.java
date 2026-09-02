package com.bustix.api.v1;

import com.bustix.booking.Booking;
import com.bustix.booking.BookingInfant;
import com.bustix.booking.BookingInfantRepository;
import com.bustix.booking.BookingRepository;
import com.bustix.booking.BookingSeat;
import com.bustix.booking.BookingSeatRepository;
import com.bustix.booking.BookingService;
import com.bustix.booking.MultiSeatRescheduleNotSupportedException;
import com.bustix.booking.RescheduleBookingRequest;
import com.bustix.booking.SeatConflictException;
import com.bustix.booking.TenantMismatchException;
import com.bustix.booking.TooLateToRescheduleException;
import com.bustix.booking.OperatorInactiveException;
import com.bustix.refund.BookingAlreadyCancelledException;
import com.bustix.refund.Cancellation;
import com.bustix.refund.CancellationService;
import com.bustix.booking.BookingRescheduleService;
import com.bustix.scheduling.Seat;
import com.bustix.scheduling.SeatRepository;
import com.bustix.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Partner-facing bookings surface. Reads need the {@code bookings:read}
 * scope, writes need {@code bookings:write}. All tenant-scoped to the
 * partner's operator via {@link TenantContext}.
 *
 * A booking created here is {@code channel = "partner"} (see
 * {@code BookingService}) - the partner books for a walk-in with no Bustix
 * account, so contact details replace a customer id, same mechanics as a
 * guest booking.
 */
@RestController
@RequestMapping("/v1/bookings")
@Tag(name = "Bookings", description = "Create, read, cancel and reschedule the partner's own operator's bookings.")
public class V1BookingController {

    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final BookingInfantRepository bookingInfantRepository;
    private final SeatRepository seatRepository;
    private final CancellationService cancellationService;
    private final BookingRescheduleService bookingRescheduleService;

    public V1BookingController(
            BookingService bookingService,
            BookingRepository bookingRepository,
            BookingSeatRepository bookingSeatRepository,
            BookingInfantRepository bookingInfantRepository,
            SeatRepository seatRepository,
            CancellationService cancellationService,
            BookingRescheduleService bookingRescheduleService) {
        this.bookingService = bookingService;
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.bookingInfantRepository = bookingInfantRepository;
        this.seatRepository = seatRepository;
        this.cancellationService = cancellationService;
        this.bookingRescheduleService = bookingRescheduleService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_bookings:read')")
    @Operation(summary = "List the operator's bookings, newest first")
    public PageEnvelope<BookingView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<BookingView> all = bookingRepository.findAllByTenantId(TenantContext.require()).stream()
                .sorted(Comparator.comparing(Booking::getCreatedAt).reversed())
                .map(V1BookingController::toView)
                .toList();
        return PageEnvelope.of(all, page, size);
    }

    @GetMapping("/{bookingId}")
    @PreAuthorize("hasAuthority('SCOPE_bookings:read')")
    @Operation(summary = "Get one booking")
    public BookingView get(@PathVariable UUID bookingId) {
        return toView(ownedBooking(bookingId));
    }

    @GetMapping("/{bookingId}/seats")
    @PreAuthorize("hasAuthority('SCOPE_bookings:read')")
    @Operation(summary = "Get a booking's seats and passengers")
    public List<BookedSeatV1View> seats(@PathVariable UUID bookingId) {
        ownedBooking(bookingId);
        return bookedSeatViews(bookingId);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_bookings:write')")
    @Operation(summary = "Create a booking",
            description = "channel is recorded as \"partner\". Retrying with the same idempotencyKey returns the "
                    + "original booking rather than double-booking.")
    public BookingView create(@Valid @RequestBody CreateBookingV1Request request) {
        UUID tenantId = TenantContext.require();
        Booking booking = bookingService.createBooking(
                request.toInternal(), "partner", null, null, tenantId,
                request.contactEmail(), request.contactPhone());
        return toView(booking);
    }

    @PostMapping("/{bookingId}/cancel")
    @PreAuthorize("hasAuthority('SCOPE_bookings:write')")
    @Operation(summary = "Cancel a booking", description = "Frees the seats and computes a refund from the operator's policy.")
    public CancellationV1View cancel(
            @PathVariable UUID bookingId,
            @RequestBody(required = false) com.bustix.refund.CancelBookingRequest body) {
        Booking booking = ownedBooking(bookingId);
        String reason = body != null ? body.reason() : null;
        Cancellation cancellation = cancellationService.cancel(bookingId, TenantContext.require(), null, reason);
        return new CancellationV1View(
                bookingId, booking.getBookingRef(), "cancelled",
                cancellation.getRefundAmount(), cancellation.getRefundedAt());
    }

    @PostMapping("/{bookingId}/reschedule")
    @PreAuthorize("hasAuthority('SCOPE_bookings:write')")
    @Operation(summary = "Move a single-seat booking to a new trip and seat",
            description = "A flat mutation fee is added to the new trip's fare. Blocked within the operator's "
                    + "minimum-notice window before the current departure.")
    public BookingView reschedule(@PathVariable UUID bookingId, @Valid @RequestBody RescheduleBookingRequest request) {
        UUID tenantId = TenantContext.require();
        ownedBooking(bookingId);
        Booking booking = bookingRescheduleService.reschedule(
                bookingId, tenantId, request.newTripId(), request.newSeatId(), null);
        return toView(booking);
    }

    private Booking ownedBooking(UUID bookingId) {
        return bookingRepository.findByIdAndTenantId(bookingId, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Booking not found: " + bookingId));
    }

    private static BookingView toView(Booking b) {
        return new BookingView(
                b.getId(), b.getBookingRef(), b.getTicketNumber(), b.getTripId(), b.getTenantId(),
                b.getChannel(), b.getStatus(), b.getSubtotalAmount(), b.getTaxAmount(),
                b.getRescheduleFee(), b.getTotalAmount(), b.getCreatedAt());
    }

    private List<BookedSeatV1View> bookedSeatViews(UUID bookingId) {
        List<BookingSeat> bookingSeats = bookingSeatRepository.findAllByIdBookingId(bookingId);

        Map<UUID, Seat> seatsById = new HashMap<>();
        for (BookingSeat bs : bookingSeats) {
            seatRepository.findById(bs.getId().getSeatId()).ifPresent(s -> seatsById.put(s.getId(), s));
        }

        Map<UUID, List<BookedSeatV1View.InfantView>> infantsBySeatId = new HashMap<>();
        for (BookingInfant infant : bookingInfantRepository.findAllByBookingId(bookingId)) {
            infantsBySeatId.computeIfAbsent(infant.getSeatId(), k -> new ArrayList<>())
                    .add(new BookedSeatV1View.InfantView(infant.getName(), infant.getAge()));
        }

        return bookingSeats.stream().map(bs -> {
            UUID seatId = bs.getId().getSeatId();
            Seat seat = seatsById.get(seatId);
            return new BookedSeatV1View(
                    seatId,
                    seat != null ? seat.getSeatNo() : null,
                    seat != null ? seat.getSeatClass() : null,
                    bs.getPrice(),
                    bs.getPassengerName(),
                    bs.getPassengerPhone(),
                    bs.getPassengerIdNumber(),
                    bs.getPassengerIdType() != null ? bs.getPassengerIdType().name() : null,
                    bs.getPassengerAge(),
                    infantsBySeatId.getOrDefault(seatId, List.of()),
                    bs.getBoardingStatus(),
                    bs.getBoardedAt());
        }).toList();
    }

    // --- error mapping (consolidated into a /v1 @RestControllerAdvice in WS-3) ---

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }

    @ExceptionHandler({
            SeatConflictException.class,
            OperatorInactiveException.class,
            BookingAlreadyCancelledException.class,
            TooLateToRescheduleException.class,
            MultiSeatRescheduleNotSupportedException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleConflict(RuntimeException e) {
        return e.getMessage();
    }

    @ExceptionHandler(TenantMismatchException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String handleTenantMismatch(TenantMismatchException e) {
        return e.getMessage();
    }
}
