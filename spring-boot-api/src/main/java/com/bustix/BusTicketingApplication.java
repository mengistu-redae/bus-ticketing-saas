package com.bustix;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // powers the NotificationWorker outbox poller
public class BusTicketingApplication {
    public static void main(String[] args) {
        SpringApplication.run(BusTicketingApplication.class, args);
    }
}
