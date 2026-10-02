package com.customertrace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Customer Trace API.
 *
 * A modular monolith: three portals (TryNearby admin, Restaurant, Creator) share one
 * database. All authorization is enforced here — the frontend only ever hides links.
 */
@SpringBootApplication
@EnableJpaAuditing
public class CustomerTraceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerTraceApplication.class, args);
    }
}