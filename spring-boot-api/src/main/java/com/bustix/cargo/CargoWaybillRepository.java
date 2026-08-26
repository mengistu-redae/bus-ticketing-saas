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
    // history. Combines two ownership paths, not mutually exclusive: (a)
    // waybills attached to a booking this customer owns (bookingId is a
    // plain UUID column, not a mapped JPA relation to Booking, so this
    // half is a subquery against com.bustix.booking.Booking), and (b)
    // waybills this customer requested directly (customerUserId, added
    // 2026-08-26 for the customer-initiated request flow - see
    // CargoWaybillService.requestShipment). A staff-created standalone
    // waybill with neither set never matches either query, by design.
    @Query("SELECT w FROM CargoWaybill w WHERE w.customerUserId = :customerUserId "
            + "OR w.bookingId IN (SELECT b.id FROM Booking b WHERE b.customerUserId = :customerUserId) "
            + "ORDER BY w.createdAt DESC")
    List<CargoWaybill> findAllOwnedByCustomer(@Param("customerUserId") UUID customerUserId);

    @Query("SELECT w FROM CargoWaybill w WHERE w.id = :id AND (w.customerUserId = :customerUserId "
            + "OR w.bookingId IN (SELECT b.id FROM Booking b WHERE b.customerUserId = :customerUserId))")
    Optional<CargoWaybill> findByIdOwnedByCustomer(@Param("id") UUID id, @Param("customerUserId") UUID customerUserId);

    // Staff-facing pending-requests inbox (CargoWaybillController.pendingRequests)
    // - a "requested" waybill has no tenant yet (see CargoWaybill's own
    // javadoc), so it's invisible to the normal findAllByTenantId-scoped
    // list. Deliberately visible to ANY operator's staff until claimed -
    // v1 has no concept of "which operator should handle this request"
    // until a staff member picks a trip, same as a marketplace-wide inbox.
    List<CargoWaybill> findAllByStatusAndTenantIdIsNull(String status);
}
