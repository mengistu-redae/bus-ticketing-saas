package com.bustix.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Boarding Gate State Machine's "Gate Lockout" (my-notes/
 * ethiopian_bus_system_specs.md section 4.1) - same fixed-delay polling
 * shape as NotificationWorker's outbox dispatcher. This job is a
 * visibility mechanism only (a boarding-closed trip drops out of
 * marketplace search, which already filters on status='scheduled') - the
 * real-time gate decision lives in BoardingService.checkIn, which checks
 * the trip's departureAt directly rather than trusting this poller has
 * already run for a given trip.
 */
@Component
public class TripLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(TripLifecycleScheduler.class);

    private final TripRepository tripRepository;

    public TripLifecycleScheduler(TripRepository tripRepository) {
        this.tripRepository = tripRepository;
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void closeBoardingForDepartedTrips() {
        int closed = tripRepository.closeBoardingForDepartedTrips(Instant.now());
        if (closed > 0) {
            log.info("Closed boarding for {} departed trip(s)", closed);
        }
    }
}
