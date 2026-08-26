package com.bustix.booking;

import com.bustix.booking.CreateBookingRequest.PassengerSeat;
import com.bustix.notification.Notification;
import com.bustix.notification.NotificationRepository;
import com.bustix.operator.Operator;
import com.bustix.operator.OperatorRepository;
import com.bustix.scheduling.Seat;
import com.bustix.scheduling.SeatRepository;
import com.bustix.scheduling.Trip;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class BookingWriter {

    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final BookingInfantRepository bookingInfantRepository;
    private final NotificationRepository notificationRepository;
    private final OperatorRepository operatorRepository;
    private final TicketNumberGenerator ticketNumberGenerator;
    private final BigDecimal vatRate;

    public BookingWriter(
            SeatRepository seatRepository,
            BookingRepository bookingRepository,
            BookingSeatRepository bookingSeatRepository,
            BookingInfantRepository bookingInfantRepository,
            NotificationRepository notificationRepository,
            OperatorRepository operatorRepository,
            TicketNumberGenerator ticketNumberGenerator,
            @Value("${bustix.ticketing.vat-rate}") BigDecimal vatRate) {
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.bookingInfantRepository = bookingInfantRepository;
        this.notificationRepository = notificationRepository;
        this.operatorRepository = operatorRepository;
        this.ticketNumberGenerator = ticketNumberGenerator;
        this.vatRate = vatRate;
    }

    @Transactional
    public Booking write(
            Trip trip,
            CreateBookingRequest request,
            String channel,
            UUID customerUserId,
            UUID agentUserId,
            String recipientEmail,
            String guestContactPhone) {

        // Keyed by seat id so each seat's passenger details can be matched
        // back up after resolving the real Seat rows below.
        Map<UUID, PassengerSeat> passengersBySeatId = new LinkedHashMap<>();
        for (PassengerSeat passenger : request.passengers()) {
            passengersBySeatId.put(passenger.seatId(), passenger);
        }

        List<Seat> seats = new ArrayList<>();
        for (UUID seatId : passengersBySeatId.keySet()) {
            Seat seat = seatRepository.findByIdAndTripId(seatId, trip.getId())
                .orElseThrow(() -> new NoSuchElementException("Seat not found on this trip: " + seatId));
            if (!"open".equals(seat.getStatus())) {
                throw new SeatConflictException("Seat no longer available: " + seatId);
            }
            seats.add(seat);
        }

        BigDecimal subtotalAmount = trip.getPrice().multiply(BigDecimal.valueOf(seats.size()));
        BigDecimal taxAmount = subtotalAmount.multiply(vatRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalAmount = subtotalAmount.add(taxAmount);

        // Ticket number carries an operator-derived prefix (see
        // TicketNumberGenerator) - "Unknown" mirrors the same fallback
        // TripController uses when an operator lookup somehow comes up
        // empty, which shouldn't happen in practice since trip.tenantId is
        // a real FK, but the generator still needs some string either way.
        String operatorName = operatorRepository.findById(trip.getTenantId())
                .map(Operator::getName)
                .orElse("Unknown");

        Booking booking = new Booking();
        booking.setTenantId(trip.getTenantId());
        booking.setTripId(trip.getId());
        booking.setCustomerUserId(customerUserId);
        booking.setAgentUserId(agentUserId);
        booking.setChannel(channel);
        booking.setGuestContactPhone(guestContactPhone);
        booking.setStatus("confirmed");
        booking.setIdempotencyKey(request.idempotencyKey());
        booking.setSubtotalAmount(subtotalAmount);
        booking.setTaxAmount(taxAmount);
        booking.setTotalAmount(totalAmount);
        booking.setTicketNumber(ticketNumberGenerator.nextTicketNumber(operatorName));
        booking.setBookingRef(ticketNumberGenerator.nextBookingRef());
        booking = bookingRepository.save(booking);

        for (Seat seat : seats) {
            seat.setStatus("booked");
            seatRepository.save(seat);

            PassengerSeat passenger = passengersBySeatId.get(seat.getId());

            BookingSeat bookingSeat = new BookingSeat();
            BookingSeat.Id id = new BookingSeat.Id();
            id.setBookingId(booking.getId());
            id.setSeatId(seat.getId());
            bookingSeat.setId(id);
            bookingSeat.setPrice(trip.getPrice());
            bookingSeat.setPassengerName(passenger.passengerName());
            bookingSeat.setPassengerPhone(passenger.passengerPhone());
            bookingSeat.setPassengerIdNumber(passenger.passengerIdNumber());
            bookingSeat.setPassengerIdType(passenger.passengerIdType());
            bookingSeat.setPassengerAge(passenger.age());
            bookingSeatRepository.save(bookingSeat);

            // Lap-sitting infants (age < 3) ride free with this seat's
            // passenger rather than getting a seat of their own - see
            // BookingInfant's javadoc for why they're not in `seats`/
            // `passengersBySeatId` at all.
            for (PassengerSeat.Infant infant : passenger.infants()) {
                BookingInfant bookingInfant = new BookingInfant();
                bookingInfant.setBookingId(booking.getId());
                bookingInfant.setSeatId(seat.getId());
                bookingInfant.setName(infant.name());
                bookingInfant.setAge(infant.age());
                bookingInfantRepository.save(bookingInfant);
            }
        }

        // Outbox write - see NotificationWorker for why this isn't a direct
        // synchronous call to an email provider. Skipped entirely when
        // there's no email to send to (a guest who left contactEmail
        // blank) - notifications.recipient is NOT NULL, and a missing
        // confirmation email isn't grounds to fail the booking itself.
        if (recipientEmail != null && !recipientEmail.isBlank()) {
            Notification notification = new Notification();
            notification.setBookingId(booking.getId());
            notification.setChannel("email");
            notification.setRecipient(recipientEmail);
            notification.setTemplate("booking_confirmed");
            notificationRepository.save(notification);
        }

        return booking;
    }
}
