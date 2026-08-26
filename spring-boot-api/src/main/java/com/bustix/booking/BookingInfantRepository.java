package com.bustix.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookingInfantRepository extends JpaRepository<BookingInfant, UUID> {

    List<BookingInfant> findAllByBookingId(UUID bookingId);
}
