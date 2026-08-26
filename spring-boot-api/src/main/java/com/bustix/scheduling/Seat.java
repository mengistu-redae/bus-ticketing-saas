package com.bustix.scheduling;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "seats")
@Getter
@Setter
public class Seat {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "seat_no", nullable = false)
    private String seatNo;

    /** Reserved for future per-class pricing - unused by v1's flat pricing. */
    @Column(name = "seat_class", nullable = false)
    private String seatClass = "standard";

    /** open or booked. */
    @Column(nullable = false)
    private String status = "open";
}
