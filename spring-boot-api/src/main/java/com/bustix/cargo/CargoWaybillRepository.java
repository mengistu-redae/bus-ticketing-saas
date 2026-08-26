package com.bustix.cargo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CargoWaybillRepository extends JpaRepository<CargoWaybill, UUID> {

    // Staff-scoped.
    List<CargoWaybill> findAllByTenantId(UUID tenantId);

    Optional<CargoWaybill> findByIdAndTenantId(UUID id, UUID tenantId);

    List<CargoWaybill> findAllByTenantIdAndTripId(UUID tenantId, UUID tripId);

    List<CargoWaybill> findAllByTenantIdAndStatus(UUID tenantId, String status);

    // Public track-by-number path (CargoWaybillController.track) - not
    // tenant-scoped, since the caller has no session/tenant at all; the
    // phone-match check in CargoWaybillService.track is what stands in for
    // an ownership check here.
    Optional<CargoWaybill> findByWaybillNumber(String waybillNumber);

    boolean existsByWaybillNumber(String waybillNumber);

    // Backs GET /api/my-shipments(/{id}) - a logged-in customer's shipment
    // history. CargoWaybill.bookingId is a plain UUID column, not a mapped
    // JPA relation to Booking, so ownership is a subquery against
    // com.bustix.booking.Booking rather than a derived-query property
    // path. A waybill with no bookingId (staff-created standalone parcel)
    // never matches either query - deliberate, see CLAUDE.md's Cargo
    // section on why "My Shipments" is scoped only through an attached
    // booking, not e.g. a phone match.
    @Query("SELECT w FROM CargoWaybill w WHERE w.bookingId IN "
            + "(SELECT b.id FROM Booking b WHERE b.customerUserId = :customerUserId) "
            + "ORDER BY w.createdAt DESC")
    List<CargoWaybill> findAllByBookingCustomerUserId(@Param("customerUserId") UUID customerUserId);

    @Query("SELECT w FROM CargoWaybill w WHERE w.id = :id AND w.bookingId IN "
            + "(SELECT b.id FROM Booking b WHERE b.customerUserId = :customerUserId)")
    Optional<CargoWaybill> findByIdAndBookingCustomerUserId(@Param("id") UUID id, @Param("customerUserId") UUID customerUserId);
}
