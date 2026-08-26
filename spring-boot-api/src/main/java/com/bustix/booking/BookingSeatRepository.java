package com.bustix.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, BookingSeat.Id> {

    // Property traversal into the embedded id (id.bookingId) - used by
    // CancellationService to find which seats to free up.
    List<BookingSeat> findAllByIdBookingId(UUID bookingId);
}
