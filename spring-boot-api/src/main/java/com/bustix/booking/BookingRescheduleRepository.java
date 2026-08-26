package com.bustix.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookingRescheduleRepository extends JpaRepository<BookingReschedule, UUID> {

    List<BookingReschedule> findAllByBookingId(UUID bookingId);
}
