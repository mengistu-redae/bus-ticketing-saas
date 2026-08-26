package com.bustix.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findAllByBookingId(UUID bookingId);

    Optional<Payment> findByIdAndBookingId(UUID id, UUID bookingId);

    // Cargo counterpart - see CargoPaymentController.
    List<Payment> findAllByWaybillId(UUID waybillId);

    Optional<Payment> findByIdAndWaybillId(UUID id, UUID waybillId);
}
