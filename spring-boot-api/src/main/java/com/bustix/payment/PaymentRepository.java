package com.bustix.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findAllByBookingId(UUID bookingId);

    Optional<Payment> findByIdAndBookingId(UUID id, UUID bookingId);

    // Cargo counterpart - see CargoPaymentController.
    List<Payment> findAllByWaybillId(UUID waybillId);

    Optional<Payment> findByIdAndWaybillId(UUID id, UUID waybillId);

    // Operator dashboard payment-method donut. Payment has no tenant_id of its
    // own, so it's scoped through whichever parent (booking OR waybill) it
    // belongs to - native, since that's an OR across two subqueries. Rows:
    // [method (String), count (Number), amount (Number)].
    @Query(value = """
            SELECT p.method, count(*), coalesce(sum(p.amount), 0)
            FROM payments p
            WHERE p.collected_at >= :since AND (
                p.booking_id IN (SELECT id FROM bookings WHERE tenant_id = :tenantId)
             OR p.waybill_id IN (SELECT id FROM cargo_waybills WHERE tenant_id = :tenantId))
            GROUP BY p.method
            """, nativeQuery = true)
    List<Object[]> paymentMethodBreakdown(@Param("tenantId") UUID tenantId, @Param("since") Instant since);
}
