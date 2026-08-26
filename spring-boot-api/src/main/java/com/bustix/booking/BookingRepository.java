package com.bustix.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findAllByTenantId(UUID tenantId);

    Optional<Booking> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Booking> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    // Ownership-scoped, not tenant-scoped: backs customer self-service
    // cancellation, where the caller has no tenant (see TenantContext's
    // javadoc) - a customer can only ever act on their own booking.
    Optional<Booking> findByIdAndCustomerUserId(UUID id, UUID customerUserId);

    // Backs GET /api/my-bookings - the list-my-own-bookings counterpart to
    // the ownership-scoped lookup above.
    List<Booking> findAllByCustomerUserId(UUID customerUserId);

    // Used by TicketNumberGenerator's uniqueness check before assigning a
    // freshly generated ticket_number/booking_ref.
    boolean existsByTicketNumber(String ticketNumber);

    boolean existsByBookingRef(String bookingRef);

    // Backs the public guest track-by-ref endpoint
    // (BookingService.trackByRefAndPhone) - not tenant- or
    // customer-scoped, since the caller has neither; the phone match done
    // by the caller is what stands in for an ownership check here, same as
    // CargoWaybillRepository.findByWaybillNumber.
    Optional<Booking> findByBookingRef(String bookingRef);
}
