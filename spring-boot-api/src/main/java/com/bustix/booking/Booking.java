package com.bustix.booking;

import com.bustix.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@Getter
@Setter
public class Booking extends BaseTenantEntity {

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "customer_user_id")
    private UUID customerUserId;

    /** Set only when channel = counter. */
    @Column(name = "agent_user_id")
    private UUID agentUserId;

    /** self_service, counter, or guest. */
    @Column(nullable = false)
    private String channel;

    /**
     * Set only when channel = guest - the one durable contact detail a
     * no-account booking has, so it can be looked back up later via
     * bookingRef + phone (see BookingService.trackByRefAndPhone). NULL for
     * self_service/counter bookings, same shape as agentUserId above.
     */
    @Column(name = "guest_contact_phone")
    private String guestContactPhone;

    /** confirmed or cancelled. */
    @Column(nullable = false)
    private String status = "confirmed";

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    /** subtotal + tax - see V3__ticketing_details.sql; pre-2026-08-24 bookings have tax_amount = 0. */
    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    /** Fare before VAT: trip price x seat count. */
    @Column(name = "subtotal_amount", nullable = false)
    private BigDecimal subtotalAmount;

    /** subtotal x bustix.ticketing.vat-rate at booking time - see BookingWriter. */
    @Column(name = "tax_amount", nullable = false)
    private BigDecimal taxAmount;

    /** Human-facing ticket number, distinct from id - see TicketNumberGenerator. */
    @Column(name = "ticket_number", nullable = false, unique = true)
    private String ticketNumber;

    /** Short passenger-facing booking reference (PNR-style), distinct from id and ticketNumber. */
    @Column(name = "booking_ref", nullable = false, unique = true)
    private String bookingRef;

    /**
     * Flat mutation fee from the most recent reschedule (0 if never
     * rescheduled) - layered on top of subtotal/tax, not folded into
     * either, so it stays visible as its own line. See
     * BookingRescheduleService and V6__boarding_and_reschedule.sql.
     */
    @Column(name = "reschedule_fee", nullable = false)
    private BigDecimal rescheduleFee = BigDecimal.ZERO;
}
