package com.bustix.api.v1.webhook;

import java.util.UUID;

/**
 * A domain occurrence a partner may want delivered as a webhook. Published
 * via {@code ApplicationEventPublisher} from inside the domain service's
 * {@code @Transactional} method; {@link WebhookEventListener} handles it
 * synchronously so the delivery rows are written in that same transaction
 * (and roll back with it if the domain change does).
 *
 * @param type     see {@link PartnerEventTypes}
 * @param tenantId the operator the event belongs to - drives fan-out
 * @param data     the payload object, serialised to JSON as the event's {@code data}
 */
public record PartnerEvent(String type, UUID tenantId, Object data) {

    public static final class PartnerEventTypes {
        public static final String BOOKING_CONFIRMED = "booking.confirmed";
        public static final String BOOKING_CANCELLED = "booking.cancelled";
        public static final String BOOKING_RESCHEDULED = "booking.rescheduled";
        public static final String TRIP_RESCHEDULED = "trip.rescheduled";
        public static final String TRIP_CANCELLED = "trip.cancelled";
        public static final String WAYBILL_STATUS_CHANGED = "waybill.status_changed";

        private PartnerEventTypes() {
        }
    }
}
