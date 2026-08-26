package com.bustix.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder NotificationSender: logs instead of calling a real provider.
 * Replace with a Spring Mail (or provider SDK) implementation when you're
 * ready to wire up real email delivery - the rest of the outbox flow
 * (writing, retrying, marking sent/failed) doesn't need to change.
 */
@Component
public class LoggingEmailSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(Notification notification) {
        log.info(
            "[STUB EMAIL] to={} template={} booking={}",
            notification.getRecipient(),
            notification.getTemplate(),
            notification.getBookingId()
        );
    }
}
