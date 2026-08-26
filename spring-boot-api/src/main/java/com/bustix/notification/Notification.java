package com.bustix.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter
@Setter
public class Notification {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    /** email for v1, sms/push once those providers are wired in. */
    @Column(nullable = false)
    private String channel = "email";

    @Column(nullable = false)
    private String recipient;

    /** Which message template to render, e.g. "booking_confirmed", "booking_cancelled". */
    @Column(nullable = false)
    private String template;

    /** pending, sent, or failed. */
    @Column(nullable = false)
    private String status = "pending";

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;
}
